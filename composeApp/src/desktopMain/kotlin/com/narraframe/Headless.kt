package com.narraframe

import com.narraframe.library.Library
import com.narraframe.models.GenerationMeta
import com.narraframe.pipeline.PipelineCallbacks
import com.narraframe.pipeline.StoryGenerationPipeline
import com.narraframe.platform.*
import com.narraframe.settings.AppSettings
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Runs one generation without the UI, for testing and benchmarking:
 *
 *   ./gradlew :composeApp:run -PheadlessScript=script.txt [-PheadlessOut=dir] [-PheadlessSdModel=id-or-path]
 *                             [-PheadlessResume=<existing generation folder>]
 *
 * Uses the saved settings; missing engines are installed first. A model override is
 * restored afterwards so the saved settings stay unchanged.
 */
internal fun runHeadless(scriptPath: String): Int = runBlocking {
    val settings = AppSettings()
    val out = System.getProperty("narraframe.headlessOut")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("java.io.tmpdir"), "narraframe-headless").absolutePath
    val modelOverride = System.getProperty("narraframe.headlessSdModel")?.takeIf { it.isNotBlank() }
    val savedModel = settings.localSDModelId
    val log: (String) -> Unit = { println("[headless] $it") }

    try {
        initializePlatform(log)
        if (modelOverride != null) settings.localSDModelId = modelOverride
        log("GPU: $gpuSummary — engines on ${activeAccelerator()}")
        if (getLocalSDBinaryPath() == null) { log("Installing stable-diffusion.cpp…"); downloadSdBinary { } }
        if (settings.localLlmMode != "server" && getLocalLLMBinaryPath() == null) {
            log("Installing llama.cpp…"); downloadLLMBinary { }
        }

        val script = File(scriptPath).readText()
        // -PheadlessResume=<generation folder> continues an existing run (finished steps are kept)
        val gen = System.getProperty("narraframe.headlessResume")?.takeIf { it.isNotBlank() }?.let { Library.load(it) }
            ?: Library.create(out, GenerationMeta(title = Library.titleFor(script), script = script, createdAt = System.currentTimeMillis()))
        log("Output: ${gen.path}")
        val result = StoryGenerationPipeline(settings).execute(
            prompt          = script,
            imageInterval   = settings.imageInterval,
            outputDirectory = gen.path,
            callbacks       = PipelineCallbacks(
                onStepChange  = { log("step: ${it.displayName}") },
                onProgress    = { _, _ -> },
                onLog         = { e -> if (e.level != com.narraframe.models.LogEntry.Level.DEBUG) println("  ${e.level}: ${e.message}") },
                onStateUpdate = { }
            )
        )
        log("Result: ${result.currentStep}  video=${result.generatedVideoPath}")
        result.timings.forEach { (k, v) -> log("  %-26s %7.1fs".format(k, v)) }
        if (result.isComplete) 0 else 1
    } finally {
        if (modelOverride != null) settings.localSDModelId = savedModel
        stopImageEngine()
    }
}
