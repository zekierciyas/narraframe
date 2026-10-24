package com.narraframe.services

import com.narraframe.platform.installNeuralTtsModel
import com.narraframe.platform.installNeuralTtsRuntime
import com.narraframe.platform.isNeuralTtsModelInstalled
import com.narraframe.platform.isNeuralTtsRuntimeInstalled
import com.narraframe.platform.neuralTtsSynthesize
import com.narraframe.platform.readLocalFileBytes
import com.narraframe.settings.AppSettings
import com.narraframe.util.AppLogger

/**
 * Shared TTS implementation driving the embedded Python sidecar.
 *
 * Routing:
 *   · engine = "kokoro"      → Kokoro-82M ONNX (fast, CPU-friendly, ~80 MB weights)
 *   · engine = "qwen3_06b"   → Qwen3-TTS 0.6B (GPU preferred, ~1.2 GB)
 *   · engine = "qwen3_17b"   → Qwen3-TTS 1.7B (GPU required, ~4 GB)
 *
 * First call will lazily install the Python runtime + model weights via the
 * sidecar if they are not already cached. Subsequent calls are direct HTTP.
 */
class NeuralTtsService(
    private val settings: AppSettings = AppSettings()
) : TtsService {

    override suspend fun generateSpeech(text: String): ByteArray {
        val engine = settings.neuralTtsEngine
        val voice = when (engine) {
            "kokoro"    -> settings.kokoroVoice
            "qwen3_06b",
            "qwen3_17b" -> settings.qwen3Voice
            "qwen3_vd"  -> settings.voiceDesignPrompt
            else        -> settings.kokoroVoice
        }
        val speed = settings.neuralTtsSpeed

        // Lazy setup — in the normal flow this will already be done by the UI,
        // but running through here guarantees we never reach the sidecar with
        // a missing Python runtime or missing model weights.
        if (!isNeuralTtsRuntimeInstalled()) {
            AppLogger.warning("Neural TTS runtime missing – installing on first use…")
            installNeuralTtsRuntime { AppLogger.info(it) }
        }
        if (!isNeuralTtsModelInstalled(engine)) {
            AppLogger.warning("Model for $engine not cached – downloading on first use…")
            installNeuralTtsModel(engine) { AppLogger.info(it) }
        }

        val refBytes = if (engine == "qwen3_17b" && settings.qwen3UseVoiceClone && settings.qwen3VoiceClonePath.isNotBlank())
            readLocalFileBytes(settings.qwen3VoiceClonePath)
        else null

        // For voice design, voice is the text description of the desired voice style
        val effectiveVoice = if (engine == "qwen3_vd") settings.voiceDesignPrompt else voice

        return neuralTtsSynthesize(
            engine              = engine,
            voice               = effectiveVoice,
            speed               = speed,
            text                = text,
            referenceAudioBytes = refBytes
        )
    }

    companion object {
        /** Catalogue of the voices bundled with Kokoro-82M v1.0. */
        val kokoroVoices: List<Pair<String, String>> = listOf(
            "af_heart"    to "Heart (US, female)",
            "af_bella"    to "Bella (US, female)",
            "af_nicole"   to "Nicole (US, female)",
            "af_sarah"    to "Sarah (US, female)",
            "af_sky"      to "Sky (US, female)",
            "am_adam"     to "Adam (US, male)",
            "am_michael"  to "Michael (US, male)",
            "bf_emma"     to "Emma (UK, female)",
            "bf_isabella" to "Isabella (UK, female)",
            "bm_george"   to "George (UK, male)",
            "bm_lewis"    to "Lewis (UK, male)"
        )

        /** Catalogue of Qwen3-TTS preset voices (works for both 0.6B and 1.7B). */
        val qwen3Voices: List<Pair<String, String>> = listOf(
            "longwan" to "Longwan (zh/multilingual)",
            "cherry"  to "Cherry (female, warm)",
            "ethan"   to "Ethan (male, neutral)",
            "nova"    to "Nova (female, bright)",
            "sam"     to "Sam (male, deep)"
        )

        data class EngineInfo(
            val id: String,
            val displayName: String,
            val sizeLabel: String,
            val hardwareLabel: String,
            val description: String
        )

        val engines = listOf(
            EngineInfo(
                id            = "kokoro",
                displayName   = "Kokoro-82M",
                sizeLabel     = "~80 MB",
                hardwareLabel = "CPU ✓",
                description   = "Lightweight, fast, near real-time on CPU."
            ),
            EngineInfo(
                id            = "qwen3_06b",
                displayName   = "Qwen3-TTS 0.6B",
                sizeLabel     = "~1.2 GB",
                hardwareLabel = "GPU recommended",
                description   = "Expressive; slow on CPU, fast on CUDA / MPS."
            ),
            EngineInfo(
                id            = "qwen3_17b",
                displayName   = "Qwen3-TTS 1.7B",
                sizeLabel     = "~4 GB",
                hardwareLabel = "GPU required",
                description   = "Highest quality, voice cloning. Needs CUDA."
            ),
            EngineInfo(
                id            = "qwen3_vd",
                displayName   = "Qwen3-TTS Voice Design",
                sizeLabel     = "~4 GB",
                hardwareLabel = "GPU recommended",
                description   = "Describe a voice in natural language — tone, accent, emotion, style."
            )
        )
    }
}
