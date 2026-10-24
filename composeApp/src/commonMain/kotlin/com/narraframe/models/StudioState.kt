package com.narraframe.models

data class StudioState(
    val currentStep:         GenerationStep  = GenerationStep.IDLE,
    val overallProgress:     Float           = 0f,
    val stepProgress:        Float           = 0f,
    val logs:                List<LogEntry>  = emptyList(),

    /** Folder of the generation that is running or was run last. */
    val outputDirectory:     String?         = null,

    /** One image prompt per scene, available once the prompt step is done. */
    val scenePrompts:        List<String>    = emptyList(),
    /** Scene index → path of its generated image, filled while images are generated. */
    val sceneImages:         Map<Int, String> = emptyMap(),

    val narrationPath:       String?         = null,
    val generatedVideoPath:  String?         = null,

    val audioDurationSec:    Double          = 0.0,
    val estimatedImageCount: Int             = 0,

    val errorMessage:        String?         = null,
    val isGenerating:        Boolean         = false,

    /** Per-step error messages for inline display in the step bar. */
    val stepErrors:          Map<GenerationStep, String> = emptyMap(),

    /** Steps running right now; narration and visuals run in parallel. */
    val activeSteps:         Set<GenerationStep> = emptySet(),
    val doneSteps:           Set<GenerationStep> = emptySet(),

    /** Scene whose image is being rendered, and its sampling progress 0–1. */
    val renderingScene:      Int?            = null,
    val renderingProgress:   Float           = 0f,

    /** Seconds per step name, plus "SECONDS_PER_IMAGE" and "TOTAL". */
    val timings:             Map<String, Double> = emptyMap()
) {
    val isComplete: Boolean get() = currentStep == GenerationStep.COMPLETE
    val hasFailed:  Boolean get() = currentStep == GenerationStep.FAILED
    val isIdle:     Boolean get() = currentStep == GenerationStep.IDLE && !isGenerating

    fun addLog(entry: LogEntry): StudioState = copy(logs = logs + entry)
}
