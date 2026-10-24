package com.narraframe.services

import com.narraframe.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// ── Protocol ───────────────────────────────────────────────────────────────
interface AIProvider {
    /** Generate raw text from a prompt. */
    suspend fun generateText(prompt: String): String

    /** Generate an image from a prompt; returns raw PNG/JPEG bytes. */
    suspend fun generateImage(prompt: String): ByteArray

    /**
     * Generate N cinematic image prompts from a narration script.
     *
     * @param script       The narration / story script text.
     * @param count        Number of prompts to generate.
     * @param baseImageStyle Optional base style directive applied to all prompts during generation
     *                       (e.g. "cinematic photography, 4K ultra-detailed, dramatic lighting").
     *                       When provided it is embedded in the LLM instruction so every generated
     *                       prompt inherits this visual language.
     */
    suspend fun generateImagePrompts(
        scenes:         List<String>,
        baseImageStyle: String = ""
    ): List<String> = defaultGenerateImagePrompts(scenes, baseImageStyle)

    /** Generate a narration script optimised for text-to-speech from user input. */
    suspend fun generateNarrationScript(userPrompt: String): String =
        defaultGenerateNarrationScript(userPrompt)

    // Default implementations delegate to generateText
    private suspend fun defaultGenerateNarrationScript(userPrompt: String): String {
        val prompt = """
            You are a professional scriptwriter for narrated audio content optimised for text-to-speech.
            Write an engaging narration script for: "$userPrompt"

            Requirements:
            - Conversational, warm tone
            - Sentences ≤ 20 words each with natural comma/period pauses
            - 450–750 words total (≈ 3–5 minutes at 150 wpm)
            - No stage directions, markdown, or speaker labels – spoken text only
            - Captivating opening sentence; memorable closing sentence
        """.trimIndent()
        return generateText(prompt)
    }

    private suspend fun defaultGenerateImagePrompts(
        scenes:         List<String>,
        baseImageStyle: String
    ): List<String> {
        val count = scenes.size
        val prompt = imagePromptInstructions(scenes, baseImageStyle)
        val raw = generateText(prompt)
        return parseJsonStringArray(raw, count)
    }
}

// ── Image prompt instructions (shared) ─────────────────────────────────────
/**
 * Instructions that ask a text model for one image prompt per scene, as a JSON array.
 *
 * Every scene's own text is listed, so each picture shows what that part of the narration
 * says. Kept short on purpose: small local models (1–3B) follow a brief request far better
 * than a long rule list, and Stable Diffusion's text encoder only reads ~77 tokens.
 */
internal fun imagePromptInstructions(scenes: List<String>, baseImageStyle: String): String {
    val count = scenes.size
    val style = if (baseImageStyle.isNotBlank()) "\nVisual style for every prompt: $baseImageStyle." else ""
    val list = scenes.mapIndexed { i, text -> "Scene ${i + 1}: $text" }.joinToString("\n")
    return """
Write exactly $count image prompts, one for each scene below, in the same order.
Each prompt shows what its scene describes: one vivid sentence (15–35 words) naming the concrete subject,
setting, lighting and camera angle. Keep recurring characters and places consistent: repeat their look
(age, clothing, colours) in every prompt instead of writing "the same man". No text or captions.$style

$list

Return a JSON array of $count strings.
""".trim()
}

// ── JSON array parser (shared) ─────────────────────────────────────────────
internal fun parseJsonStringArray(raw: String, expectedCount: Int): List<String> {
    val cleaned = raw
        .trimIndent()
        .replace("```json", "")
        .replace("```", "")
        .trim()

    return try {
        val start = cleaned.indexOf('[').takeIf { it >= 0 } ?: 0
        val end   = cleaned.lastIndexOf(']').takeIf { it > start } ?: cleaned.lastIndex
        val arr = Json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonArray
        arr.mapNotNull { el ->
            // Small models sometimes return objects; use their longest text field
            if (el is kotlinx.serialization.json.JsonObject)
                el.values.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                    .maxByOrNull { it.length }
            else el.jsonPrimitive.content
        }.filter { it.isNotBlank() }.take(expectedCount)
    } catch (e: Exception) {
        AppLogger.warning("JSON parse failed, falling back to line split: ${e.message}")
        cleaned
            .lines()
            .map { it.trim().trimStart('"', ',', '[').trimEnd('"', ',', ']').trim() }
            .filter { it.length > 10 }
            .take(expectedCount)
    }
}
