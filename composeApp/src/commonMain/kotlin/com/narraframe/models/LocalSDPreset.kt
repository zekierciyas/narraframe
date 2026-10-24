package com.narraframe.models

/**
 * Represents a quality/speed preset for local sd.cpp image generation.
 *
 * Time estimates are based on a mid-range CPU (Apple M1 / Intel Core i7).
 * ARM Macs are typically 2–3× faster; older Intel Macs may be 2× slower.
 */
enum class LocalSDPreset(
    val id:                   String,
    val label:                String,
    val emoji:                String,
    val width:                Int,
    val height:               Int,
    val steps:                Int,
    val secsPerImage:         Int,   // conservative CPU estimate
    val qualityNote:          String
) {
    DRAFT(
        id             = "draft",
        label          = "Draft",
        emoji          = "⚡",
        width          = 384,
        height         = 224,
        steps          = 8,
        secsPerImage   = 45,
        qualityNote    = "Fast preview, low detail"
    ),
    BALANCED(
        id             = "balanced",
        label          = "Balanced",
        emoji          = "⚖️",
        width          = 512,
        height         = 288,
        steps          = 15,
        secsPerImage   = 120,
        qualityNote    = "Good quality, medium process time"
    ),
    QUALITY(
        id             = "quality",
        label          = "High Quality",
        emoji          = "🎨",
        width          = 512,
        height         = 512,
        steps          = 25,
        secsPerImage   = 320,
        qualityNote    = "Best Detail, High Process Time"
    );

    /** Total estimated seconds for [imageCount] images. */
    fun totalSecs(imageCount: Int): Int = secsPerImage * imageCount

    /** Human-readable per-image estimate, e.g. "~2 dk" or "~45 sn". */
    val perImageLabel: String get() = formatSecs(secsPerImage)

    /** Human-readable total estimate for [imageCount] images. */
    fun totalLabel(imageCount: Int): String = formatSecs(totalSecs(imageCount))

    /** Resolution label shown in UI, e.g. "512×288". */
    val resolutionLabel: String get() = "${width}×${height}"

    /**
     * Output size for [aspect]: the preset's longest side, the other side from the ratio,
     * both multiples of 64 (stable-diffusion.cpp rounds up to 64 anyway). The short side
     * rounds up so the video can crop to the exact ratio instead of adding black bars.
     */
    fun sizeFor(aspect: ImageAspectRatio): Pair<Int, Int> {
        val long  = maxOf(width, height)
        val (rw, rh) = aspect.id.split(':').map { it.toInt() }
        fun up64(v: Double) = maxOf(64, kotlin.math.ceil(v / 64).toInt() * 64)
        return if (rw >= rh) long to up64(long * rh.toDouble() / rw)
               else          up64(long * rw.toDouble() / rh) to long
    }

    companion object {
        fun fromId(id: String): LocalSDPreset = entries.find { it.id == id } ?: BALANCED

        fun formatSecs(secs: Int): String = when {
            secs < 60   -> "~${secs}s"
            secs < 3600 -> {
                val m = secs / 60
                val s = secs % 60
                if (s == 0) "~${m} min" else "~${m} min ${s}s"
            }
            else -> {
                val h = secs / 3600
                val m = (secs % 3600) / 60
                if (m == 0) "~${h} hr" else "~${h} hr ${m} min"
            }
        }
    }
}
