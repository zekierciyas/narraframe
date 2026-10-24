package com.narraframe.platform

import com.narraframe.util.AppLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

internal object LocalModelManager {

    private val modelsDir: File get() = DataDir.models

    // ── Piper TTS voice models ────────────────────────────────────────────────
    // Voice model files are hosted on Hugging Face as they are large ONNX binaries.
    // Each voice needs both a .onnx file and a .onnx.json config.
    data class PiperVoice(
        val id:     String,
        val name:   String,
        val sizeMB: Int,
        val lang:   String,
        val onnxUrl: String,
        val jsonUrl: String
    )

    val piperVoices = listOf(
        PiperVoice(
            id      = "en_US-amy-medium",
            name    = "Amy (US English)",
            sizeMB  = 63,
            lang    = "en-US",
            onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx",
            jsonUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json"
        ),
        PiperVoice(
            id      = "en_US-ryan-medium",
            name    = "Ryan (US English)",
            sizeMB  = 63,
            lang    = "en-US",
            onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx",
            jsonUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx.json"
        ),
        PiperVoice(
            id      = "en_US-joe-medium",
            name    = "Joe (US English)",
            sizeMB  = 63,
            lang    = "en-US",
            onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/joe/medium/en_US-joe-medium.onnx",
            jsonUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/joe/medium/en_US-joe-medium.onnx.json"
        ),
        PiperVoice(
            id      = "en_GB-alan-medium",
            name    = "Alan (British English)",
            sizeMB  = 63,
            lang    = "en-GB",
            onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx",
            jsonUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/en_GB-alan-medium.onnx.json"
        ),
        PiperVoice(
            id      = "en_US-lessac-medium",
            name    = "Lessac (US English)",
            sizeMB  = 63,
            lang    = "en-US",
            onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
            jsonUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json"
        ),
        PiperVoice(
            id      = "en_GB-alba-medium",
            name    = "Alba (British English)",
            sizeMB  = 63,
            lang    = "en-GB",
            onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alba/medium/en_GB-alba-medium.onnx",
            jsonUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alba/medium/en_GB-alba-medium.onnx.json"
        )
    )

    private val piperDir: File get() = DataDir.dir("models/piper")

    fun getPiperVoiceFile(voiceId: String): File = File(piperDir, "$voiceId.onnx")

    fun getCachedVoice(voiceId: String): String? =
        getPiperVoiceFile(voiceId).takeIf { it.exists() && it.length() > 1000 }?.absolutePath

    // ── Stable Diffusion GGUF models ──────────────────────────────────────────
    data class SDModel(
        val id:     String,
        val name:   String,
        val sizeMB: Int,
        val url:    String,
        val desc:   String
    )

    val sdModels = listOf(
        SDModel(
            id     = "dreamshaper8-lcm-q4",
            name   = "DreamShaper 8 LCM (Fast)",
            sizeMB = 1625,
            url    = "https://huggingface.co/haven-ai-companion/dreamshaper8-lcm-gguf/resolve/main/DreamShaper8_LCM_q4_0.gguf",
            desc   = "4–8 steps instead of 15–25: several times faster, rich detail. Best default."
        ),

        SDModel(
            id     = "sd-1.5-q4",
            name   = "Stable Diffusion 1.5",
            sizeMB = 1750,
            url    = "https://huggingface.co/gpustack/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-Q4_0.gguf",
            desc   = "Fast, good quality. Best for general scenes."
        ),

        SDModel(
            id     = "sd-1.5-q8",
            name   = "Stable Diffusion 1.5 (HQ)",
            sizeMB = 1880,
            url    = "https://huggingface.co/gpustack/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-Q8_0.gguf",
            desc   = "Higher quality, larger file. Slower on CPU."
        ),

        SDModel(
            id     = "sd-2.1-q4",
            name   = "Stable Diffusion 2.1",
            sizeMB = 1740,
            url    = "https://huggingface.co/jiaowobaba02/stable-diffusion-v2-1-GGUF/resolve/main/stable_diffusion-ema-pruned-v2-1_768.q4_1.gguf",
            desc   = "Trained for 768 px; usually weaker than SD 1.5 at the small sizes used here."
        )
    )

    fun getSDModelFile(modelId: String): File = File(modelsDir, "$modelId.gguf")

    fun getCachedSDModel(modelId: String): String? =
        getSDModelFile(modelId).takeIf { it.exists() && it.length() > 10_000 }?.absolutePath

    suspend fun downloadSDModel(modelId: String, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val model = sdModels.find { it.id == modelId }
                ?: throw IllegalArgumentException("Unknown model: $modelId")

            val file    = getSDModelFile(modelId)
            val tmpFile = File(modelsDir, "$modelId.gguf.tmp")

            if (file.exists() && file.length() > 10_000) {
                AppLogger.info("Model already cached: $modelId")
                return@withContext file.absolutePath
            }

            // Resume support: check if a partial temp file exists
            val resumeFrom = if (tmpFile.exists() && tmpFile.length() > 0) tmpFile.length() else 0L
            if (resumeFrom > 0) {
                onProgress("Resuming from ${resumeFrom / 1_048_576} MB…")
                AppLogger.info("Resuming download of ${model.name} from byte $resumeFrom")
            } else {
                onProgress("Connecting… (${model.sizeMB} MB)")
                AppLogger.info("Downloading SD model: ${model.name} from ${model.url}")
            }

            val okClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)   // no read timeout for large files
                .build()

            val requestBuilder = Request.Builder()
                .url(model.url)
                .header("User-Agent", "Narraframe/1.0")
            if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")
            val request = requestBuilder.build()

            // Cancel OkHttp call when the coroutine is cancelled
            val call = okClient.newCall(request)
            coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    call.cancel()
                    AppLogger.info("Download cancelled for $modelId – temp file kept for resume")
                }
            }

            try {
                val response = call.execute()
                // 206 = Partial Content (server supports resume), 200 = full file
                when (response.code) {
                    200, 206 -> Unit
                    401, 403 -> error("HTTP ${response.code}: authentication required. Download the .gguf manually and use 'Browse'.")
                    else     -> error("HTTP ${response.code}: ${model.url}")
                }

                val body = response.body ?: error("Empty response body")

                // For 206: Content-Range: bytes <start>-<end>/<total>
                val totalBytes: Long = when (response.code) {
                    206 -> response.header("Content-Range")
                        ?.substringAfter("/")?.trim()?.toLongOrNull() ?: -1L
                    else -> body.contentLength()
                }

                var downloaded = resumeFrom
                var lastReportedPct = if (totalBytes > 0) (resumeFrom * 100 / totalBytes).toInt() else -1

                // Append to temp file if resuming, otherwise overwrite
                body.byteStream().use { input ->
                    java.io.FileOutputStream(tmpFile, resumeFrom > 0 && response.code == 206)
                        .buffered(65_536).use { output ->
                            val buf = ByteArray(65_536)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                coroutineContext.ensureActive()
                                output.write(buf, 0, read)
                                downloaded += read
                                if (totalBytes > 0) {
                                    val pct = (downloaded * 100 / totalBytes).toInt()
                                    if (pct != lastReportedPct) {
                                        lastReportedPct = pct
                                        onProgress("$pct%  –  ${downloaded / 1_048_576} MB / ${totalBytes / 1_048_576} MB")
                                    }
                                } else {
                                    val mb = downloaded / 1_048_576
                                    if (mb % 10 == 0L) onProgress("${mb} MB downloaded…")
                                }
                            }
                        }
                }

                // Atomic rename: only on full successful download
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
                AppLogger.success("Model downloaded: ${model.name} (${file.length() / 1_048_576} MB)")
                onProgress("Download complete ✓")
                file.absolutePath

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Do NOT delete tmpFile – keep it for resume next time
                AppLogger.info("Download of $modelId paused at ${tmpFile.length() / 1_048_576} MB")
                throw e
            } catch (e: Exception) {
                // Network / HTTP error – keep tmpFile for potential resume
                AppLogger.error("Download error for $modelId: ${e.message}")
                throw e
            }
        }

    suspend fun downloadVoice(voiceId: String, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val voice = piperVoices.find { it.id == voiceId }
                ?: throw IllegalArgumentException("Voice not found: $voiceId")

            val file    = getPiperVoiceFile(voiceId)
            val tmpFile = File(piperDir, "$voiceId.onnx.tmp")
            val jsonFile = File(piperDir, "$voiceId.onnx.json")

            if (file.exists() && file.length() > 1000) {
                AppLogger.info("Piper voice already cached: $voiceId")
                // Also ensure JSON config exists
                if (!jsonFile.exists()) downloadPiperJson(voice.jsonUrl, jsonFile, onProgress)
                return@withContext file.absolutePath
            }

            val resumeFrom = if (tmpFile.exists() && tmpFile.length() > 0) tmpFile.length() else 0L
            if (resumeFrom > 0) {
                onProgress("Resuming from ${resumeFrom / 1_048_576} MB…")
            } else {
                onProgress("Connecting… (${voice.sizeMB} MB)")
                AppLogger.info("Downloading Piper voice: ${voice.name} from ${voice.onnxUrl}")
            }

            val okClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

            val requestBuilder = Request.Builder()
                .url(voice.onnxUrl)
                .header("User-Agent", "Narraframe/1.0")
            if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")
            val request = requestBuilder.build()

            val call = okClient.newCall(request)
            coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }

            try {
                val response = call.execute()
                when (response.code) {
                    200, 206 -> Unit
                    else     -> error("HTTP ${response.code}: ${voice.onnxUrl}")
                }
                val body = response.body ?: error("Empty response body")
                val totalBytes: Long = when (response.code) {
                    206 -> response.header("Content-Range")?.substringAfter("/")?.trim()?.toLongOrNull() ?: -1L
                    else -> body.contentLength()
                }
                var downloaded = resumeFrom
                var lastPct = if (totalBytes > 0) (resumeFrom * 100 / totalBytes).toInt() else -1

                body.byteStream().use { input ->
                    java.io.FileOutputStream(tmpFile, resumeFrom > 0 && response.code == 206)
                        .buffered(65_536).use { output ->
                            val buf = ByteArray(65_536)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                coroutineContext.ensureActive()
                                output.write(buf, 0, read)
                                downloaded += read
                                if (totalBytes > 0) {
                                    val pct = (downloaded * 100 / totalBytes).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress("$pct%  –  ${downloaded / 1_048_576} MB / ${totalBytes / 1_048_576} MB")
                                    }
                                }
                            }
                        }
                }
                if (!tmpFile.renameTo(file)) { tmpFile.copyTo(file, overwrite = true); tmpFile.delete() }
                AppLogger.success("Piper voice downloaded: ${voice.name}")

                // Download matching JSON config file
                onProgress("Downloading voice config…")
                downloadPiperJson(voice.jsonUrl, jsonFile, onProgress)

                onProgress("Download complete ✓")
                file.absolutePath
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.info("Piper voice download paused for $voiceId")
                throw e
            } catch (e: Exception) {
                AppLogger.error("Piper voice download error: ${e.message}")
                throw e
            }
        }

    private suspend fun downloadPiperJson(url: String, dest: File, onProgress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                val client = HttpClient(OkHttp) {
                    install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 30_000 }
                }
                val bytes: ByteArray = client.get(url).body()
                client.close()
                dest.writeBytes(bytes)
                AppLogger.info("Piper JSON config saved: ${dest.name}")
            } catch (e: Exception) {
                AppLogger.warning("Could not download Piper JSON config (${e.message}) – voice may still work")
            }
        }

    // ModelInfo alias for wasmJs compat
    data class ModelInfo(val id: String, val name: String, val sizeMB: Int, val url: String)

    // ── LLM GGUF models ───────────────────────────────────────────────────────
    data class LLMModel(
        val id:     String,
        val name:   String,
        val sizeMB: Int,
        val url:    String,
        val desc:   String
    )

    private val llmDir: File get() = DataDir.dir("models/llm")

    val llmModels = listOf(
        LLMModel(
            id     = "llama-3.2-1b-q4",
            name   = "Llama 3.2 1B (Fast)",
            sizeMB = 790,
            url    = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            desc   = "~800 MB · Very fast on CPU. Good for short scripts."
        ),
        LLMModel(
            id     = "llama-3.2-3b-q4",
            name   = "Llama 3.2 3B (Quality)",
            sizeMB = 1980,
            url    = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            desc   = "~2 GB · Better quality, moderate speed."
        ),
        LLMModel(
            id     = "phi-3.5-mini-q4",
            name   = "Phi-3.5 Mini (Best Quality)",
            sizeMB = 2200,
            url    = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            desc   = "~2.2 GB · Excellent reasoning, best results."
        )
    )

    fun getLLMModelFile(modelId: String): File = File(llmDir, "$modelId.gguf")

    fun getCachedLLMModel(modelId: String): String? {
        if (modelId.endsWith(".gguf") && File(modelId).exists()) return modelId
        return getLLMModelFile(modelId).takeIf { it.exists() && it.length() > 10_000 }?.absolutePath
    }

    suspend fun downloadLLMModel(modelId: String, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val model = llmModels.find { it.id == modelId }
                ?: throw IllegalArgumentException("Unknown LLM model: $modelId")

            val file    = getLLMModelFile(modelId)
            val tmpFile = File(llmDir, "$modelId.gguf.tmp")

            if (file.exists() && file.length() > 10_000) {
                AppLogger.info("LLM model already cached: $modelId")
                return@withContext file.absolutePath
            }

            val resumeFrom = if (tmpFile.exists() && tmpFile.length() > 0) tmpFile.length() else 0L
            if (resumeFrom > 0) {
                onProgress("Resuming from ${resumeFrom / 1_048_576} MB…")
                AppLogger.info("Resuming LLM model download: ${model.name} from byte $resumeFrom")
            } else {
                onProgress("Connecting… (${model.sizeMB} MB)")
                AppLogger.info("Downloading LLM model: ${model.name} from ${model.url}")
            }

            val okClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .build()

            val requestBuilder = Request.Builder()
                .url(model.url)
                .header("User-Agent", "Narraframe/1.0")
            if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")
            val request = requestBuilder.build()

            val call = okClient.newCall(request)
            coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    call.cancel()
                    AppLogger.info("LLM download cancelled for $modelId – temp file kept for resume")
                }
            }

            try {
                val response = call.execute()
                when (response.code) {
                    200, 206 -> Unit
                    401, 403 -> error("HTTP ${response.code}: authentication required. Download the .gguf manually and browse to it.")
                    else     -> error("HTTP ${response.code}: ${model.url}")
                }

                val body = response.body ?: error("Empty response body")

                val totalBytes: Long = when (response.code) {
                    206 -> response.header("Content-Range")
                        ?.substringAfter("/")?.trim()?.toLongOrNull() ?: -1L
                    else -> body.contentLength()
                }

                var downloaded = resumeFrom
                var lastReportedPct = if (totalBytes > 0) (resumeFrom * 100 / totalBytes).toInt() else -1

                body.byteStream().use { input ->
                    java.io.FileOutputStream(tmpFile, resumeFrom > 0 && response.code == 206)
                        .buffered(65_536).use { output ->
                            val buf = ByteArray(65_536)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                coroutineContext.ensureActive()
                                output.write(buf, 0, read)
                                downloaded += read
                                if (totalBytes > 0) {
                                    val pct = (downloaded * 100 / totalBytes).toInt()
                                    if (pct != lastReportedPct) {
                                        lastReportedPct = pct
                                        onProgress("$pct%  –  ${downloaded / 1_048_576} MB / ${totalBytes / 1_048_576} MB")
                                    }
                                } else {
                                    val mb = downloaded / 1_048_576
                                    if (mb % 10 == 0L) onProgress("${mb} MB downloaded…")
                                }
                            }
                        }
                }

                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
                AppLogger.success("LLM model downloaded: ${model.name} (${file.length() / 1_048_576} MB)")
                onProgress("Download complete ✓")
                file.absolutePath

            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.info("LLM download of $modelId paused at ${tmpFile.length() / 1_048_576} MB")
                throw e
            } catch (e: Exception) {
                AppLogger.error("LLM download error for $modelId: ${e.message}")
                throw e
            }
        }
}
