package com.narraframe.services

import com.narraframe.platform.generatePiperTts
import com.narraframe.platform.generatePiperTtsParts
import com.narraframe.platform.getLocalPiperBinaryPath
import com.narraframe.platform.getLocalPiperVoicePath
import com.narraframe.platform.tempDirectory
import com.narraframe.settings.AppSettings
import com.narraframe.util.AppLogger

/**
 * Neural offline TTS using the Piper TTS engine with ONNX voice models.
 * Requires the Piper binary and a downloaded .onnx voice model.
 *
 * [AppSettings.piperVoiceId] is a curated voice id (e.g. "en_US-amy-medium") or a path to a custom .onnx file.
 */
class PiperTtsService(private val settings: AppSettings) : TtsService {

    override suspend fun generateSpeech(text: String): ByteArray {
        val piperBin = getLocalPiperBinaryPath()
            ?: throw Exception(
                "The Piper engine is not installed. Open Settings → Voice."
            )

        val selectedVoice = settings.piperVoiceId
        val modelPath = getLocalPiperVoicePath(selectedVoice)
            ?: throw Exception(
                "Piper voice '$selectedVoice' is not installed. Open Settings → Voice."
            )

        AppLogger.info("🎤 Piper TTS: voice=${selectedVoice}, speed=${settings.localTtsSpeed}x")

        return generatePiperTts(
            text      = text,
            modelPath = modelPath,
            piperBin  = piperBin,
            tempDir   = tempDirectory,
            speed     = settings.localTtsSpeed
        )
    }

    override suspend fun generateSpeechParts(texts: List<String>): List<ByteArray> {
        val piperBin = getLocalPiperBinaryPath()
            ?: throw Exception("The Piper engine is not installed. Open Settings → Voice.")
        val modelPath = getLocalPiperVoicePath(settings.piperVoiceId)
            ?: throw Exception("Piper voice '${settings.piperVoiceId}' is not installed. Open Settings → Voice.")
        return generatePiperTtsParts(texts, modelPath, piperBin, tempDirectory, settings.localTtsSpeed)
    }
}
