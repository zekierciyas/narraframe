package com.narraframe.models

/**
 * Sampling family of a Stable Diffusion model. Distilled models (LCM, Turbo) need far
 * fewer steps and a low CFG scale, so they override the preset's steps and the style's
 * CFG / sampler.
 */
enum class SdModelKind(val label: String) {
    STANDARD("Standard"), LCM("LCM"), TURBO("Turbo");

    /** Steps for [preset]: distilled models keep their 4–8 (LCM) or 1–4 (Turbo) step range. */
    fun steps(preset: LocalSDPreset): Int = when (this) {
        STANDARD -> preset.steps
        LCM      -> when (preset) { LocalSDPreset.DRAFT -> 4; LocalSDPreset.BALANCED -> 6; LocalSDPreset.QUALITY -> 8 }
        TURBO    -> when (preset) { LocalSDPreset.DRAFT -> 1; LocalSDPreset.BALANCED -> 2; LocalSDPreset.QUALITY -> 4 }
    }

    /** CFG scale to use, or null to keep the style preset's value. */
    val cfgScale: Float? get() = when (this) { STANDARD -> null; LCM -> 1.5f; TURBO -> 1.0f }

    /** Sampler to use, or null to keep the style preset's value. */
    val sampler: String? get() = when (this) { STANDARD -> null; LCM -> "lcm"; TURBO -> "euler_a" }

    companion object {
        /** Detects the family from a curated id or a custom file name. */
        fun of(modelId: String): SdModelKind {
            val name = modelId.substringAfterLast('/').substringAfterLast('\\').lowercase()
            return when {
                "turbo" in name -> TURBO
                "lcm" in name   -> LCM
                else            -> STANDARD
            }
        }
    }
}
