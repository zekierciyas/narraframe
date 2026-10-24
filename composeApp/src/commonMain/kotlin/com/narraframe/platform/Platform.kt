package com.narraframe.platform

// ── Persistent key-value storage ──────────────────────────────────────────

expect fun loadString(key: String, default: String): String
expect fun saveString(key: String, value: String)
expect fun loadDouble(key: String, default: Double): Double
expect fun saveDouble(key: String, value: Double)
expect fun loadLong(key: String, default: Long): Long
expect fun saveLong(key: String, value: Long)

// ── Files & OS integration ────────────────────────────────────────────────

/** Shows a "save as" dialog and copies [sourcePath] there. Returns the chosen path or null. */
expect suspend fun saveCopyAs(sourcePath: String, suggestedName: String): String?

/** Opens [path] with the OS default application (e.g. the video player). */
expect fun openWithSystem(path: String)

/** Reveals [path] in Finder / Explorer / the file manager. */
expect fun revealInFileManager(path: String)

/** Moves [path] to the OS trash. Returns false when the platform does not support it. */
expect fun moveToTrash(path: String): Boolean

/** Absolute paths of the direct sub-directories of [path], newest first. */
expect fun listDirectories(path: String): List<String>

/** Suggested library folder for first launch, e.g. ~/Movies/Narraframe. */
expect val defaultLibraryPath: String

// ── Engines & models folder ────────────────────────────────────────────────

/** Folder holding downloaded engines, models and the Python voice runtime. */
expect fun dataDirectory(): String

/** Default engines & models folder (~/.narraframe). */
expect val defaultDataDirectory: String

/** False when the engines & models folder is on a drive that is not connected. */
expect fun isDataDirectoryAvailable(): Boolean

/** True when the current engines & models folder already holds downloads. */
expect fun dataDirectoryHasContent(): Boolean

/**
 * Switches the engines & models folder to [newRoot]. With [moveExisting], current
 * downloads are moved there; progress strings look like "42%  –  120 MB / 280 MB".
 */
expect suspend fun changeDataDirectory(newRoot: String, moveExisting: Boolean, onProgress: (String) -> Unit)

/** True when [path] exists, or could be created because its parent folder exists. */
expect fun isPathAvailable(path: String): Boolean

/** Free bytes on the drive that holds [path] (or its nearest existing parent). */
expect fun freeSpaceBytes(path: String): Long

/** Total size in bytes of the engines & models folder. */
expect suspend fun dataDirectorySizeBytes(): Long

/** True when the app was started with -Dnarraframe.forceSetup=true. */
expect val forceSetupRequested: Boolean

// ── Audio playback ─────────────────────────────────────────────────────────

/** Plays MP3 [audioBytes]; any clip already playing is stopped first. Returns when playback ends. */
expect suspend fun playAudio(audioBytes: ByteArray)

/** Stops the clip started by [playAudio], if any. */
expect fun stopAudio()

/** OS voice names usable with [generateLocalTts]. Empty when the OS cannot list them. */
expect suspend fun listSystemVoices(): List<String>

// ── Audio analysis ─────────────────────────────────────────────────────────

/** Returns duration in seconds for the given audio bytes (MP3/WAV). */
expect suspend fun getAudioDurationSeconds(audioBytes: ByteArray): Double

// ── Video composition ──────────────────────────────────────────────────────

sealed class VideoResult {
    data class Success(
        val handle:        String,            // file path of the MP4
        val previewFrames: List<ByteArray>    // first few JPEG frames for in-app preview
    ) : VideoResult()
    data class Error(val message: String) : VideoResult()
}

/** Joins MP3 [parts] into one MP3 with [gapSeconds] of silence between them. */
expect suspend fun concatAudio(parts: List<ByteArray>, gapSeconds: Double): ByteArray

/**
 * Composes [imageBytesList] + [audioBytes] into a video; image i stays on screen for
 * [sceneDurations]\[i] seconds (scaled so the total matches the narration exactly).
 *
 * H.264 MP4 via FFmpeg, saved to [outputDirectory].
 */
expect suspend fun generateVideo(
    imageBytesList:    List<ByteArray>,
    audioBytes:        ByteArray,
    sceneDurations:    List<Double>,
    crossfadeSeconds:  Double,
    transitionType:    String = "fade",
    zoomAmount:        Double = 1.15,
    outputName:        String,
    outputDirectory:   String,
    width:             Int = 1920,
    height:            Int = 1080,
    onProgress:        (Float) -> Unit
): VideoResult

/** Shows a directory picker dialog. Returns the chosen path, or null if cancelled / not supported. */
expect suspend fun pickOutputDirectory(title: String = "Choose a folder"): String?

/** Shows a file picker filtered by [extensions] (e.g. listOf("gguf")). Returns path or null. */
expect suspend fun pickFile(title: String, extensions: List<String>): String?


/**
 * Generates an image locally with stable-diffusion.cpp (a long-running sd-server, so the
 * model loads once). Returns PNG bytes. [onProgress] gets the sampling progress 0–1.
 * Throws if the engine or model is missing, or if generation stalls.
 *
 * @param width   output image width in pixels (e.g. 512)
 * @param height  output image height in pixels (e.g. 288)
 * @param steps   number of diffusion steps (e.g. 15); higher = better quality but slower
 */
expect suspend fun generateLocalSDImage(
    prompt:         String,
    modelPath:      String,
    sdBinPath:      String,
    outputDir:      String,
    width:          Int    = 512,
    height:         Int    = 288,
    steps:          Int    = 15,
    negativePrompt: String = "",
    stylePrefix:    String = "",
    cfgScale:       Float  = 7f,
    sampler:        String = "euler_a",
    seed:           Long   = -1L,
    onProgress:     (Float) -> Unit = {}
): ByteArray

/** Returns the image engine (sd-server) path if the build for this computer is installed, else null. */
expect fun getLocalSDBinaryPath(): String?

/** Downloads the sd.cpp inference binary, reporting progress via [onProgress]. */
expect suspend fun downloadSdBinary(onProgress: (String) -> Unit)

/** Returns cached GGUF model path for [modelId] if downloaded, else null. */
expect fun getLocalSDModelPath(modelId: String): String?

/** Lightweight model descriptor exposed to commonMain. */
data class SDModelInfo(val id: String, val name: String, val sizeMB: Int, val desc: String, val downloadUrl: String = "")

/** All curated downloadable GGUF image models. */
expect val availableSDModels: List<SDModelInfo>

/** Downloads the GGUF model with [modelId], reporting progress via [onProgress]. */
expect suspend fun downloadSDModel(modelId: String, onProgress: (String) -> Unit)

// ── Local LLM text generation ─────────────────────────────────────────────

/** Returns the llama.cpp binary path if the build for this computer is installed, else null. */
expect fun getLocalLLMBinaryPath(): String?

/** Downloads the llama.cpp CLI binary, reporting progress via [onProgress]. */
expect suspend fun downloadLLMBinary(onProgress: (String) -> Unit)

/** Returns cached GGUF LLM model path for [modelId] if downloaded, else null. */
expect fun getLocalLLMModelPath(modelId: String): String?

/** Lightweight LLM model descriptor exposed to commonMain. */
data class LLMModelInfo(val id: String, val name: String, val sizeMB: Int, val desc: String)

/** All curated downloadable GGUF text models. */
expect val availableLLMModels: List<LLMModelInfo>

/** Downloads the GGUF LLM model with [modelId], reporting progress via [onProgress]. */
expect suspend fun downloadLLMModel(modelId: String, onProgress: (String) -> Unit)

/**
 * Generates text using the llama.cpp CLI binary and a local GGUF model.
 * Runs llama.cpp once and returns the generated text. [jsonSchema] constrains the output.
 */
expect suspend fun generateLocalLLMText(
    prompt:      String,
    modelPath:   String,
    llamaBinPath: String,
    maxTokens:   Int = 1000,
    jsonSchema:  String? = null
): String

// ── Local TTS (OS built-in) ───────────────────────────────────────────────

/** Absolute path to the app's temp directory (used for local TTS scratch files). */
expect val tempDirectory: String

/**
 * Synthesises [text] to MP3 bytes using the OS built-in TTS engine + FFmpeg.
 *
 * macOS  : `say -f text.txt -o out.aiff`  → ffmpeg → MP3
 * Linux  : `espeak-ng -f text.txt -w out.wav` → ffmpeg → MP3
 * Windows: PowerShell System.Speech → WAV → ffmpeg → MP3
 */
expect suspend fun generateLocalTts(text: String, tempDir: String, voice: String = "", speed: Double = 1.0): ByteArray

// ── Piper neural TTS ──────────────────────────────────────────────────────

/** Returns the command that runs Piper (the Python runtime's python) if Piper is installed, else null. */
expect fun getLocalPiperBinaryPath(): String?

/** Installs Piper (piper-tts in the Python runtime), reporting progress via [onProgress]. */
expect suspend fun downloadPiperBinary(onProgress: (String) -> Unit)

/** Lightweight Piper voice descriptor exposed to commonMain. */
data class PiperVoiceInfo(val id: String, val name: String, val sizeMB: Int, val lang: String)

/** All curated downloadable Piper voices. */
expect val availablePiperVoices: List<PiperVoiceInfo>

/**
 * Returns the Piper voice model path (.onnx) for [voiceId] if available, else null.
 * [voiceId] may also be an absolute path to a custom .onnx file with its .onnx.json next to it.
 */
expect fun getLocalPiperVoicePath(voiceId: String): String?

/** Downloads the Piper ONNX voice model for [voiceId], reporting progress via [onProgress]. */
expect suspend fun downloadPiperVoice(voiceId: String, onProgress: (String) -> Unit)

/**
 * Generates speech using Piper and an ONNX voice model.
 * Returns raw MP3 bytes (WAV → FFmpeg → MP3).
 */
expect suspend fun generatePiperTts(
    text:       String,
    modelPath:  String,
    piperBin:   String,
    tempDir:    String,
    speed:      Double = 1.0
): ByteArray

/** Generates one MP3 per text with a single Piper run (the voice loads once). */
expect suspend fun generatePiperTtsParts(
    texts:      List<String>,
    modelPath:  String,
    piperBin:   String,
    tempDir:    String,
    speed:      Double = 1.0
): List<ByteArray>

// ── Neural TTS (Kokoro / Qwen3 via embedded Python sidecar) ───────────────

/** True when the host platform supports the embedded Python neural-TTS sidecar (desktop only). */
expect val isNeuralTtsSupported: Boolean

/** True when the embedded Python runtime + venv is fully installed. */
expect suspend fun isNeuralTtsRuntimeInstalled(): Boolean

/** True when the model weights for [engine] (kokoro / qwen3-0.6b / qwen3-1.7b) are cached locally. */
expect suspend fun isNeuralTtsModelInstalled(engine: String): Boolean

/** Downloads python-build-standalone, creates venv, pip-installs sidecar dependencies. Idempotent. */
expect suspend fun installNeuralTtsRuntime(onProgress: (String) -> Unit)

/** Downloads model weights for [engine]. Idempotent. */
expect suspend fun installNeuralTtsModel(engine: String, onProgress: (String) -> Unit)

/** Synthesises [text] via the local sidecar; returns MP3 bytes. */
expect suspend fun neuralTtsSynthesize(
    engine:              String,
    voice:               String,
    speed:               Double,
    text:                String,
    referenceAudioBytes: ByteArray? = null
): ByteArray

/** Returns "cuda" / "mps" / "cpu" / "unknown" — used to surface a hardware hint in the UI. */
expect suspend fun neuralTtsHardwareHint(): String

/** Reads a local file from disk; returns null if the path is empty or the file cannot be read. */
expect fun readLocalFileBytes(path: String): ByteArray?

// ── Startup initialisation ────────────────────────────────────────────────

/**
 * Called once at app startup (before the main UI is shown).
 * Resolves or downloads the FFmpeg binary and reports progress.
 *
 * @param onProgress  human-readable status string shown in the loading screen
 * @throws Exception  if a required dependency cannot be installed
 */
expect suspend fun initializePlatform(onProgress: (String) -> Unit)

// ── Project file I/O ──────────────────────────────────────────────────────

/** Creates all subdirectories for a project (images, voices, prompts, videos). */
expect fun ensureProjectDirs(projectPath: String)

/** Saves [bytes] to [projectPath]/[subDir]/[fileName]. Returns the full path. */
expect fun saveProjectFile(projectPath: String, subDir: String, fileName: String, bytes: ByteArray): String

/** Saves text [content] to [projectPath]/[subDir]/[fileName]. Returns the full path. */
expect fun saveProjectText(projectPath: String, subDir: String, fileName: String, content: String): String

/** Reads bytes from [projectPath]/[subDir]/[fileName]. Returns null if file does not exist. */
expect fun readProjectFile(projectPath: String, subDir: String, fileName: String): ByteArray?

/** Reads text from [projectPath]/[subDir]/[fileName]. Returns null if file does not exist. */
expect fun readProjectText(projectPath: String, subDir: String, fileName: String): String?

/** Returns sorted list of file names in [projectPath]/[subDir]. Empty if directory absent. */
expect fun listProjectFiles(projectPath: String, subDir: String): List<String>

// ── Platform identity ──────────────────────────────────────────────────────

expect val platformName: String

enum class HostOs { MACOS, LINUX, WINDOWS, OTHER }

/** The host operating system detected at runtime. */
expect val hostOs: HostOs

/** Short hardware description for the setup wizard, e.g. "Apple Silicon · 16 GB RAM". */
expect val hardwareSummary: String

/** GPU found on this machine with its backend, e.g. "Apple M3 · Metal". */
expect val gpuSummary: String

/** Backend the engines use now ("Metal", "CUDA", "Vulkan" or "CPU"), honouring the compute mode. */
expect fun activeAccelerator(): String

/** Stops the image engine server, e.g. after the compute mode or image model changes. */
expect fun stopImageEngine()

/** True on Apple Silicon Macs (Metal acceleration available to the engines). */
expect val isAppleSilicon: Boolean

/** Total physical memory in GB, or 0 when unknown. */
expect val totalMemoryGb: Int
