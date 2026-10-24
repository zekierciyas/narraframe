package com.narraframe.models

/**
 * Consistency settings shared across every image in a local sd.cpp generation run.
 *
 * Applied uniformly to all scene prompts so the resulting images share the same
 * style, quality floor, and visual language — making the final video look cohesive.
 *
 * @param negativePrompt  What to avoid in every image. A good default removes
 *                        common artifacts without constraining creative content.
 * @param stylePrefix     Prepended to every scene prompt (e.g. "cinematic photo, 8k, ").
 *                        Use this to lock in a consistent art direction.
 * @param cfgScale        Classifier-Free Guidance strength (1–15).
 *                        Higher = follows prompt more strictly; lower = more creative/varied.
 * @param sampler         Sampling algorithm name as accepted by sd-cli (--sampling-method).
 * @param seed            RNG seed. -1 means a new random seed per image (max variation).
 *                        A fixed positive value makes all images visually related.
 */
data class LocalSDConsistency(
    val negativePrompt: String = DEFAULT_NEGATIVE,
    val stylePrefix:    String = "",
    val cfgScale:       Float  = 7f,
    val sampler:        String = "euler_a",
    val seed:           Long   = -1L
) {
    companion object {
        const val DEFAULT_NEGATIVE =
            "blurry, low quality, bad anatomy, watermark, text, logo, " +
            "ugly, deformed, distorted, noisy, oversaturated, duplicate"

        /** All sampler options supported by sd-cli (--sampling-method). */
        val SAMPLERS = listOf(
            "euler_a"    to "Euler A  — fast, creative (recommended)",
            "euler"      to "Euler  — fast, consistent",
            "dpm++2m"    to "DPM++ 2M  — high quality",
            "dpm++2mv2"  to "DPM++ 2M v2  — improved quality",
            "heun"       to "Heun  — slow but precise",
            "lcm"        to "LCM  — very fast, fewer steps"
        )
    }
}
