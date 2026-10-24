package com.narraframe.pipeline

import com.narraframe.models.Generation
import com.narraframe.models.GenerationStep
import com.narraframe.models.GenerationStep.*
import com.narraframe.models.ImageAspectRatio
import com.narraframe.models.LogEntry
import com.narraframe.models.StudioState
import com.narraframe.platform.VideoResult
import com.narraframe.platform.concatAudio
import com.narraframe.platform.ensureProjectDirs
import com.narraframe.platform.generateVideo
import com.narraframe.platform.getAudioDurationSeconds
import com.narraframe.platform.readProjectFile
import com.narraframe.platform.readProjectText
import com.narraframe.platform.saveProjectFile
import com.narraframe.platform.saveProjectText
import com.narraframe.settings.AppSettings
import com.narraframe.util.AppLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.cancellation.CancellationException

data class PipelineCallbacks(
    val onStepChange:  (GenerationStep)  -> Unit,
    val onProgress:    (step: Float, overall: Float) -> Unit,
    val onLog:         (LogEntry)        -> Unit,
    val onStateUpdate: (StudioState)     -> Unit
)

/** A step failed; carries which one so the UI and the checkpoint can point at it. */
private class StepFailure(val step: GenerationStep, cause: Throwable) : Exception(cause.message, cause)

class StoryGenerationPipeline(private val settings: AppSettings) {

    // Share of the overall progress bar per step
    private val weights = mapOf(
        GENERATING_AUDIO to 0.15f, ANALYZING_AUDIO to 0.02f,
        GENERATING_IMAGE_PROMPTS to 0.08f, GENERATING_IMAGES to 0.6f, COMPOSING_VIDEO to 0.15f
    )

    /**
     * Runs script → narration + (image prompts → images) → video inside [outputDirectory].
     *
     * The narration and the visuals run in parallel: the scene count is estimated from the
     * word count, and once the narration exists each scene's on-screen time is set so the
     * video matches the audio exactly. Steps whose output is already on disk are skipped,
     * so a failed or interrupted run resumes where it stopped.
     */
    suspend fun execute(
        prompt:          String,
        imageInterval:   Double,
        outputDirectory: String,
        callbacks:       PipelineCallbacks
    ): StudioState {
        AppLogger.setHandler { callbacks.onLog(it) }

        val lock = Any()
        var state = StudioState(isGenerating = true, outputDirectory = outputDirectory)
        val stepProgress = mutableMapOf<GenerationStep, Float>()

        fun update(change: (StudioState) -> StudioState) {
            val snapshot = synchronized(lock) {
                state = change(state)
                state
            }
            callbacks.onStateUpdate(snapshot)
        }

        fun overall(): Float = synchronized(lock) {
            weights.entries.sumOf { (s, w) ->
                ((if (s in state.doneSteps) 1f else stepProgress[s] ?: 0f) * w).toDouble()
            }.toFloat()
        }

        fun begin(s: GenerationStep) {
            update { it.copy(currentStep = s, activeSteps = it.activeSteps + s) }
            callbacks.onStepChange(s)
        }

        fun progress(s: GenerationStep, p: Float) {
            synchronized(lock) { stepProgress[s] = p.coerceIn(0f, 1f) }
            val o = overall()
            update { it.copy(overallProgress = o, stepProgress = p) }
            callbacks.onProgress(p, o)
        }

        fun done(s: GenerationStep, seconds: Double? = null) {
            update {
                it.copy(
                    activeSteps = it.activeSteps - s,
                    doneSteps   = it.doneSteps + s,
                    timings     = if (seconds != null) it.timings + (s.name to seconds) else it.timings
                )
            }
            progress(s, 1f)
        }

        fun log(msg: String, level: LogEntry.Level = LogEntry.Level.INFO) {
            callbacks.onLog(LogEntry(level = level, step = state.currentStep, message = msg))
        }

        AppLogger.info("🎙️ Voice: ${LocalProviders.voiceLabel(settings)}")

        // ── Checkpoint ────────────────────────────────────────────────────────
        var cp = PipelineCheckpoint(
            script        = prompt,
            imageInterval = imageInterval,
            status        = "running",
            timestamp     = System.currentTimeMillis()
        )
        PipelineCheckpoint.save(outputDirectory, cp)

        fun fail(failedStep: GenerationStep, e: Throwable): StudioState {
            val msg = e.message ?: e::class.simpleName ?: "Unknown error"
            AppLogger.error("Step '${failedStep.displayName}' failed: $msg\n${e.stackTraceToString()}")
            cp = cp.copy(
                status       = "failed",
                failedStep   = failedStep.name,
                errorMessage = "[${failedStep.displayName}] $msg",
                timestamp    = System.currentTimeMillis()
            )
            PipelineCheckpoint.save(outputDirectory, cp)
            update {
                it.copy(
                    currentStep    = FAILED,
                    isGenerating   = false,
                    activeSteps    = emptySet(),
                    errorMessage   = "[${failedStep.displayName}] $msg",
                    stepErrors     = it.stepErrors + (failedStep to msg),
                    renderingScene = null
                )
            }
            log("❌ ${failedStep.displayName} failed: $msg", LogEntry.Level.ERROR)
            return state
        }

        val runStarted = System.currentTimeMillis()
        try {
            ensureProjectDirs(outputDirectory)
            val ai  = try { LocalProviders.textProvider(settings) } catch (e: Exception) { return fail(GENERATING_IMAGE_PROMPTS, e) }
            val sd  = try { LocalProviders.imageProvider(settings) } catch (e: Exception) { return fail(GENERATING_IMAGES, e) }
            val tts = try { LocalProviders.tts(settings) } catch (e: Exception) { return fail(GENERATING_AUDIO, e) }

            val script = prompt
            saveProjectText(outputDirectory, "prompts", "story.txt", script)

            // Split at sentence boundaries: each scene is narrated and illustrated on its own,
            // so the picture on screen always matches the words being spoken
            val scenes = ScriptScenes.split(script, imageInterval)
            scenes.forEachIndexed { i, text -> saveProjectText(outputDirectory, "prompts", "scene_%03d.txt".format(i + 1), text) }
            val plannedCount = scenes.size
            update { it.copy(estimatedImageCount = plannedCount) }
            log("Script: ${ScriptScenes.wordCount(script)} words → $plannedCount scenes")

            val aspectRatio = ImageAspectRatio.fromId(settings.imageAspectRatio)
            val gap = SCENE_GAP_SECONDS

            val result: Result<Pair<Triple<ByteArray, Double, List<Double>>, List<ByteArray>>> = supervisorScope {
                // ── Narration ────────────────────────────────────────────────
                val audio = async {
                    try {
                        begin(GENERATING_AUDIO)
                        val t0 = System.currentTimeMillis()
                        val full = readProjectFile(outputDirectory, "voices", "narration_001.mp3")
                        val sceneAudio = scenes.indices.map { i ->
                            readProjectFile(outputDirectory, "voices", "scene_%03d.mp3".format(i + 1))
                        }
                        val bytes: ByteArray
                        val sceneSecs: List<Double>
                        if (full != null && sceneAudio.all { it != null }) {
                            log("Resuming — narration loaded from disk", LogEntry.Level.SUCCESS)
                            bytes = full
                            sceneSecs = sceneAudio.map { getAudioDurationSeconds(it!!) }
                        } else if (full != null) {
                            // Narration from a run before per-scene audio existed: split by words
                            log("Resuming — narration loaded from disk", LogEntry.Level.SUCCESS)
                            bytes = full
                            val total = getAudioDurationSeconds(full)
                            val words = scenes.map { ScriptScenes.wordCount(it).toDouble() }
                            sceneSecs = words.map { total * it / words.sum() }
                        } else {
                            AppLogger.info("Recording the narration, scene by scene…", GENERATING_AUDIO)
                            // Only scenes without audio on disk are recorded, in one batch
                            val missing = scenes.indices.filter { sceneAudio[it] == null }
                            val recorded = tts.generateSpeechParts(missing.map { scenes[it] })
                            missing.forEachIndexed { k, i ->
                                saveProjectFile(outputDirectory, "voices", "scene_%03d.mp3".format(i + 1), recorded[k])
                            }
                            val parts = scenes.indices.map { i -> sceneAudio[i] ?: recorded[missing.indexOf(i)] }
                            progress(GENERATING_AUDIO, 0.9f)
                            sceneSecs = parts.map { getAudioDurationSeconds(it) }
                            bytes = concatAudio(parts, gap)
                            saveProjectFile(outputDirectory, "voices", "narration_001.mp3", bytes)
                            log("Narration ready – ${bytes.size / 1024} KB", LogEntry.Level.SUCCESS)
                        }
                        update { it.copy(narrationPath = "$outputDirectory/voices/narration_001.mp3") }
                        done(GENERATING_AUDIO, (System.currentTimeMillis() - t0) / 1000.0)

                        begin(ANALYZING_AUDIO)
                        val duration = try { getAudioDurationSeconds(bytes) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { throw StepFailure(ANALYZING_AUDIO, e) }
                        // Each scene lasts as long as its own narration plus the pause after it
                        val durations = sceneSecs.mapIndexed { i, d -> if (i < sceneSecs.lastIndex) d + gap else d }
                        update { it.copy(audioDurationSec = duration) }
                        log("Narration length: ${"%.1f".format(duration)}s", LogEntry.Level.SUCCESS)
                        done(ANALYZING_AUDIO)
                        Triple(bytes, duration, durations)
                    } catch (e: CancellationException) { throw e }
                    catch (e: StepFailure) { throw e }
                    catch (e: Exception) { throw StepFailure(GENERATING_AUDIO, e) }
                }

                // ── Visuals: prompts, then one image per scene ───────────────
                val visuals = async {
                    begin(GENERATING_IMAGE_PROMPTS)
                    val t0 = System.currentTimeMillis()
                    val onDisk = generateSequence(1) { it + 1 }
                        .map { readProjectText(outputDirectory, "prompts", "prompt_%03d.txt".format(it)) }
                        .takeWhile { it != null }.filterNotNull().toList()
                    val prompts = try {
                        if (onDisk.size == plannedCount) {
                            log("Resuming — ${onDisk.size} prompts loaded from disk", LogEntry.Level.SUCCESS)
                            onDisk
                        } else {
                            AppLogger.info("Writing $plannedCount image prompts…", GENERATING_IMAGE_PROMPTS)
                            val written = ai.generateImagePrompts(scenes)
                            if (written.isEmpty()) throw Exception("The text model returned no usable image prompts.")
                            // A scene without a prompt is drawn from its own narration text
                            val p = scenes.mapIndexed { i, text -> written.getOrNull(i) ?: text }
                            p.forEachIndexed { i, text -> saveProjectText(outputDirectory, "prompts", "prompt_%03d.txt".format(i + 1), text) }
                            log("${p.size} image prompts ready", LogEntry.Level.SUCCESS)
                            p
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { throw StepFailure(GENERATING_IMAGE_PROMPTS, e) }
                    update { it.copy(scenePrompts = prompts) }
                    cp = cp.copy(imageCount = prompts.size, lastCompletedStep = GENERATING_IMAGE_PROMPTS.name)
                    PipelineCheckpoint.save(outputDirectory, cp)
                    done(GENERATING_IMAGE_PROMPTS, (System.currentTimeMillis() - t0) / 1000.0)

                    begin(GENERATING_IMAGES)
                    val tImages = System.currentTimeMillis()
                    val images = mutableListOf<ByteArray>()
                    var rendered = 0
                    try {
                        for ((idx, imgPrompt) in prompts.withIndex()) {
                            val fileName  = "image_%03d.jpg".format(idx + 1)
                            val imagePath = "$outputDirectory/images/$fileName"
                            val existing  = readProjectFile(outputDirectory, "images", fileName)
                            val bytes = if (existing != null) existing else {
                                update { it.copy(renderingScene = idx, renderingProgress = 0f) }
                                sd.onProgress = { p ->
                                    update { it.copy(renderingProgress = p) }
                                    progress(GENERATING_IMAGES, (idx + p) / prompts.size)
                                }
                                val t = System.currentTimeMillis()
                                val img = renderWithRetry(idx, prompts.size) {
                                    // The style prefix is added by the SD provider; the frame size sets the framing
                                    sd.generateImage(imgPrompt)
                                }
                                saveProjectFile(outputDirectory, "images", fileName, img)
                                rendered++
                                log("Scene ${idx + 1}/${prompts.size} – ${"%.1f".format((System.currentTimeMillis() - t) / 1000.0)}s", LogEntry.Level.SUCCESS)
                                img
                            }
                            images += bytes
                            update { it.copy(sceneImages = it.sceneImages + (idx to imagePath), renderingScene = null) }
                            progress(GENERATING_IMAGES, (idx + 1f) / prompts.size)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { throw StepFailure(GENERATING_IMAGES, e) }
                    val imgSecs = (System.currentTimeMillis() - tImages) / 1000.0
                    if (rendered > 0) {
                        update { it.copy(timings = it.timings + ("SECONDS_PER_IMAGE" to imgSecs / rendered)) }
                        log("Images: $rendered in ${"%.0f".format(imgSecs)}s (${"%.1f".format(imgSecs / rendered)}s each)", LogEntry.Level.SUCCESS)
                    }
                    cp = cp.copy(lastCompletedStep = GENERATING_IMAGES.name)
                    PipelineCheckpoint.save(outputDirectory, cp)
                    done(GENERATING_IMAGES, imgSecs)
                    images.toList()
                }

                // Wait for both; a failed narration stops the visuals and vice versa
                val audioResult = runCatching { audio.await() }
                if (audioResult.isFailure) visuals.cancel()
                val visualResult = runCatching { visuals.await() }
                if (visualResult.isFailure && audio.isActive) audio.cancel()
                when {
                    audioResult.isFailure && audioResult.exceptionOrNull() !is CancellationException ->
                        Result.failure(audioResult.exceptionOrNull()!!)
                    visualResult.isFailure -> Result.failure(visualResult.exceptionOrNull()!!)
                    audioResult.isFailure  -> Result.failure(audioResult.exceptionOrNull()!!)
                    else -> Result.success(audioResult.getOrThrow() to visualResult.getOrThrow())
                }
            }

            val (audioPart, imageBytesList) = result.getOrElse { e ->
                if (e is CancellationException) throw e
                val failedStep = (e as? StepFailure)?.step ?: state.currentStep
                return fail(failedStep, (e as? StepFailure)?.cause ?: e)
            }
            val (audioBytes, duration, sceneDurations) = audioPart

            // ── Compose video ────────────────────────────────────────────────
            begin(COMPOSING_VIDEO)
            val t0 = System.currentTimeMillis()
            val (width, height) = videoSize(aspectRatio, settings.videoResolution)
            AppLogger.info("Composing ${width}×$height video, ${sceneDurations.size} scenes, ${"%.1f".format(duration)}s…", COMPOSING_VIDEO)
            val videoResult = try {
                generateVideo(
                    imageBytesList   = imageBytesList,
                    audioBytes       = audioBytes,
                    sceneDurations   = sceneDurations,
                    crossfadeSeconds = settings.crossfadeDuration,
                    transitionType   = settings.transitionType,
                    zoomAmount       = settings.zoomAmount,
                    outputName       = Generation.VIDEO_NAME,
                    outputDirectory  = "$outputDirectory/videos",
                    width            = width,
                    height           = height,
                    onProgress       = { p -> progress(COMPOSING_VIDEO, p) }
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { return fail(COMPOSING_VIDEO, e) }

            when (videoResult) {
                is VideoResult.Success -> {
                    done(COMPOSING_VIDEO, (System.currentTimeMillis() - t0) / 1000.0)
                    val total = (System.currentTimeMillis() - runStarted) / 1000.0
                    update {
                        it.copy(
                            generatedVideoPath = videoResult.handle,
                            currentStep        = COMPLETE,
                            overallProgress    = 1f,
                            isGenerating       = false,
                            activeSteps        = emptySet(),
                            timings            = it.timings + ("TOTAL" to total)
                        )
                    }
                    log("Video ready in ${"%.0f".format(total)}s → ${videoResult.handle}", LogEntry.Level.SUCCESS)
                    log("Timings: " + state.timings.entries.joinToString { (k, v) -> "$k ${"%.1f".format(v)}s" })
                }
                is VideoResult.Error -> return fail(COMPOSING_VIDEO, Exception(videoResult.message))
            }

            PipelineCheckpoint.markCompleted(outputDirectory, cp)
            callbacks.onProgress(1f, 1f)
            callbacks.onStepChange(COMPLETE)
            return state

        } catch (e: CancellationException) {
            // Keep a resumable checkpoint; finished files stay on disk
            if (cp.status != "failed") PipelineCheckpoint.save(outputDirectory, cp.copy(status = "running"))
            update { it.copy(currentStep = IDLE, isGenerating = false, activeSteps = emptySet(), renderingScene = null) }
            log("Generation cancelled", LogEntry.Level.WARNING)
            throw e
        } catch (e: Exception) {
            return fail(state.currentStep.takeIf { it in GenerationStep.pipelineSteps } ?: GENERATING_AUDIO, e)
        }
    }

    /** One automatic retry per scene: a hung or crashed engine is restarted on the second try. */
    private suspend fun renderWithRetry(idx: Int, total: Int, block: suspend () -> ByteArray): ByteArray =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warning("Scene ${idx + 1}/$total failed (${e.message}); retrying once…", GENERATING_IMAGES)
            block()
        }

    companion object {
        /** Silence between scenes, so sentences do not run into each other at the joins. */
        const val SCENE_GAP_SECONDS = 0.25

        /** Output size from the aspect ratio and the "720p" / "1080p" / "4K" setting (short side). */
        fun videoSize(aspect: ImageAspectRatio, resolution: String): Pair<Int, Int> {
            val short = when (resolution) { "720p" -> 720; "4K" -> 2160; else -> 1080 }
            val (rw, rh) = aspect.id.split(':').map { it.toDouble() }
            fun even(v: Double) = (v / 2).toInt() * 2
            return if (rw >= rh) even(short * rw / rh) to short else short to even(short * rh / rw)
        }
    }
}
