package com.narraframe.services

import com.narraframe.models.ImageAspectRatio
import com.narraframe.models.LocalSDConsistency
import com.narraframe.models.LocalSDPreset
import com.narraframe.models.SdModelKind
import com.narraframe.platform.generateLocalSDImage
import com.narraframe.platform.tempDirectory
import com.narraframe.util.AppLogger

/**
 * Generates images locally via sd.cpp binary + GGUF model.
 * Image only: prompts come from the text model.
 */
class LocalSDProvider(
    private val modelPath:   String,
    private val sdBinPath:   String,
    private val preset:      LocalSDPreset      = LocalSDPreset.BALANCED,
    private val consistency: LocalSDConsistency = LocalSDConsistency(),
    private val aspect:      ImageAspectRatio   = ImageAspectRatio.LANDSCAPE_16_9,
    private val kind:        SdModelKind        = SdModelKind.of(modelPath)
) : AIProvider {

    /** Called with the sampling progress (0–1) of the image being generated. */
    var onProgress: (Float) -> Unit = {}

    override suspend fun generateText(prompt: String): String =
        throw UnsupportedOperationException("LocalSDProvider only generates images")

    override suspend fun generateImage(prompt: String): ByteArray {
        val (width, height) = preset.sizeFor(aspect)
        val steps   = kind.steps(preset)
        val cfg     = kind.cfgScale ?: consistency.cfgScale
        val sampler = kind.sampler ?: consistency.sampler
        AppLogger.info(
            "sd.cpp: ${preset.label} ${width}×$height $steps steps (${kind.label}) " +
            "cfg=$cfg sampler=$sampler seed=${consistency.seed}"
        )
        return generateLocalSDImage(
            prompt         = prompt,
            modelPath      = modelPath,
            sdBinPath      = sdBinPath,
            outputDir      = tempDirectory,
            width          = width,
            height         = height,
            steps          = steps,
            negativePrompt = consistency.negativePrompt,
            stylePrefix    = consistency.stylePrefix,
            cfgScale       = cfg,
            sampler        = sampler,
            seed           = consistency.seed,
            onProgress     = onProgress
        )
    }
}
