package com.narraframe.models

enum class GenerationStep(val displayName: String) {
    IDLE                    ("Idle"),
    GENERATING_SCRIPT       ("Generating Script"),
    GENERATING_AUDIO        ("Generating Audio"),
    ANALYZING_AUDIO         ("Analyzing Audio"),
    GENERATING_IMAGE_PROMPTS("Generating Image Prompts"),
    GENERATING_IMAGES       ("Generating Images"),
    COMPOSING_VIDEO         ("Composing Video"),
    COMPLETE                ("Complete"),
    FAILED                  ("Failed");

    companion object {
        /** Ordered steps that make up the active pipeline. */
        val pipelineSteps: List<GenerationStep> = listOf(
            GENERATING_AUDIO,
            ANALYZING_AUDIO,
            GENERATING_IMAGE_PROMPTS,
            GENERATING_IMAGES,
            COMPOSING_VIDEO
        )
    }
}
