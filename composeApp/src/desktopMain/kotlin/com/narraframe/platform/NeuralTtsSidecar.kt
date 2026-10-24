package com.narraframe.platform

import com.narraframe.util.AppLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the lifecycle of the local Python sidecar that hosts Kokoro + Qwen3.
 *
 *   · Starts the process on-demand (first /tts call).
 *   · Reads the chosen port from the child's stdout (`PORT=<n>` line).
 *   · Proxies install_model + tts requests via Ktor over HTTP.
 *   · Installs a JVM shutdown hook that kills the child on app exit.
 *
 * Only one sidecar process runs per JVM.
 */
internal object NeuralTtsSidecar {

    private val startMutex = Mutex()
    private val state = AtomicReference<State?>(null)

    data class State(val process: Process, val baseUrl: String, val token: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun httpClient() = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 1_800_000   // 30 min — Qwen3 + long scripts can be slow
            connectTimeoutMillis = 10_000
            socketTimeoutMillis  = 1_800_000
        }
        install(ContentNegotiation) { json(json) }
    }

    /** Stops the sidecar, e.g. before its files move to another folder. Restarts lazily on next use. */
    fun shutdown() {
        state.getAndSet(null)?.process?.let { p ->
            p.destroy()
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /** Ensures the sidecar is running and /health returns ok. Lazy. */
    suspend fun ensureRunning(): State = startMutex.withLock {
        state.get()?.let { existing ->
            if (existing.process.isAlive) return existing
            AppLogger.warning("Neural TTS sidecar crashed – restarting")
            state.set(null)
        }

        if (!PythonRuntimeInstaller.isInstalled()) {
            error("Neural TTS runtime is not installed. Run setup first.")
        }

        val token = generateToken()
        val cmd = listOf(
            PythonRuntimeInstaller.venvPython.absolutePath,
            PythonRuntimeInstaller.serverPy.absolutePath,
            "--host", "127.0.0.1",
            "--port", "0",
            "--token", token,
            "--cache-dir", PythonRuntimeInstaller.cacheDir.absolutePath
        )
        AppLogger.info("Spawning neural TTS sidecar: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["PYTHONUNBUFFERED"] = "1"
        val proc = pb.start()

        // Read the `PORT=NNN` banner from stdout (with a hard timeout).
        val reader = proc.inputStream.bufferedReader()
        var port: Int? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                val tail = reader.readText()
                error("Sidecar exited during startup:\n$tail")
            }
            if (!reader.ready()) { delay(100); continue }
            val line = reader.readLine() ?: break
            AppLogger.debug("[sidecar] $line")
            if (line.startsWith("PORT=")) {
                port = line.removePrefix("PORT=").trim().toInt()
                break
            }
        }
        if (port == null) {
            proc.destroy()
            error("Sidecar did not announce its port within 30s")
        }

        // Background-drain the rest of stdout so the process buffer never fills up
        Thread({
            try {
                reader.useLines { lines -> lines.forEach { AppLogger.debug("[sidecar] $it") } }
            } catch (_: Exception) { /* ignore */ }
        }, "neural-tts-sidecar-log").apply { isDaemon = true }.start()

        val baseUrl = "http://127.0.0.1:$port"
        val newState = State(proc, baseUrl, token)
        state.set(newState)

        // Shut the process down on JVM exit
        Runtime.getRuntime().addShutdownHook(Thread {
            try { if (proc.isAlive) proc.destroy() } catch (_: Exception) {}
        })

        // Poll /health
        waitForHealth(newState)
        AppLogger.success("Neural TTS sidecar ready at $baseUrl")
        newState
    }

    private suspend fun waitForHealth(s: State) {
        val client = httpClient()
        try {
            val deadline = System.currentTimeMillis() + 30_000
            var lastErr: String? = null
            while (System.currentTimeMillis() < deadline) {
                try {
                    val r: HttpResponse = client.get("${s.baseUrl}/health") {
                        header("X-S2V-Token", s.token)
                    }
                    if (r.status.isSuccess()) return
                    lastErr = "HTTP ${r.status.value}"
                } catch (e: Exception) { lastErr = e.message }
                delay(300)
            }
            error("Sidecar /health never succeeded. Last error: $lastErr")
        } finally {
            client.close()
        }
    }

    // ── Public API used by NeuralTtsService ───────────────────────────────
    suspend fun hardwareHint(): String = withContext(Dispatchers.IO) {
        try {
            val s = ensureRunning()
            val client = httpClient()
            try {
                val resp: HttpResponse = client.get("${s.baseUrl}/health") {
                    header("X-S2V-Token", s.token)
                }
                if (!resp.status.isSuccess()) return@withContext "unknown"
                val body = resp.body<HealthResponse>()
                body.device
            } finally { client.close() }
        } catch (_: Exception) { "unknown" }
    }

    suspend fun installModel(engine: String, onLog: (String) -> Unit) = withContext(Dispatchers.IO) {
        val s = ensureRunning()
        val client = httpClient()
        try {
            onLog("Requesting model install: $engine…")
            val resp: HttpResponse = client.post("${s.baseUrl}/install_model") {
                header("X-S2V-Token", s.token)
                contentType(ContentType.Application.Json)
                setBody(InstallModelRequest(engine))
            }
            val body = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                error("install_model HTTP ${resp.status.value}: $body")
            }
            // Surface server-side log lines to the user
            try {
                val parsed = json.decodeFromString(InstallModelResponse.serializer(), body)
                parsed.logs.forEach { onLog(it) }
                if (parsed.error != null) error(parsed.error)
            } catch (_: Exception) {
                onLog(body.take(120))
            }
        } finally { client.close() }
    }

    suspend fun synthesize(
        engine:              String,
        voice:               String,
        speed:               Double,
        text:                String,
        referenceAudioBytes: ByteArray? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val s = ensureRunning()
        val client = httpClient()
        try {
            val refB64 = referenceAudioBytes?.let {
                java.util.Base64.getEncoder().encodeToString(it)
            }
            val resp: HttpResponse = client.post("${s.baseUrl}/tts") {
                header("X-S2V-Token", s.token)
                contentType(ContentType.Application.Json)
                setBody(TtsRequest(
                    engine              = engine,
                    voice               = voice,
                    speed               = speed,
                    text                = text,
                    reference_audio_b64 = refB64
                ))
            }
            if (!resp.status.isSuccess()) {
                val body = resp.bodyAsText()
                error("Sidecar /tts HTTP ${resp.status.value}: ${body.take(500)}")
            }
            resp.body<ByteArray>()
        } finally { client.close() }
    }

    fun isModelCached(engine: String): Boolean {
        val cache = PythonRuntimeInstaller.cacheDir
        return when (engine) {
            "kokoro" -> java.io.File(cache, "kokoro/kokoro-v1.0.onnx").exists() &&
                        java.io.File(cache, "kokoro/voices-v1.0.bin").exists()
            "qwen3_06b" -> hasHfSnapshot(cache, "Qwen--Qwen3-TTS-12Hz-0.6B-CustomVoice")
            "qwen3_17b" -> hasHfSnapshot(cache, "Qwen--Qwen3-TTS-12Hz-1.7B-CustomVoice")
            "qwen3_vd"  -> hasHfSnapshot(cache, "Qwen--Qwen3-TTS-12Hz-1.7B-VoiceDesign")
            else        -> false
        }
    }

    private fun hasHfSnapshot(cache: java.io.File, folder: String): Boolean {
        val hfRoot = java.io.File(cache, "hf/models--$folder/snapshots")
        if (!hfRoot.exists()) return false
        return hfRoot.listFiles()?.any { dir ->
            dir.isDirectory && dir.listFiles().orEmpty().any { it.name.endsWith(".safetensors") || it.name.endsWith(".bin") }
        } == true
    }

    private fun generateToken(): String {
        val b = ByteArray(24)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    // ── Wire formats ───────────────────────────────────────────────────────
    @Serializable
    private data class HealthResponse(val ok: Boolean, val device: String, val cache_dir: String = "")

    @Serializable
    private data class InstallModelRequest(val engine: String)

    @Serializable
    private data class InstallModelResponse(
        val ok: Boolean,
        val logs: List<String> = emptyList(),
        val error: String? = null
    )

    @Serializable
    private data class TtsRequest(
        val engine:              String,
        val voice:               String,
        val speed:               Double,
        val text:                String,
        val reference_audio_b64: String? = null
    )
}
