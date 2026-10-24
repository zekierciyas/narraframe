package com.narraframe.settings

import com.narraframe.models.LocalSDConsistency
import com.narraframe.platform.loadDouble
import com.narraframe.platform.loadLong
import com.narraframe.platform.loadString
import com.narraframe.platform.saveDouble
import com.narraframe.platform.saveLong
import com.narraframe.platform.saveString

private object K {
    const val SETUP_COMPLETED          = "setup_completed"
    const val LIBRARY_PATH             = "library_path"
    const val IMAGE_INTERVAL           = "image_interval"
    const val CROSSFADE_DURATION       = "crossfade_duration"
    const val VIDEO_RESOLUTION         = "video_resolution"
    const val TRANSITION_TYPE          = "transition_type"
    const val ZOOM_AMOUNT              = "zoom_amount"
    const val IMAGE_ASPECT_RATIO       = "image_aspect_ratio"
    const val TTS_PROVIDER             = "tts_provider"
    const val LOCAL_TTS_VOICE          = "local_tts_voice"
    const val LOCAL_TTS_SPEED          = "local_tts_speed"
    const val PIPER_VOICE_ID           = "piper_voice_id"
    const val NEURAL_TTS_ENGINE        = "neural_tts_engine"
    const val KOKORO_VOICE             = "kokoro_voice"
    const val QWEN3_VOICE              = "qwen3_voice"
    const val NEURAL_TTS_SPEED         = "neural_tts_speed"
    const val QWEN3_VOICE_CLONE_PATH   = "qwen3_voice_clone_path"
    const val QWEN3_USE_VOICE_CLONE    = "qwen3_use_voice_clone"
    const val VOICE_DESIGN_PROMPT      = "voice_design_prompt"
    const val LOCAL_SD_MODEL_ID        = "local_sd_model_id"
    const val LOCAL_SD_PRESET          = "local_sd_preset"
    const val LOCAL_SD_NEG_PROMPT      = "local_sd_neg_prompt"
    const val LOCAL_SD_STYLE_PREFIX    = "local_sd_style_prefix"
    const val LOCAL_SD_CFG_SCALE       = "local_sd_cfg_scale"
    const val LOCAL_SD_SAMPLER         = "local_sd_sampler"
    const val LOCAL_SD_SEED            = "local_sd_seed"
    const val LOCAL_SD_STYLE_PRESET_ID = "local_sd_style_preset_id"
    const val LOCAL_LLM_MODE           = "local_llm_mode"
    const val LOCAL_LLM_MODEL_ID       = "local_llm_model_id"
    const val LOCAL_LLM_URL            = "local_llm_url"
    const val LOCAL_LLM_MODEL          = "local_llm_model"
    const val COMPUTE_MODE             = "compute_mode"
    const val BENCH_SECS_PER_IMAGE     = "bench_secs_per_image"
    const val BENCH_KEY                = "bench_key"
}

/**
 * Platform-persisted application settings (JVM Preferences on desktop).
 *
 * Model and voice ids hold either a curated catalogue id (e.g. "sd-1.5-q4")
 * or an absolute path to a user-provided file.
 */
class AppSettings {

    // ── Setup & storage ───────────────────────────────────────────────────
    var setupCompleted: Boolean
        get() = loadString(K.SETUP_COMPLETED, "false").toBoolean()
        set(v) { saveString(K.SETUP_COMPLETED, v.toString()) }

    /** Folder where every generation gets its own sub-folder. */
    var libraryPath: String
        get() = loadString(K.LIBRARY_PATH, "")
        set(v) { saveString(K.LIBRARY_PATH, v) }

    // ── Video ─────────────────────────────────────────────────────────────
    var imageInterval: Double
        get() = loadDouble(K.IMAGE_INTERVAL, 8.0)
        set(v) { saveDouble(K.IMAGE_INTERVAL, v) }

    var crossfadeDuration: Double
        get() = loadDouble(K.CROSSFADE_DURATION, 0.5)
        set(v) { saveDouble(K.CROSSFADE_DURATION, v) }

    var videoResolution: String
        get() = loadString(K.VIDEO_RESOLUTION, "1080p")
        set(v) { saveString(K.VIDEO_RESOLUTION, v) }

    // "none" | "fade" | "zoom_in" | "zoom_out"
    var transitionType: String
        get() = loadString(K.TRANSITION_TYPE, "fade")
        set(v) { saveString(K.TRANSITION_TYPE, v) }

    // Zoom amount for zoom_in / zoom_out (1.05 = 5 %, 1.5 = 50 %)
    var zoomAmount: Double
        get() = loadDouble(K.ZOOM_AMOUNT, 1.15)
        set(v) { saveDouble(K.ZOOM_AMOUNT, v) }

    // "16:9" | "9:16" | "1:1" | "4:3" | "3:4"
    var imageAspectRatio: String
        get() = loadString(K.IMAGE_ASPECT_RATIO, "16:9")
        set(v) { saveString(K.IMAGE_ASPECT_RATIO, v) }

    // ── Voice ─────────────────────────────────────────────────────────────
    // "neural" (Kokoro / Qwen3 sidecar) | "piper" | "local" (OS voices)
    var ttsProvider: String
        get() = loadString(K.TTS_PROVIDER, "neural").let { if (it in TTS_PROVIDERS) it else "neural" }
        set(v) { saveString(K.TTS_PROVIDER, v) }

    /** OS voice name, e.g. "Samantha" on macOS. Empty = system default. */
    var localTtsVoice: String
        get() = loadString(K.LOCAL_TTS_VOICE, "")
        set(v) { saveString(K.LOCAL_TTS_VOICE, v) }

    /** Speed for OS voices and Piper (0.5–2.0). */
    var localTtsSpeed: Double
        get() = loadDouble(K.LOCAL_TTS_SPEED, 1.0)
        set(v) { saveDouble(K.LOCAL_TTS_SPEED, v) }

    /** Piper voice id, or an absolute path to a custom .onnx voice. */
    var piperVoiceId: String
        get() = loadString(K.PIPER_VOICE_ID, "en_US-amy-medium")
        set(v) { saveString(K.PIPER_VOICE_ID, v) }

    // "kokoro" | "qwen3_06b" | "qwen3_17b" | "qwen3_vd"
    var neuralTtsEngine: String
        get() = loadString(K.NEURAL_TTS_ENGINE, "kokoro")
        set(v) { saveString(K.NEURAL_TTS_ENGINE, v) }

    var kokoroVoice: String
        get() = loadString(K.KOKORO_VOICE, "af_heart")
        set(v) { saveString(K.KOKORO_VOICE, v) }

    var qwen3Voice: String
        get() = loadString(K.QWEN3_VOICE, "longwan")
        set(v) { saveString(K.QWEN3_VOICE, v) }

    var neuralTtsSpeed: Double
        get() = loadDouble(K.NEURAL_TTS_SPEED, 1.0)
        set(v) { saveDouble(K.NEURAL_TTS_SPEED, v) }

    var qwen3VoiceClonePath: String
        get() = loadString(K.QWEN3_VOICE_CLONE_PATH, "")
        set(v) { saveString(K.QWEN3_VOICE_CLONE_PATH, v) }

    var qwen3UseVoiceClone: Boolean
        get() = loadString(K.QWEN3_USE_VOICE_CLONE, "false").toBoolean()
        set(v) { saveString(K.QWEN3_USE_VOICE_CLONE, v.toString()) }

    var voiceDesignPrompt: String
        get() = loadString(K.VOICE_DESIGN_PROMPT, "calm, warm narrator with clear diction")
        set(v) { saveString(K.VOICE_DESIGN_PROMPT, v) }

    // ── Image model (stable-diffusion.cpp) ────────────────────────────────
    var localSDModelId: String
        get() = loadString(K.LOCAL_SD_MODEL_ID, "dreamshaper8-lcm-q4")
        set(v) { saveString(K.LOCAL_SD_MODEL_ID, v) }

    // "draft" | "balanced" | "quality"
    var localSDPreset: String
        get() = loadString(K.LOCAL_SD_PRESET, "balanced")
        set(v) { saveString(K.LOCAL_SD_PRESET, v) }

    var localSDNegPrompt: String
        get() = loadString(K.LOCAL_SD_NEG_PROMPT, LocalSDConsistency.DEFAULT_NEGATIVE)
        set(v) { saveString(K.LOCAL_SD_NEG_PROMPT, v) }

    var localSDStylePrefix: String
        get() = loadString(K.LOCAL_SD_STYLE_PREFIX, "")
        set(v) { saveString(K.LOCAL_SD_STYLE_PREFIX, v) }

    var localSDCfgScale: Float
        get() = loadDouble(K.LOCAL_SD_CFG_SCALE, 7.0).toFloat()
        set(v) { saveDouble(K.LOCAL_SD_CFG_SCALE, v.toDouble()) }

    var localSDSampler: String
        get() = loadString(K.LOCAL_SD_SAMPLER, "euler_a")
        set(v) { saveString(K.LOCAL_SD_SAMPLER, v) }

    var localSDSeed: Long
        get() = loadLong(K.LOCAL_SD_SEED, -1L)
        set(v) { saveLong(K.LOCAL_SD_SEED, v) }

    /** Active [com.narraframe.models.SDStylePreset] id. */
    var localSDStylePresetId: String
        get() = loadString(K.LOCAL_SD_STYLE_PRESET_ID, "cinematic")
        set(v) { saveString(K.LOCAL_SD_STYLE_PRESET_ID, v) }

    // ── Text model (writes the image prompts) ─────────────────────────────
    // "offline" = embedded llama.cpp + GGUF model, "server" = Ollama / LM Studio
    var localLlmMode: String
        get() = loadString(K.LOCAL_LLM_MODE, "offline")
        set(v) { saveString(K.LOCAL_LLM_MODE, v) }

    var localLlmModelId: String
        get() = loadString(K.LOCAL_LLM_MODEL_ID, "llama-3.2-1b-q4")
        set(v) { saveString(K.LOCAL_LLM_MODEL_ID, v) }

    var localLlmUrl: String
        get() = loadString(K.LOCAL_LLM_URL, "http://localhost:11434")
        set(v) { saveString(K.LOCAL_LLM_URL, v) }

    var localLlmModel: String
        get() = loadString(K.LOCAL_LLM_MODEL, "llama3.2")
        set(v) { saveString(K.LOCAL_LLM_MODEL, v) }

    // ── Performance ───────────────────────────────────────────────────────
    /** "auto" (GPU when available) | "gpu" | "cpu". Also read by the desktop engine code. */
    var computeMode: String
        get() = loadString(K.COMPUTE_MODE, "auto")
        set(v) { saveString(K.COMPUTE_MODE, v) }

    /** Measured seconds per image for [benchKey] (model|preset|backend), 0 when unknown. */
    var benchSecsPerImage: Double
        get() = loadDouble(K.BENCH_SECS_PER_IMAGE, 0.0)
        set(v) { saveDouble(K.BENCH_SECS_PER_IMAGE, v) }

    var benchKey: String
        get() = loadString(K.BENCH_KEY, "")
        set(v) { saveString(K.BENCH_KEY, v) }

    companion object {
        val TTS_PROVIDERS = setOf("neural", "piper", "local")
    }
}
