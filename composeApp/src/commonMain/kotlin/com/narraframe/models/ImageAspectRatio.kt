package com.narraframe.models

/**
 * Aspect ratio options for AI image generation.
 *
 * [promptHint] is appended to every image prompt so the AI model
 * produces images with the right framing and composition.
 */
enum class ImageAspectRatio(
    val id:         String,
    val label:      String,
    val emoji:      String,
    val promptHint: String
) {
    LANDSCAPE_16_9(
        id         = "16:9",
        label      = "16:9",
        emoji      = "🖥",
        promptHint = "wide landscape composition, 16:9 cinematic aspect ratio, horizontal framing"
    ),
    PORTRAIT_9_16(
        id         = "9:16",
        label      = "9:16",
        emoji      = "📱",
        promptHint = "vertical portrait composition, 9:16 aspect ratio, phone screen framing"
    ),
    SQUARE_1_1(
        id         = "1:1",
        label      = "1:1",
        emoji      = "⬛",
        promptHint = "square composition, 1:1 aspect ratio, centered framing"
    ),
    CLASSIC_4_3(
        id         = "4:3",
        label      = "4:3",
        emoji      = "📺",
        promptHint = "standard landscape composition, 4:3 aspect ratio, classic TV framing"
    ),
    TALL_3_4(
        id         = "3:4",
        label      = "3:4",
        emoji      = "🖼",
        promptHint = "tall portrait composition, 3:4 aspect ratio, portrait framing"
    );

    companion object {
        fun fromId(id: String): ImageAspectRatio =
            entries.find { it.id == id } ?: LANDSCAPE_16_9
    }
}
