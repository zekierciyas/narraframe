package com.narraframe.models

/**
 * Visual style presets for local image generation.
 *
 * Each preset is a curated combination of style prefix, negative prompt,
 * CFG scale and sampler that produces a consistent artistic look.
 * Selecting a preset overwrites all four consistency fields at once;
 * users can still fine-tune individual fields afterward.
 */
enum class SDStylePreset(
    val id:             String,
    val label:          String,
    val emoji:          String,
    val description:    String,
    val stylePrefix:    String,
    val negativePrompt: String,
    val cfgScale:       Float,
    val sampler:        String
) {
    DEFAULT(
        id             = "default",
        label          = "Default",
        emoji          = "🎯",
        description    = "No style lock — model decides freely",
        stylePrefix    = "",
        negativePrompt = LocalSDConsistency.DEFAULT_NEGATIVE,
        cfgScale       = 7f,
        sampler        = "euler_a"
    ),
    CINEMATIC(
        id             = "cinematic",
        label          = "Cinematic",
        emoji          = "🎬",
        description    = "Film-like, dramatic lighting, widescreen mood",
        stylePrefix    = "cinematic photo, 35mm film, anamorphic lens, dramatic lighting, " +
                         "shallow depth of field, 8k resolution, ultra-detailed,",
        negativePrompt = "${LocalSDConsistency.DEFAULT_NEGATIVE}, " +
                         "animated, cartoon, painting, sketch, flat colors",
        cfgScale       = 7.5f,
        sampler        = "dpm++2m"
    ),
    PHOTOREALISTIC(
        id             = "photorealistic",
        label          = "Photorealistic",
        emoji          = "📷",
        description    = "Hyper-realistic DSLR photography look",
        stylePrefix    = "photorealistic, hyperrealistic, DSLR photograph, professional photography, " +
                         "sharp focus, natural lighting, 8k, ultra-detailed,",
        negativePrompt = "${LocalSDConsistency.DEFAULT_NEGATIVE}, " +
                         "cartoon, anime, painting, illustration, drawing, unrealistic",
        cfgScale       = 8f,
        sampler        = "dpm++2mv2"
    ),
    ANIME(
        id             = "anime",
        label          = "Anime",
        emoji          = "🎌",
        description    = "Japanese animation style with vibrant colors",
        stylePrefix    = "anime style, manga illustration, vibrant colors, clean line art, " +
                         "studio quality, detailed character design,",
        negativePrompt = "${LocalSDConsistency.DEFAULT_NEGATIVE}, " +
                         "realistic, photograph, 3d render, western cartoon, blurry lines",
        cfgScale       = 8f,
        sampler        = "euler_a"
    ),
    OIL_PAINTING(
        id             = "oil_painting",
        label          = "Oil Painting",
        emoji          = "🖼️",
        description    = "Classical oil painting, rich textures and depth",
        stylePrefix    = "oil painting, classical art, detailed brushwork, rich textures, " +
                         "masterpiece, museum quality, impasto technique,",
        negativePrompt = "${LocalSDConsistency.DEFAULT_NEGATIVE}, " +
                         "photo, digital art, cartoon, anime, 3d render, flat",
        cfgScale       = 7f,
        sampler        = "heun"
    ),
    WATERCOLOR(
        id             = "watercolor",
        label          = "Watercolor",
        emoji          = "💧",
        description    = "Soft flowing watercolor painting",
        stylePrefix    = "watercolor painting, soft flowing colors, artistic washes, " +
                         "transparent layers, traditional media, paper texture,",
        negativePrompt = "${LocalSDConsistency.DEFAULT_NEGATIVE}, " +
                         "photo, realistic, digital art, 3d, sharp edges, cartoon",
        cfgScale       = 6.5f,
        sampler        = "euler"
    ),
    SKETCH(
        id             = "sketch",
        label          = "Sketch",
        emoji          = "✏️",
        description    = "Detailed pencil or ink line art",
        stylePrefix    = "pencil sketch, detailed line art, hatching and cross-hatching, " +
                         "graphite drawing, fine detail, artistic,",
        negativePrompt = "${LocalSDConsistency.DEFAULT_NEGATIVE}, " +
                         "color, photograph, realistic, 3d, painting, digital",
        cfgScale       = 7f,
        sampler        = "euler_a"
    );

    companion object {
        fun fromId(id: String): SDStylePreset = entries.find { it.id == id } ?: DEFAULT
    }
}
