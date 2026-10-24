package com.narraframe.platform

import com.narraframe.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Keeps one stable-diffusion.cpp server (sd-server) running so the model is loaded once
 * instead of once per image. Images are requested over its A1111-style HTTP API and
 * progress is polled from /sdapi/v1/progress.
 *
 * The server restarts when the model or backend changes, stops after [IDLE_MS] without
 * work to free memory, and is killed when a request is cancelled or stalls.
 */
internal object SdServer {

    private const val IDLE_MS        = 10 * 60_000L
    private const val START_TIMEOUT  = 5 * 60_000L   // first model load from a slow drive
    private const val STALL_MS       = 3 * 60_000L   // no progress change → treat as hung

    private data class Running(val key: String, val process: Process, val port: Int, val log: ArrayDeque<String>)

    private val mutex = Mutex()
    @Volatile private var running: Running? = null
    @Volatile private var lastUse = 0L
    private var idleJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Backend the running server uses ("Metal", "CUDA", "Vulkan", "CPU"), for logs and the UI. */
    @Volatile var activeBackend: String = "—"
        private set

    @Serializable
    private data class Txt2Img(
        val prompt: String,
        val negative_prompt: String,
        val steps: Int,
        val cfg_scale: Float,
        val width: Int,
        val height: Int,
        val seed: Long,
        val sampler_name: String,
        val batch_size: Int = 1
    )

    suspend fun generate(
        serverBin:      String,
        modelPath:      String,
        prompt:         String,
        negativePrompt: String,
        width:          Int,
        height:         Int,
        steps:          Int,
        cfgScale:       Float,
        sampler:        String,
        seed:           Long,
        onProgress:     (Float) -> Unit
    ): ByteArray = mutex.withLock {
        val server = ensure(serverBin, modelPath)
        lastUse = System.currentTimeMillis()
        val body = json.encodeToString(Txt2Img.serializer(), Txt2Img(
            prompt, negativePrompt, steps, cfgScale, width, height,
            if (seed >= 0) seed else (Math.random() * Int.MAX_VALUE).toLong(), sampler
        ))
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/sdapi/v1/txt2img"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofHours(2))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        try {
            coroutineScope {
                val call = async(Dispatchers.IO) { http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await() }
                // Poll progress while the image renders; kill the server if it stops moving
                var lastStep = -1
                var lastChange = System.currentTimeMillis()
                while (!call.isCompleted) {
                    delay(500)
                    val step = progress(server.port)
                    if (step != null && step.first != lastStep) {
                        lastStep = step.first
                        lastChange = System.currentTimeMillis()
                        if (step.second > 0) onProgress(step.first.toFloat() / step.second)
                    }
                    if (!server.process.isAlive) throw Exception("sd-server exited: ${server.log.takeLast(8).joinToString(" | ")}")
                    if (System.currentTimeMillis() - lastChange > STALL_MS) {
                        stopLocked()
                        throw ProcessStalledException("Image generation stopped making progress for ${STALL_MS / 60_000} min")
                    }
                }
                val resp = call.await()
                if (resp.statusCode() !in 200..299) {
                    throw Exception("sd-server HTTP ${resp.statusCode()}: ${resp.body().take(300)}")
                }
                val b64 = json.parseToJsonElement(resp.body()).jsonObject["images"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                    ?: throw Exception("sd-server returned no image")
                onProgress(1f)
                Base64.getDecoder().decode(b64)
            }
        } catch (e: CancellationException) {
            // The server cannot abort a running image; restart it so the GPU is free at once
            stopLocked()
            throw e
        } finally {
            lastUse = System.currentTimeMillis()
            scheduleIdleStop()
        }
    }

    private suspend fun <T> java.util.concurrent.CompletableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            whenComplete { v, e -> if (e != null) cont.resumeWith(Result.failure(e.cause ?: e)) else cont.resumeWith(Result.success(v)) }
            cont.invokeOnCancellation { cancel(true) }
        }

    /** (step, totalSteps) or null when the endpoint does not answer. */
    private fun progress(port: Int): Pair<Int, Int>? = runCatching {
        val r = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/sdapi/v1/progress")).timeout(Duration.ofSeconds(3)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        val st = json.parseToJsonElement(r.body()).jsonObject["state"]!!.jsonObject
        st["sampling_step"]!!.jsonPrimitive.content.toInt() to st["sampling_steps"]!!.jsonPrimitive.content.toInt()
    }.getOrNull()

    private suspend fun ensure(serverBin: String, modelPath: String): Running {
        val accel = Hardware.accel
        val key = "$serverBin|$modelPath|$accel"
        running?.let { if (it.key == key && it.process.isAlive) return it }
        stopLocked()
        return try {
            start(serverBin, modelPath, accel, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!accel.isGpu) throw e
            // A GPU build that cannot start (driver, VRAM) falls back to the CPU backend
            AppLogger.warning("sd-server could not start on ${accel.label} (${e.message}); retrying on CPU")
            start(serverBin, modelPath, Accel.CPU, key)
        }
    }

    private suspend fun start(serverBin: String, modelPath: String, accel: Accel, key: String): Running {
        val port = ServerSocket(0).use { it.localPort }
        val cmd = buildList {
            add(serverBin)
            addAll(listOf("-m", modelPath))
            addAll(listOf("--listen-ip", "127.0.0.1", "--listen-port", port.toString()))
            if (accel == Accel.CPU) addAll(listOf("--backend", "cpu"))
            // Batched CFG renders blank images on Metal with current sd.cpp builds
            if (Hardware.isMac) addAll(listOf("--batched-cfg", "off"))
        }
        AppLogger.info("Starting sd-server (${accel.label}): ${cmd.joinToString(" ")}")
        val proc = Processes.track(ProcessBuilder(cmd).redirectErrorStream(true).start())
        proc.outputStream.close()
        val log = ArrayDeque<String>()
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.addLast(line); if (log.size > 80) log.removeFirst() }
                    if (line.contains("[E]") || line.contains("error", ignoreCase = true)) AppLogger.warning("[sd-server] $line")
                    else AppLogger.debug("[sd-server] $line")
                }
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + START_TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            if (!proc.isAlive) {
                throw Exception("sd-server exited (${proc.exitValue()}): ${synchronized(log) { log.takeLast(6).joinToString(" | ") }}")
            }
            val up = runCatching {
                http.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/sdapi/v1/sd-models")).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
                ).statusCode() == 200
            }.getOrDefault(false)
            if (up) {
                activeBackend = accel.label
                AppLogger.success("sd-server ready on ${accel.label} (port $port)")
                return Running(key, proc, port, log).also { running = it }
            }
            delay(300)
        }
        Processes.kill(proc)
        throw ProcessStalledException("sd-server did not start within ${START_TIMEOUT / 60_000} min")
    }

    private fun scheduleIdleStop() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            mutex.withLock {
                if (System.currentTimeMillis() - lastUse >= IDLE_MS) {
                    AppLogger.info("sd-server idle for ${IDLE_MS / 60_000} min — stopping to free memory")
                    stopLocked()
                }
            }
        }
    }

    private fun stopLocked() {
        running?.let { Processes.kill(it.process) }
        running = null
    }

    /** Stops the server, e.g. before the engines folder moves or a model is replaced. */
    fun stop() {
        running?.let { Processes.kill(it.process) }
        running = null
    }
}
