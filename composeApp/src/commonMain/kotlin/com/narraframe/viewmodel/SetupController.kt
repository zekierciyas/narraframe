package com.narraframe.viewmodel

import androidx.compose.runtime.*
import com.narraframe.pipeline.LocalProviders
import com.narraframe.platform.*
import com.narraframe.services.LocalLLMProvider
import com.narraframe.services.NeuralTtsService
import com.narraframe.settings.AppSettings
import com.narraframe.util.AppLogger
import kotlinx.coroutines.*

/** One engine, model or voice the current selection needs on disk. */
data class InstallTask(
    val id:       String,
    val label:    String,
    val detail:   String,
    val sizeMB:   Int,
    val status:   Status       = Status.PENDING,
    val progress: Float?       = null,   // 0–1 when the downloader reports a percentage
    val message:  String?      = null
) {
    enum class Status { PENDING, RUNNING, DONE, FAILED }
}

/**
 * Holds every local-engine choice (text model, image model, voice, library folder)
 * and installs whatever the choice needs. Used by the setup wizard and by Settings.
 * Every setter writes straight to [AppSettings].
 */
class SetupController(
    private val settings: AppSettings,
    private val scope:    CoroutineScope
) {
    // ── Library ───────────────────────────────────────────────────────────
    var libraryPath by mutableStateOf(settings.libraryPath.ifBlank { defaultLibraryPath })
        private set

    fun chooseLibraryFolder() {
        scope.launch {
            pickOutputDirectory("Choose where Narraframe saves your videos")?.let { updateLibraryPath(it) }
        }
    }

    fun updateLibraryPath(path: String) {
        libraryPath = path
        settings.libraryPath = path
    }

    val libraryAvailable: Boolean get() = isPathAvailable(libraryPath)

    // ── Engines & models folder ───────────────────────────────────────────
    var dataDir by mutableStateOf(dataDirectory())
        private set
    var dataDirAvailable by mutableStateOf(isDataDirectoryAvailable())
        private set
    /** Folder picked by the user, waiting for "move existing downloads?" confirmation. */
    var pendingDataDir by mutableStateOf<String?>(null)
        private set
    var movingData by mutableStateOf<String?>(null)
        private set
    var dataDirError by mutableStateOf<String?>(null)
        private set

    fun chooseDataFolder() {
        scope.launch {
            val picked = pickOutputDirectory("Choose where Narraframe stores engines and models") ?: return@launch
            val target = if (picked.trimEnd('/', '\\').endsWith(DATA_FOLDER_NAME)) picked else "$picked/$DATA_FOLDER_NAME"
            requestDataDir(target)
        }
    }

    fun useDefaultDataFolder() = requestDataDir(defaultDataDirectory)

    private fun requestDataDir(target: String) {
        dataDirError = null
        if (target == dataDir) return
        if (dataDirectoryHasContent()) pendingDataDir = target else applyDataDir(target, moveExisting = false)
    }

    fun confirmDataDir(moveExisting: Boolean) {
        val target = pendingDataDir ?: return
        pendingDataDir = null
        applyDataDir(target, moveExisting)
    }

    fun dismissDataDir() { pendingDataDir = null }

    private fun applyDataDir(target: String, moveExisting: Boolean) {
        if (installing || movingData != null) return
        scope.launch {
            movingData = if (moveExisting) "Preparing…" else null
            try {
                changeDataDirectory(target, moveExisting) { msg -> movingData = msg }
            } catch (e: Exception) {
                AppLogger.error("Changing the engines folder failed: ${e.stackTraceToString()}")
                dataDirError = e.message ?: "Could not use that folder"
            } finally {
                movingData = null
                dataDir = dataDirectory()
                resetTasks()
                refresh()
            }
        }
    }

    // ── Text model ────────────────────────────────────────────────────────
    /** "offline" (embedded llama.cpp) or "server" (Ollama / LM Studio). */
    var llmMode by mutableStateOf(settings.localLlmMode)
        private set
    var llmModelId by mutableStateOf(settings.localLlmModelId)
        private set
    var llmUrl by mutableStateOf(settings.localLlmUrl)
        private set
    var llmServerModel by mutableStateOf(settings.localLlmModel)
        private set

    fun selectLlmMode(mode: String) { llmMode = mode; settings.localLlmMode = mode; resetTasks(); refresh() }
    fun selectLlmModel(id: String) { llmModelId = id; settings.localLlmModelId = id; resetTasks(); refresh() }
    fun updateLlmUrl(url: String) { llmUrl = url; settings.localLlmUrl = url; serverTest = null }
    fun updateLlmServerModel(m: String) { llmServerModel = m; settings.localLlmModel = m; serverTest = null }

    fun browseLlmModel() {
        scope.launch { pickFile("Choose a GGUF text model", listOf("gguf"))?.let { selectLlmModel(it) } }
    }

    sealed class ServerTest {
        data object Running : ServerTest()
        data class Ok(val sample: String) : ServerTest()
        data class Failed(val message: String) : ServerTest()
    }
    var serverTest by mutableStateOf<ServerTest?>(null)
        private set

    fun testServer() {
        serverTest = ServerTest.Running
        scope.launch {
            serverTest = try {
                val reply = withContext(Dispatchers.IO) {
                    LocalLLMProvider(llmUrl, llmServerModel).generateText("Reply with the single word: ready")
                }
                ServerTest.Ok(reply.trim().take(40))
            } catch (e: Exception) {
                ServerTest.Failed(e.message ?: "Could not reach the server")
            }
        }
    }

    // ── Image model ───────────────────────────────────────────────────────
    var sdModelId by mutableStateOf(settings.localSDModelId)
        private set
    var sdPreset by mutableStateOf(settings.localSDPreset)
        private set

    fun selectSdModel(id: String) { sdModelId = id; settings.localSDModelId = id; stopImageEngine(); resetTasks(); refresh() }
    fun selectSdPreset(id: String) { sdPreset = id; settings.localSDPreset = id }

    // ── Compute & benchmark ───────────────────────────────────────────────
    /** "auto" | "gpu" | "cpu". Switching may need a different engine build (shown as an install). */
    var computeMode by mutableStateOf(settings.computeMode)
        private set

    fun selectComputeMode(mode: String) {
        computeMode = mode
        settings.computeMode = mode
        stopImageEngine()
        resetTasks()
        refresh()
    }

    /** Identifies what a measured speed belongs to. */
    val benchKey: String get() = "$sdModelId|$sdPreset|${activeAccelerator()}"

    /** Seconds per image measured on this machine for the current model, preset and backend. */
    val measuredSecsPerImage: Double? get() {
        sdModelId; sdPreset; computeMode; benchResult
        return settings.benchSecsPerImage.takeIf { it > 0 && settings.benchKey == benchKey }
    }

    sealed class Bench {
        data object Running : Bench()
        data class Done(val seconds: Double, val backend: String) : Bench()
        data class Failed(val message: String) : Bench()
    }
    var benchResult by mutableStateOf<Bench?>(null)
        private set

    /** Renders one test image twice (the first includes model loading) and stores the speed. */
    fun runBenchmark() {
        if (benchResult == Bench.Running || !imageModelInstalled) return
        benchResult = Bench.Running
        scope.launch {
            benchResult = try {
                val sd = LocalProviders.imageProvider(settings)
                val prompt = "a lighthouse on a cliff at sunset, dramatic sky, cinematic"
                withContext(Dispatchers.IO) { sd.generateImage(prompt) }     // warm-up and model load
                val t = System.currentTimeMillis()
                withContext(Dispatchers.IO) { sd.generateImage(prompt) }
                val secs = (System.currentTimeMillis() - t) / 1000.0
                settings.benchSecsPerImage = secs
                settings.benchKey = benchKey
                Bench.Done(secs, activeAccelerator())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("Benchmark failed: ${e.stackTraceToString()}")
                Bench.Failed(e.message ?: "Benchmark failed")
            }
        }
    }

    fun browseSdModel() {
        scope.launch { pickFile("Choose a GGUF Stable Diffusion model", listOf("gguf"))?.let { selectSdModel(it) } }
    }

    // ── Voice ─────────────────────────────────────────────────────────────
    /** "kokoro" | "qwen3" | "piper" | "os" — the engine card selected in the UI. */
    val voiceEngine: String get() = when (ttsProvider) {
        "piper" -> "piper"
        "local" -> "os"
        else    -> if (neuralEngine == "kokoro") "kokoro" else "qwen3"
    }

    var ttsProvider by mutableStateOf(settings.ttsProvider)
        private set
    var neuralEngine by mutableStateOf(settings.neuralTtsEngine)
        private set
    var kokoroVoice by mutableStateOf(settings.kokoroVoice)
        private set
    var qwen3Voice by mutableStateOf(settings.qwen3Voice)
        private set
    var voiceDesignPrompt by mutableStateOf(settings.voiceDesignPrompt)
        private set
    var piperVoiceId by mutableStateOf(settings.piperVoiceId)
        private set
    var osVoice by mutableStateOf(settings.localTtsVoice)
        private set
    var systemVoices by mutableStateOf<List<String>>(emptyList())
        private set

    /** Speed of the selected engine (neural engines and Piper/OS keep separate values). */
    val voiceSpeed: Double get() = if (ttsProvider == "neural") settings.neuralTtsSpeed else settings.localTtsSpeed
    var voiceSpeedState by mutableStateOf(voiceSpeed)
        private set

    fun selectVoiceEngine(engine: String) {
        when (engine) {
            "kokoro" -> { applyTtsProvider("neural"); selectNeuralEngine("kokoro") }
            "qwen3"  -> { applyTtsProvider("neural"); if (!neuralEngine.startsWith("qwen3")) selectNeuralEngine("qwen3_06b") }
            "piper"  -> applyTtsProvider("piper")
            "os"     -> applyTtsProvider("local")
        }
        voiceSpeedState = voiceSpeed
        resetTasks()
        refresh()
    }

    fun selectNeuralEngine(engine: String) { neuralEngine = engine; settings.neuralTtsEngine = engine; resetTasks(); refresh() }
    fun selectKokoroVoice(v: String) { kokoroVoice = v; settings.kokoroVoice = v }
    fun selectQwen3Voice(v: String) { qwen3Voice = v; settings.qwen3Voice = v }
    fun updateVoiceDesignPrompt(p: String) { voiceDesignPrompt = p; settings.voiceDesignPrompt = p }
    fun selectPiperVoice(id: String) { piperVoiceId = id; settings.piperVoiceId = id; resetTasks(); refresh() }
    fun selectOsVoice(v: String) { osVoice = v; settings.localTtsVoice = v }

    fun updateVoiceSpeed(speed: Double) {
        voiceSpeedState = speed
        if (ttsProvider == "neural") settings.neuralTtsSpeed = speed else settings.localTtsSpeed = speed
    }

    fun browsePiperVoice() {
        scope.launch {
            val path = pickFile("Choose a Piper voice (.onnx with its .onnx.json next to it)", listOf("onnx"))
                ?: return@launch
            if (getLocalPiperVoicePath(path) == null) {
                previewState = PreviewState.Failed("${path.substringAfterLast('/')}.json was not found next to the voice file.")
                return@launch
            }
            selectPiperVoice(path)
        }
    }

    private fun applyTtsProvider(p: String) { ttsProvider = p; settings.ttsProvider = p }

    val voiceLabel: String get() {
        // Read the observable fields so the label recomposes on change
        ttsProvider; neuralEngine; kokoroVoice; qwen3Voice; piperVoiceId; osVoice
        return LocalProviders.voiceLabel(settings)
    }

    // ── Voice preview ─────────────────────────────────────────────────────
    sealed class PreviewState {
        data object Idle : PreviewState()
        data object Generating : PreviewState()
        data object Playing : PreviewState()
        data class Failed(val message: String) : PreviewState()
    }
    var previewState by mutableStateOf<PreviewState>(PreviewState.Idle)
        private set
    private var previewJob: Job? = null

    fun previewVoice(text: String = PREVIEW_TEXT) {
        if (previewState == PreviewState.Generating || previewState == PreviewState.Playing) {
            stopPreview(); return
        }
        if (!voiceInstalled) {
            previewState = PreviewState.Failed("Install this voice first to hear a preview.")
            return
        }
        previewJob = scope.launch {
            try {
                previewState = PreviewState.Generating
                val bytes = withContext(Dispatchers.IO) { LocalProviders.tts(settings).generateSpeech(text) }
                previewState = PreviewState.Playing
                playAudio(bytes)
                previewState = PreviewState.Idle
            } catch (e: CancellationException) {
                previewState = PreviewState.Idle
            } catch (e: Exception) {
                AppLogger.error("Voice preview failed: ${e.message}")
                previewState = PreviewState.Failed(e.message ?: "Preview failed")
            }
        }
    }

    fun stopPreview() {
        stopAudio()
        previewJob?.cancel()
        previewState = PreviewState.Idle
    }

    // ── Installed state ───────────────────────────────────────────────────
    var llamaBinReady   by mutableStateOf(false); private set
    var llmModelReady   by mutableStateOf(false); private set
    var sdBinReady      by mutableStateOf(false); private set
    var sdModelReady    by mutableStateOf(false); private set
    var piperBinReady   by mutableStateOf(false); private set
    var piperVoiceReady by mutableStateOf(false); private set
    var neuralRuntimeReady by mutableStateOf(false); private set
    var neuralModelReady   by mutableStateOf<Map<String, Boolean>>(emptyMap()); private set
    /** False until the first background neural check finishes, so the UI does not flash "missing". */
    var neuralChecked      by mutableStateOf(false); private set

    val textModelInstalled: Boolean get() = llmMode == "server" || (llamaBinReady && llmModelReady)
    val imageModelInstalled: Boolean get() = sdBinReady && sdModelReady
    val voiceInstalled: Boolean get() = when (ttsProvider) {
        "piper" -> piperBinReady && piperVoiceReady
        "local" -> true
        else    -> neuralRuntimeReady && neuralModelReady[neuralEngine] == true
    }
    /** True when everything the current selection needs is on disk. */
    val everythingInstalled: Boolean get() =
        dataDirAvailable && textModelInstalled && imageModelInstalled && voiceInstalled

    /** Free space on the drive of the engines & models folder, in MB. */
    val freeSpaceMB: Int get() { dataDir; return (freeSpaceBytes(dataDir) / 1_048_576).toInt() }

    fun isLlmModelCached(id: String) = getLocalLLMModelPath(id) != null
    fun isSdModelCached(id: String) = getLocalSDModelPath(id) != null
    fun isPiperVoiceCached(id: String) = getLocalPiperVoicePath(id) != null
    fun isNeuralModelCached(engine: String) = neuralRuntimeReady && neuralModelReady[engine] == true

    private var refreshJob: Job? = null

    /** Re-checks what is on disk. Cheap checks run immediately, neural checks in the background. */
    fun refresh() {
        dataDirAvailable = isDataDirectoryAvailable()
        // A failing check (e.g. an unreadable drive) must never crash the UI thread
        fun check(block: () -> Any?): Boolean = runCatching { block() != null }
            .onFailure { AppLogger.warning("Install check failed: ${it.message}") }
            .getOrDefault(false)
        llamaBinReady   = check { getLocalLLMBinaryPath() }
        llmModelReady   = check { getLocalLLMModelPath(llmModelId) }
        sdBinReady      = check { getLocalSDBinaryPath() }
        sdModelReady    = check { getLocalSDModelPath(sdModelId) }
        piperBinReady   = check { getLocalPiperBinaryPath() }
        piperVoiceReady = check { getLocalPiperVoicePath(piperVoiceId) }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val runtime = withContext(Dispatchers.IO) { runCatching { isNeuralTtsRuntimeInstalled() }.getOrDefault(false) }
            val models = withContext(Dispatchers.IO) {
                NeuralTtsService.engines.associate { e ->
                    e.id to runCatching { isNeuralTtsModelInstalled(e.id) }.getOrDefault(false)
                }
            }
            neuralRuntimeReady = runtime
            neuralModelReady   = models
            neuralChecked      = true
        }
        if (systemVoices.isEmpty() && ttsProvider == "local") {
            scope.launch { systemVoices = listSystemVoices() }
        }
    }

    // ── Install queue ─────────────────────────────────────────────────────
    private var taskState by mutableStateOf<Map<String, InstallTask>>(emptyMap())
    var installing by mutableStateOf(false)
        private set
    private var installJob: Job? = null

    /**
     * What the current selection needs: items finished in this session first (shown as done),
     * then everything still missing, with live status for running or failed items.
     */
    val tasks: List<InstallTask> get() {
        val required = requiredTasks()
        val done = taskState.values.filter { t ->
            t.status == InstallTask.Status.DONE && required.none { it.id == t.id }
        }
        return done + required.map { req -> taskState[req.id]?.takeIf { it.status != InstallTask.Status.DONE } ?: req }
    }

    /** Forgets finished/failed rows, e.g. after the user picks a different model. */
    private fun resetTasks() { if (!installing) taskState = emptyMap() }

    val pendingDownloadMB: Int get() = requiredTasks().sumOf { it.sizeMB }

    private fun requiredTasks(): List<InstallTask> = if (!dataDirAvailable) emptyList() else buildList {
        if (llmMode != "server") {
            if (!llamaBinReady) add(InstallTask("llama-bin", "llama.cpp engine (${activeAccelerator()})", "Runs the text model", engineSizeMB(llm = true)))
            if (!llmModelReady && !llmModelId.endsWith(".gguf")) {
                val m = availableLLMModels.find { it.id == llmModelId }
                add(InstallTask("llm-model", m?.name ?: llmModelId, "Text model · writes the image prompts", m?.sizeMB ?: 0))
            }
        }
        if (!sdBinReady) add(InstallTask("sd-bin", "stable-diffusion.cpp engine (${activeAccelerator()})", "Runs the image model", engineSizeMB(llm = false)))
        if (!sdModelReady && !sdModelId.endsWith(".gguf")) {
            val m = availableSDModels.find { it.id == sdModelId }
            add(InstallTask("sd-model", m?.name ?: sdModelId, "Image model", m?.sizeMB ?: 0))
        }
        when (ttsProvider) {
            "piper" -> if (neuralChecked) {
                if (!neuralRuntimeReady) add(InstallTask("py-runtime", "Voice runtime", "Portable Python + ONNX Runtime", 250))
                if (!piperBinReady) add(InstallTask("piper-bin", "Piper engine", "piper-tts with built-in espeak-ng", 30))
                if (!piperVoiceReady && !piperVoiceId.endsWith(".onnx")) {
                    val v = availablePiperVoices.find { it.id == piperVoiceId }
                    add(InstallTask("piper-voice", v?.name ?: piperVoiceId, "Piper voice", v?.sizeMB ?: 63))
                }
            }
            "neural" -> if (neuralChecked) {
                if (!neuralRuntimeReady) add(InstallTask("py-runtime", "Voice runtime", "Portable Python + ONNX Runtime", 250))
                if (neuralModelReady[neuralEngine] != true) {
                    val e = NeuralTtsService.engines.find { it.id == neuralEngine }
                    val extras = if (neuralEngine.startsWith("qwen3")) 2000 else 0
                    add(InstallTask("neural-model", e?.displayName ?: neuralEngine, "Voice model", neuralSizeMB(neuralEngine) + extras))
                }
            }
        }
    }

    fun installAll() {
        if (installing || !dataDirAvailable || movingData != null) return
        installing = true
        installJob = scope.launch {
            try {
                // A failed item does not block the rest; it keeps its Retry button
                for (task in requiredTasks()) runTask(task)
            } finally {
                installing = false
                refresh()
            }
        }
    }

    /** Retries a single failed item. */
    fun retry(id: String) {
        if (installing) return
        val task = requiredTasks().find { it.id == id } ?: return
        installing = true
        installJob = scope.launch {
            try { runTask(task) } finally { installing = false; refresh() }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    private suspend fun runTask(task: InstallTask): Boolean {
        fun update(t: InstallTask) { taskState = taskState + (t.id to t) }
        var current = task.copy(status = InstallTask.Status.RUNNING, message = "Starting…")
        update(current)
        val onProgress: (String) -> Unit = { msg ->
            val pct = msg.substringBefore('%').trim().toIntOrNull()
            current = current.copy(message = msg, progress = pct?.div(100f) ?: current.progress)
            update(current)
        }
        return try {
            withContext(Dispatchers.IO) {
                when (task.id) {
                    "llama-bin"    -> downloadLLMBinary(onProgress)
                    "llm-model"    -> downloadLLMModel(llmModelId, onProgress)
                    "sd-bin"       -> downloadSdBinary(onProgress)
                    "sd-model"     -> downloadSDModel(sdModelId, onProgress)
                    "piper-bin"    -> downloadPiperBinary(onProgress)
                    "piper-voice"  -> downloadPiperVoice(piperVoiceId, onProgress)
                    "py-runtime"   -> installNeuralTtsRuntime(onProgress)
                    "neural-model" -> installNeuralTtsModel(neuralEngine, onProgress)
                }
            }
            update(current.copy(status = InstallTask.Status.DONE, progress = 1f, message = "Installed"))
            refresh()
            true
        } catch (e: CancellationException) {
            update(current.copy(status = InstallTask.Status.FAILED, message = "Paused — press Retry to continue"))
            throw e
        } catch (e: Exception) {
            AppLogger.error("Install of ${task.label} failed: ${e.stackTraceToString()}")
            val reason = e.message ?: "Unknown error"
            val hint = when {
                "401" in reason || "403" in reason -> "Access denied. Download the file yourself and use “Use my own file”."
                "404" in reason -> "File not found (404). The download link may have moved."
                else -> reason
            }
            update(current.copy(status = InstallTask.Status.FAILED, message = hint))
            false
        }
    }

    init { refresh() }

    companion object {
        const val DATA_FOLDER_NAME = "Narraframe Data"
        const val PREVIEW_TEXT = "Every story deserves a voice. This is how your narration will sound."

        /** Rough download size of an engine build; CUDA builds ship the CUDA runtime too. */
        fun engineSizeMB(llm: Boolean): Int = when (activeAccelerator()) {
            "CUDA"   -> if (llm) 650 else 600
            "Vulkan" -> if (llm) 35 else 60
            else     -> if (llm) 20 else 90
        }

        fun neuralSizeMB(engine: String): Int = when (engine) {
            "kokoro"    -> 110
            "qwen3_06b" -> 1200
            else        -> 4000
        }
    }
}
