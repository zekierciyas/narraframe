package com.narraframe.models

import kotlinx.serialization.Serializable

/** Contents of `meta.json` in every generation folder. */
@Serializable
data class GenerationMeta(
    val title:       String,
    val script:      String,
    val createdAt:   Long,
    /** "running" | "completed" | "failed" */
    val status:      String = "running",
    val durationSec: Double = 0.0,
    val sceneCount:  Int    = 0,
    val voice:       String = "",
    val style:       String = "",
    val aspectRatio: String = "16:9",
    /** Seconds per step of the last run, plus "SECONDS_PER_IMAGE" and "TOTAL". */
    val timings:     Map<String, Double> = emptyMap(),
    /** Backend the images were rendered on, e.g. "Metal". */
    val backend:     String = ""
)

/** One video in the library: a folder with images/, voices/, prompts/, videos/ and meta.json. */
data class Generation(val path: String, val meta: GenerationMeta) {
    val videoPath:     String get() = "$path/videos/$VIDEO_NAME.mp4"
    val narrationPath: String get() = "$path/voices/narration_001.mp3"
    val thumbnailPath: String get() = "$path/images/image_001.jpg"

    companion object {
        const val VIDEO_NAME = "story_video"
    }
}
