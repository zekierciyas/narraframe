package com.narraframe.services

/** Common interface for all local text-to-speech engines. */
interface TtsService {
    /** Synthesises [text] to raw MP3 audio bytes using the voice chosen in settings. */
    suspend fun generateSpeech(text: String): ByteArray

    /** One MP3 per text. Engines that load a model per call override this to batch. */
    suspend fun generateSpeechParts(texts: List<String>): List<ByteArray> = texts.map { generateSpeech(it) }
}
