package com.narraframe.viewmodel

import androidx.compose.runtime.*
import com.narraframe.library.Library
import com.narraframe.models.*
import com.narraframe.pipeline.LocalProviders
import com.narraframe.pipeline.PipelineCallbacks
import com.narraframe.pipeline.PipelineCheckpoint
import com.narraframe.pipeline.StoryGenerationPipeline
import com.narraframe.platform.*
import com.narraframe.settings.AppSettings
import com.narraframe.util.AppLogger
import kotlinx.coroutines.*
import java.io.File

// ── App initialisation state ───────────────────────────────────────────────

sealed class AppInitState {
    data class Loading(val message: String = "Initializing…") : AppInitState()
    data object Ready : AppInitState()
    data class Failed(val reason: String, val cause: Throwable? = null) : AppInitState()
}

enum class Screen { SETUP, STUDIO, LIBRARY, SETTINGS }

/** A generation that failed or was interrupted and can continue where it stopped. */
data class ResumeInfo(
    val generation: Generation,
    val failedStep: String,
    val errorMessage: String
)

class StudioViewModel {

    val settings = AppSettings()

    // ── Coroutine scope ────────────────────────────────────────────────────
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.error("Uncaught coroutine exception: ${throwable.message}\n${throwable.stackTraceToString()}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + exceptionHandler)
    private var generationJob: Job? = null

    /** Engine, model and voice choices plus their installs. */
    val setup = SetupController(settings, scope)

    // ── Navigation ─────────────────────────────────────────────────────────
    var initState: AppInitState by mutableStateOf(AppInitState.Loading())
        private set
    var screen by mutableStateOf(
        if (!settings.setupCompleted || forceSetupRequested) Screen.SETUP else Screen.STUDIO
    )
        private set
    var showLogs by mutableStateOf(false)

    fun navigate(to: Screen) {
        if (to == Screen.LIBRARY) refreshLibrary()
        if (to == Screen.SETTINGS || to == Screen.SETUP) setup.refresh()
        screen = to
    }

    fun finishSetup() {
        settings.setupCompleted = true
        settings.libraryPath    = setup.libraryPath
        refreshLibrary()
        screen = Screen.STUDIO
    }

    // ── Composer inputs ────────────────────────────────────────────────────
    var script by mutableStateOf("")
    var stylePresetId by mutableStateOf(settings.localSDStylePresetId)
        private set
    var aspectRatio by mutableStateOf(settings.imageAspectRatio)
        private set

    fun selectStyle(preset: SDStylePreset) {
        stylePresetId = preset.id
        settings.localSDStylePresetId = preset.id
        settings.localSDStylePrefix   = preset.stylePrefix
        settings.localSDNegPrompt     = preset.negativePrompt
        settings.localSDCfgScale      = preset.cfgScale
        settings.localSDSampler       = preset.sampler
    }

    fun selectAspectRatio(ratio: ImageAspectRatio) {
        aspectRatio = ratio.id
        settings.imageAspectRatio = ratio.id
    }

    // ── Video settings (Settings → Video) ──────────────────────────────────
    var imageInterval by mutableStateOf(settings.imageInterval)
        private set
    var crossfade by mutableStateOf(settings.crossfadeDuration)
        private set
    var transitionType by mutableStateOf(settings.transitionType)
        private set
    var zoomAmount by mutableStateOf(settings.zoomAmount)
        private set
    var videoResolution by mutableStateOf(settings.videoResolution)
        private set

    fun updateImageInterval(v: Double) { imageInterval = v; settings.imageInterval = v }
    fun updateCrossfade(v: Double) { crossfade = v; settings.crossfadeDuration = v }
    fun updateTransition(v: String) { transitionType = v; settings.transitionType = v }
    fun updateZoom(v: Double) { zoomAmount = v; settings.zoomAmount = v }
    fun updateResolution(v: String) { videoResolution = v; settings.videoResolution = v }

    // ── Estimates (150 words per minute) ───────────────────────────────────
    val wordCount: Int get() = script.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    private val estimatedSeconds: Double get() = wordCount / 150.0 * 60.0

    /** Same sentence-based split the pipeline uses. */
    val estimatedSceneCount: Int get() =
        if (wordCount == 0) 0 else com.narraframe.pipeline.ScriptScenes.split(script, imageInterval).size

    val estimatedDuration: String get() = formatDuration(estimatedSeconds)

    /** Render time for the images: measured on this machine when possible, else a rough guess. */
    val estimatedRenderTime: String get() {
        val perImage = setup.measuredSecsPerImage
            ?: (LocalSDPreset.fromId(setup.sdPreset).secsPerImage *
                SdModelKind.of(setup.sdModelId).steps(LocalSDPreset.fromId(setup.sdPreset)).toDouble() /
                LocalSDPreset.fromId(setup.sdPreset).steps)
        return LocalSDPreset.formatSecs((perImage * estimatedSceneCount).toInt())
    }

    // ── Generation ─────────────────────────────────────────────────────────
    var state by mutableStateOf(StudioState())
        private set

    /** The generation shown on the storyboard: the running one, or one opened from the library. */
    var current by mutableStateOf<Generation?>(null)
        private set

    var resumeInfo by mutableStateOf<ResumeInfo?>(null)
        private set

    val canGenerate: Boolean get() =
        !state.isGenerating && script.isNotBlank() && setup.everythingInstalled && setup.libraryAvailable

    /** Why Generate is disabled, or null when it is enabled. */
    val generateBlocker: String? get() = when {
        state.isGenerating           -> null
        script.isBlank()             -> "Write or paste a script to start."
        !setup.dataDirAvailable      -> "The drive with your engines and models (${setup.dataDir}) is not connected."
        !setup.libraryAvailable      -> "The library folder (${setup.libraryPath}) is not available. Connect its drive or pick another folder in Settings → Storage."
        !setup.textModelInstalled    -> "The text model is not installed. Open Settings → Models."
        !setup.imageModelInstalled   -> "The image model is not installed. Open Settings → Models."
        !setup.voiceInstalled        -> "The voice is not installed. Open Settings → Voice."
        else                         -> null
    }

    fun startGeneration() {
        if (!canGenerate) return
        val meta = GenerationMeta(
            title       = Library.titleFor(script),
            script      = script,
            createdAt   = System.currentTimeMillis(),
            voice       = setup.voiceLabel,
            style       = SDStylePreset.fromId(stylePresetId).label,
            aspectRatio = aspectRatio
        )
        val generation = try {
            Library.create(libraryRoot(), meta)
        } catch (e: Exception) {
            AppLogger.error("Could not create the generation folder: ${e.message}")
            state = StudioState(currentStep = GenerationStep.FAILED, errorMessage = "Could not create the output folder: ${e.message}")
            return
        }
        run(generation, script)
    }

    fun resumeGeneration() {
        val info = resumeInfo ?: return
        resumeInfo = null
        script = info.generation.meta.script
        run(info.generation, info.generation.meta.script)
    }

    fun dismissResumeInfo() { resumeInfo = null }

    /** Retries the generation on screen from the step that failed. */
    fun retryCurrent() {
        val gen = current ?: return
        if (state.isGenerating) return
        run(gen, gen.meta.script)
    }

    private fun run(generation: Generation, text: String) {
        current = generation
        state = StudioState(isGenerating = true, outputDirectory = generation.path)
        val callbacks = PipelineCallbacks(
            onStepChange  = { step -> scope.launch { state = state.copy(currentStep = step) } },
            onProgress    = { sp, op -> scope.launch { state = state.copy(stepProgress = sp, overallProgress = op) } },
            onLog         = { entry -> scope.launch { state = state.copy(logs = state.logs + entry) } },
            onStateUpdate = { newState -> scope.launch { state = newState.copy(logs = state.logs) } }
        )
        generationJob = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    StoryGenerationPipeline(settings).execute(
                        prompt          = text,
                        imageInterval   = imageInterval,
                        outputDirectory = generation.path,
                        callbacks       = callbacks
                    )
                }
            } catch (e: CancellationException) {
                saveMeta(generation, "cancelled", state)
                throw e
            }
            saveMeta(generation, if (result.isComplete) "completed" else "failed", result)
        }
    }

    private fun saveMeta(generation: Generation, status: String, result: StudioState) {
        val updated = generation.copy(meta = generation.meta.copy(
            status      = status,
            durationSec = result.audioDurationSec.takeIf { it > 0 } ?: generation.meta.durationSec,
            sceneCount  = result.scenePrompts.size.takeIf { it > 0 } ?: generation.meta.sceneCount,
            timings     = generation.meta.timings + result.timings,
            backend     = activeAccelerator()
        ))
        // Real render speed makes the next estimate accurate
        result.timings["SECONDS_PER_IMAGE"]?.let { secs ->
            settings.benchSecsPerImage = secs
            settings.benchKey = setup.benchKey
        }
        runCatching { Library.save(updated) }
        if (current?.path == generation.path) current = updated
        refreshLibrary()
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        state = state.copy(
            currentStep  = GenerationStep.IDLE,
            isGenerating = false,
            logs         = state.logs + LogEntry(level = LogEntry.Level.WARNING, message = "Cancelled by user")
        )
    }

    /** Clears the storyboard so a new script can be written. */
    fun newVideo() {
        if (state.isGenerating) return
        current = null
        state = StudioState()
        script = ""
    }

    /** Narration and visuals run in parallel, so several steps can be in progress at once. */
    fun stepStatus(step: GenerationStep): StepStatus = when {
        state.isComplete                -> StepStatus.COMPLETED
        step in state.stepErrors        -> StepStatus.FAILED
        step in state.doneSteps         -> StepStatus.COMPLETED
        step in state.activeSteps && state.isGenerating -> StepStatus.IN_PROGRESS
        else                            -> StepStatus.PENDING
    }

    // ── Result actions ─────────────────────────────────────────────────────
    fun playVideo(gen: Generation? = current) {
        gen?.videoPath?.takeIf { File(it).exists() }?.let { openWithSystem(it) }
    }

    var narrationPlaying by mutableStateOf(false)
        private set
    private var narrationJob: Job? = null

    /** Plays or stops the narration of the generation on screen. */
    fun toggleNarration() {
        if (narrationPlaying) { stopAudio(); narrationJob?.cancel(); narrationPlaying = false; return }
        val path = state.narrationPath ?: return
        narrationJob = scope.launch {
            narrationPlaying = true
            try {
                val bytes = withContext(Dispatchers.IO) { readLocalFileBytes(path) } ?: return@launch
                playAudio(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("Narration playback failed: ${e.message}")
            } finally {
                narrationPlaying = false
            }
        }
    }

    fun revealInFolder(gen: Generation? = current) {
        val g = gen ?: return
        revealInFileManager(g.videoPath.takeIf { File(it).exists() } ?: g.path)
    }

    fun exportVideo(gen: Generation? = current) {
        val g = gen ?: return
        if (!File(g.videoPath).exists()) return
        scope.launch { saveCopyAs(g.videoPath, "${g.meta.title.take(40).replace(Regex("[^A-Za-z0-9 _-]"), "")}.mp4") }
    }

    // ── Library ────────────────────────────────────────────────────────────
    var library by mutableStateOf<List<Generation>>(emptyList())
        private set

    private fun libraryRoot(): String = settings.libraryPath.ifBlank { setup.libraryPath }

    fun refreshLibrary() {
        scope.launch {
            library = withContext(Dispatchers.IO) { Library.list(libraryRoot()) }
        }
    }

    /** Shows a past generation on the storyboard. */
    fun openGeneration(gen: Generation) {
        if (state.isGenerating) return
        current = gen
        script  = gen.meta.script
        scope.launch {
            val restored = withContext(Dispatchers.IO) { restoreState(gen) }
            state = restored
            screen = Screen.STUDIO
        }
    }

    fun deleteGeneration(gen: Generation) {
        if (state.isGenerating && current?.path == gen.path) return
        scope.launch {
            val trashed = withContext(Dispatchers.IO) { moveToTrash(gen.path) }
            if (!trashed) AppLogger.warning("Could not move ${gen.path} to the trash; delete it manually.")
            if (current?.path == gen.path) newVideo()
            refreshLibrary()
        }
    }

    /** Rebuilds the storyboard state from the files of a finished or failed generation. */
    private fun restoreState(gen: Generation): StudioState {
        val prompts = listProjectFiles(gen.path, "prompts")
            .filter { it.startsWith("prompt_") }
            .mapNotNull { readProjectText(gen.path, "prompts", it) }
        val images = listProjectFiles(gen.path, "images")
            .filter { it.startsWith("image_") }
            .associate { name -> (name.removePrefix("image_").substringBefore('.').toInt() - 1) to "${gen.path}/images/$name" }
        val video = gen.videoPath.takeIf { File(it).exists() }
        val cp = PipelineCheckpoint.load(gen.path)
        val failed = cp?.isResumable == true && video == null
        return StudioState(
            currentStep        = when {
                video != null -> GenerationStep.COMPLETE
                failed        -> GenerationStep.FAILED
                else          -> GenerationStep.IDLE
            },
            overallProgress    = if (video != null) 1f else 0f,
            outputDirectory    = gen.path,
            scenePrompts       = prompts,
            sceneImages        = images,
            narrationPath      = gen.narrationPath.takeIf { File(it).exists() },
            generatedVideoPath = video,
            audioDurationSec   = gen.meta.durationSec,
            errorMessage       = if (failed) cp?.errorMessage else null,
            stepErrors         = if (failed && cp != null)
                GenerationStep.entries.find { it.name == cp.failedStep }?.let { mapOf(it to cp.errorMessage) } ?: emptyMap()
            else emptyMap()
        )
    }

    private fun findResumable() {
        scope.launch {
            val candidate = withContext(Dispatchers.IO) {
                Library.list(libraryRoot()).firstOrNull { gen ->
                    gen.meta.status != "completed" && PipelineCheckpoint.load(gen.path)?.let {
                        it.isResumable || it.status == "running"
                    } == true
                }
            } ?: return@launch
            val cp = PipelineCheckpoint.load(candidate.path)
            resumeInfo = ResumeInfo(
                generation   = candidate,
                failedStep   = cp?.failedStep.orEmpty(),
                errorMessage = cp?.errorMessage.orEmpty()
            )
        }
    }

    // ── Init ───────────────────────────────────────────────────────────────
    private fun runInit() {
        initState = AppInitState.Loading("Initializing…")
        scope.launch {
            try {
                initializePlatform { msg -> initState = AppInitState.Loading(msg) }
                initState = AppInitState.Ready
                AppLogger.success("Platform initialization complete")
            } catch (e: Exception) {
                val reason = e.message ?: "Unknown initialization error"
                AppLogger.error("Platform initialization failed: $reason\n${e.stackTraceToString()}")
                initState = AppInitState.Failed(reason, e)
            }
        }
    }

    fun retryInit() = runInit()

    fun clearLogs() { state = state.copy(logs = emptyList()) }

    fun dispose() { scope.cancel() }

    init {
        AppLogger.setHandler { entry -> scope.launch { state = state.copy(logs = state.logs + entry) } }
        runInit()
        if (settings.setupCompleted) {
            refreshLibrary()
            findResumable()
        }
    }

    companion object {
        fun formatDuration(secs: Double): String {
            val s = secs.toInt()
            return when {
                s <= 0  -> "0s"
                s < 60  -> "${s}s"
                else    -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
            }
        }
    }
}

enum class StepStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }
