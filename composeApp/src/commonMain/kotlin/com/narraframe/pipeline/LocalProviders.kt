package com.narraframe.pipeline

import com.narraframe.models.ImageAspectRatio
import com.narraframe.models.LocalSDConsistency
import com.narraframe.models.LocalSDPreset
import com.narraframe.models.SdModelKind
import com.narraframe.platform.getLocalLLMBinaryPath
import com.narraframe.platform.getLocalLLMModelPath
import com.narraframe.platform.getLocalSDBinaryPath
import com.narraframe.platform.getLocalSDModelPath
import com.narraframe.services.AIProvider
import com.narraframe.services.LocalLLMProvider
import com.narraframe.services.LocalOfflineLLMProvider
import com.narraframe.services.LocalSDProvider
import com.narraframe.services.LocalTtsService
import com.narraframe.services.NeuralTtsService
import com.narraframe.services.PiperTtsService
import com.narraframe.services.TtsService
import com.narraframe.settings.AppSettings

/**
 * Builds the local engines selected in [AppSettings].
 * Throws with a user-facing message when something the selection needs is missing.
 */
object LocalProviders {

    /** Text model that turns the script into image prompts. */
    fun textProvider(settings: AppSettings): AIProvider = when (settings.localLlmMode) {
        "server" -> {
            if (settings.localLlmUrl.isBlank() || settings.localLlmModel.isBlank())
                throw Exception("Local server URL or model name is empty. Set them in Settings → Models.")
            LocalLLMProvider(baseUrl = settings.localLlmUrl, model = settings.localLlmModel)
        }
        else -> {
            val modelPath = getLocalLLMModelPath(settings.localLlmModelId)
                ?: throw Exception("Text model '${settings.localLlmModelId}' is not installed. Open Settings → Models.")
            val llamaBin = getLocalLLMBinaryPath()
                ?: throw Exception("The llama.cpp engine is not installed. Open Settings → Models.")
            LocalOfflineLLMProvider(modelPath = modelPath, llamaBinPath = llamaBin)
        }
    }

    fun imageProvider(settings: AppSettings): LocalSDProvider {
        val modelPath = getLocalSDModelPath(settings.localSDModelId)
            ?: throw Exception("Image model '${settings.localSDModelId}' is not installed. Open Settings → Models.")
        val sdBin = getLocalSDBinaryPath()
            ?: throw Exception("The stable-diffusion.cpp engine is not installed. Open Settings → Models.")
        return LocalSDProvider(
            modelPath   = modelPath,
            sdBinPath   = sdBin,
            preset      = LocalSDPreset.fromId(settings.localSDPreset),
            consistency = LocalSDConsistency(
                negativePrompt = settings.localSDNegPrompt,
                stylePrefix    = settings.localSDStylePrefix,
                cfgScale       = settings.localSDCfgScale,
                sampler        = settings.localSDSampler,
                seed           = settings.localSDSeed
            ),
            aspect      = ImageAspectRatio.fromId(settings.imageAspectRatio),
            kind        = SdModelKind.of(settings.localSDModelId)
        )
    }

    fun tts(settings: AppSettings): TtsService = when (settings.ttsProvider) {
        "piper" -> PiperTtsService(settings)
        "local" -> LocalTtsService(settings)
        else    -> NeuralTtsService(settings)
    }

    /** Human-readable name of the selected voice, e.g. "Kokoro · Heart". */
    fun voiceLabel(settings: AppSettings): String = when (settings.ttsProvider) {
        "piper" -> "Piper · " + settings.piperVoiceId.substringAfterLast('/').removeSuffix(".onnx")
        "local" -> "System · " + settings.localTtsVoice.ifBlank { "Default" }
        else -> when (settings.neuralTtsEngine) {
            "kokoro" -> "Kokoro · " + (NeuralTtsService.kokoroVoices.toMap()[settings.kokoroVoice]
                ?.substringBefore(" (") ?: settings.kokoroVoice)
            "qwen3_vd" -> "Qwen3 · Voice Design"
            else -> "Qwen3 · " + (NeuralTtsService.qwen3Voices.toMap()[settings.qwen3Voice]
                ?.substringBefore(" (") ?: settings.qwen3Voice)
        }
    }
}
