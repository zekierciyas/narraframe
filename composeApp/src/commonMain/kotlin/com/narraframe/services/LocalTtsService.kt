package com.narraframe.services

import com.narraframe.platform.generateLocalTts
import com.narraframe.platform.tempDirectory
import com.narraframe.settings.AppSettings
import com.narraframe.util.AppLogger

/**
 * TTS provider using OS built-in (macOS say, Linux espeak-ng, Windows System.Speech).
 * No API key required. Output converted to MP3 via FFmpeg.
 *
 * Voice and speed come from [AppSettings.localTtsVoice] and [AppSettings.localTtsSpeed].
 * Falls back to system default if custom voice is unavailable.
 */
class LocalTtsService(private val settings: AppSettings = AppSettings()) : TtsService {
    override suspend fun generateSpeech(text: String): ByteArray {
        AppLogger.info("🎤 Local TTS (voice: ${settings.localTtsVoice}, speed: ${settings.localTtsSpeed}x)")
        return generateLocalTts(text, tempDirectory, settings.localTtsVoice, settings.localTtsSpeed)
    }
}
