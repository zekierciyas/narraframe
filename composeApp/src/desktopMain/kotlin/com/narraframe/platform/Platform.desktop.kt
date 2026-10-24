package com.narraframe.platform

import com.narraframe.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.util.prefs.Preferences
import javax.sound.sampled.AudioSystem
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

// ── Platform name & OS ────────────────────────────────────────────────────
actual val platformName: String = "Desktop (JVM)"

actual val hostOs: HostOs = run {
    val os = System.getProperty("os.name").lowercase()
    when {
        os.contains("mac")   -> HostOs.MACOS
        os.contains("win")   -> HostOs.WINDOWS
        os.contains("linux") -> HostOs.LINUX
        else                 -> HostOs.OTHER
    }
}

// ── Startup initialisation ─────────────────────────────────────────────────
/**
 * Pre-resolves FFmpeg at app startup so the pipeline never blocks mid-generation.
 * Reports progress strings to [onProgress] for display on the loading screen.
 */
actual suspend fun initializePlatform(onProgress: (String) -> Unit) {
    withContext(Dispatchers.IO) {
        AppLogger.info("Platform init: checking FFmpeg…")
        onProgress("Checking FFmpeg…")

        // 1. Already resolved in this session
        _ffmpegExe?.let {
            AppLogger.info("Platform init: FFmpeg already resolved → $it")
            onProgress("FFmpeg ready ✓")
            return@withContext
        }

        // 2. Previously downloaded binary — but validate it's actually a real ELF/Mach-O
        //    binary, not a leftover HTML error page from a failed download.
        val cached = FfmpegDownloader.binary
        if (cached.exists()) {
            if (isValidBinary(cached)) {
                _ffmpegExe = cached.absolutePath
                AppLogger.info("Platform init: using cached FFmpeg → ${cached.absolutePath}")
                onProgress("FFmpeg ready ✓")
                return@withContext
            } else {
                AppLogger.warning("Platform init: cached file is not a valid binary – deleting and re-downloading")
                cached.delete()
            }
        }

        // 3. System shell PATH
        findFfmpegViaShell()?.let { path ->
            _ffmpegExe = path
            AppLogger.info("Platform init: found system FFmpeg → $path")
            onProgress("FFmpeg ready ✓")
            return@withContext
        }

        // 4. Auto-download (one-time, ~90 MB)
        AppLogger.info("Platform init: FFmpeg not found – starting download…")
        onProgress("FFmpeg not found – downloading (one-time setup, ~90 MB)…")
        try {
            val path = FfmpegDownloader.download { msg ->
                AppLogger.info("FFmpeg download: $msg")
                onProgress(msg)
            }
            _ffmpegExe = path
            AppLogger.success("Platform init: FFmpeg installed → $path")
            onProgress("FFmpeg installed ✓")
        } catch (e: Exception) {
            AppLogger.error("Platform init: FFmpeg download failed\n${e.stackTraceToString()}")
            throw Exception("FFmpeg could not be installed: ${e.message}", e)
        }
    }
}

/**
 * Checks the first 4 bytes of [file] for known executable magic numbers:
 *   ELF  (Linux)       : 7F 45 4C 46
 *   Mach-O (macOS x64) : CE FA ED FE  or  CF FA ED FE
 *   Mach-O (macOS arm) : CA FE BA BE  or  same as above with different endian
 *   PE   (Windows)     : 4D 5A  ("MZ")
 * Returns false when the file looks like text (HTML, JSON error page, etc.).
 */
private fun isValidBinary(file: File): Boolean {
    if (file.length() < 4) return false
    return try {
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        // ELF
        if (magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
            magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()) return true
        // Mach-O (little-endian 64-bit: CF FA ED FE, or 32-bit: CE FA ED FE)
        if ((magic[0] == 0xCF.toByte() || magic[0] == 0xCE.toByte()) &&
            magic[1] == 0xFA.toByte() && magic[2] == 0xED.toByte() &&
            magic[3] == 0xFE.toByte()) return true
        // Mach-O fat binary: CA FE BA BE
        if (magic[0] == 0xCA.toByte() && magic[1] == 0xFE.toByte() &&
            magic[2] == 0xBA.toByte() && magic[3] == 0xBE.toByte()) return true
        // PE (Windows): MZ
        if (magic[0] == 0x4D.toByte() && magic[1] == 0x5A.toByte()) return true
        false
    } catch (_: Exception) { false }
}

// ── FFmpeg executable resolution (with auto-download fallback) ────────────
// Priority:
//   1. Already resolved in this JVM session (cached in _ffmpegExe)
//   2. Previously downloaded binary in ~/.narraframe/bin/
//   3. System binary found via login shell (respects .zshrc / .bash_profile)
//   4. Auto-download from BtbN (Linux/Windows) or evermeet.cx (macOS)

@Volatile private var _ffmpegExe: String? = null
private val ffmpegMutex = Mutex()

private suspend fun ffmpeg(): String {
    _ffmpegExe?.let { return it }
    return ffmpegMutex.withLock {
        _ffmpegExe ?: resolveFfmpeg().also { _ffmpegExe = it }
    }
}

private suspend fun resolveFfmpeg(): String = withContext(Dispatchers.IO) {
    // 1. Cached download
    FfmpegDownloader.getCachedPath()?.also {
        AppLogger.info("FFmpeg: using cached binary → $it")
        return@withContext it
    }
    // 2. System shell PATH (login shell resolves Homebrew / nix / custom installs)
    findFfmpegViaShell()?.also {
        AppLogger.info("FFmpeg: found system binary → $it")
        return@withContext it
    }
    // 3. Auto-download
    FfmpegDownloader.download { AppLogger.info(it) }
}

/** Asks the login shell for FFmpeg's absolute path via `command -v` / `where`. */
private fun findFfmpegViaShell(): String? {
    val os    = System.getProperty("os.name").lowercase()
    val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
    return try {
        val cmd = if (os.contains("win")) listOf("where", "ffmpeg")
                  else listOf(shell, "-l", "-c", "command -v ffmpeg")
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        proc.outputStream.close()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        val candidate = if (os.contains("win"))
            out.lines().firstOrNull { it.isNotBlank() }
        else
            out.lines().lastOrNull { it.startsWith('/') }
        candidate?.takeIf { File(it).exists() && File(it).canExecute() }
    } catch (_: Exception) { null }
}

private fun processBuilder(cmd: List<String>): ProcessBuilder = ProcessBuilder(cmd)

// ── Local TTS ──────────────────────────────────────────────────────────────
actual val tempDirectory: String =
    File(System.getProperty("java.io.tmpdir"), "Narraframe").also { it.mkdirs() }.absolutePath

actual suspend fun generateLocalTts(text: String, tempDir: String, voice: String, speed: Double): ByteArray =
    withContext(Dispatchers.IO) {
        val base    = File(tempDir, "local_tts_${System.currentTimeMillis()}")
        val txtFile = File("${base.absolutePath}.txt").also { it.writeText(text) }

        val os = System.getProperty("os.name").lowercase()

        // Step 1: synthesise to an intermediate audio file
        val (intermediateFile, ttsCmd) = when {
            os.contains("mac") -> {
                val aiff = File("${base.absolutePath}.aiff")
                val cmd  = mutableListOf("say", "-f", txtFile.absolutePath, "-o", aiff.absolutePath)
                if (voice.isNotBlank()) { cmd.add(1, voice); cmd.add(1, "-v") }
                aiff to cmd
            }
            os.contains("linux") -> {
                val wav = File("${base.absolutePath}.wav")
                val cmd = mutableListOf("espeak-ng", "-f", txtFile.absolutePath, "-w", wav.absolutePath)
                if (voice.isNotBlank()) cmd.addAll(listOf("--voice=$voice"))
                wav to cmd
            }
            os.contains("win") -> {
                val wav = File("${base.absolutePath}.wav")
                val ps  = """
                    Add-Type -AssemblyName System.Speech
                    ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    ${'$'}s.SetOutputToWaveFile('${wav.absolutePath.replace("\\", "/")}')
                    ${'$'}s.SpeakFromFile('${txtFile.absolutePath.replace("\\", "/")}')
                    ${'$'}s.Dispose()
                """.trimIndent()
                wav to listOf("powershell", "-Command", ps)
            }
            else -> throw UnsupportedOperationException("Local TTS not supported on OS: $os")
        }

        AppLogger.info("🖥️ Running local TTS: ${ttsCmd.first()}...")
        // OS voices print nothing while rendering, so allow a long quiet period
        val tts = Processes.run(ttsCmd, stallMs = 20 * 60_000L)
        txtFile.delete()

        if (tts.exitCode != 0 || !intermediateFile.exists()) {
            throw Exception(
                "Local TTS command failed (exit ${tts.exitCode}). Is '${ttsCmd.first()}' installed?" +
                if (tts.log.isNotBlank()) "\n${tts.log.takeLast(400)}" else ""
            )
        }

        // Step 2: convert to MP3 via FFmpeg; apply speed via atempo filter if not 1.0
        val mp3File = File("${base.absolutePath}.mp3")
        AppLogger.debug("Converting ${intermediateFile.name} → MP3 via FFmpeg (speed=${"%.2f".format(speed)}x)...")
        val ffArgs = mutableListOf(
            ffmpeg(), "-y", "-i", intermediateFile.absolutePath
        )
        val clampedSpeed = speed.coerceIn(0.5, 2.0)
        if (clampedSpeed != 1.0) ffArgs.addAll(listOf("-filter:a", "atempo=${"%.2f".format(clampedSpeed)}"))
        ffArgs.addAll(listOf("-codec:a", "libmp3lame", "-qscale:a", "2", mp3File.absolutePath))

        val ff = Processes.run(ffArgs)
        intermediateFile.delete()

        if (ff.exitCode != 0)
            throw Exception("FFmpeg audio conversion failed (exit ${ff.exitCode}).\n${ff.log.takeLast(400)}")

        val bytes = mp3File.readBytes()
        mp3File.delete()
        AppLogger.success("✓ Local TTS complete (${bytes.size / 1024} KB)")
        bytes
    }

// ── Neural TTS (Kokoro / Qwen3 via embedded Python sidecar) ───────────────
actual val isNeuralTtsSupported: Boolean = true

actual suspend fun isNeuralTtsRuntimeInstalled(): Boolean =
    withContext(Dispatchers.IO) { PythonRuntimeInstaller.isInstalled() }

actual suspend fun isNeuralTtsModelInstalled(engine: String): Boolean =
    withContext(Dispatchers.IO) {
        (!engine.startsWith("qwen3") || PythonRuntimeInstaller.isQwenInstalled()) &&
            NeuralTtsSidecar.isModelCached(engine)
    }

actual suspend fun installNeuralTtsRuntime(onProgress: (String) -> Unit) {
    PythonRuntimeInstaller.install(onProgress)
}

actual suspend fun installNeuralTtsModel(engine: String, onProgress: (String) -> Unit) {
    // Qwen3 needs torch + transformers, which the Kokoro-only base runtime skips
    if (engine.startsWith("qwen3")) PythonRuntimeInstaller.installQwenExtras(onProgress)
    NeuralTtsSidecar.installModel(engine, onProgress)
}

actual suspend fun neuralTtsHardwareHint(): String = NeuralTtsSidecar.hardwareHint()

actual fun readLocalFileBytes(path: String): ByteArray? =
    if (path.isBlank()) null else try { File(path).readBytes() } catch (_: Exception) { null }

actual suspend fun neuralTtsSynthesize(
    engine:              String,
    voice:               String,
    speed:               Double,
    text:                String,
    referenceAudioBytes: ByteArray?
): ByteArray = withContext(Dispatchers.IO) {
    AppLogger.info("🧠 Neural TTS ($engine, voice=$voice, speed=${speed}x, clone=${referenceAudioBytes != null})")
    val wavBytes = NeuralTtsSidecar.synthesize(engine, voice, speed, text, referenceAudioBytes)

    val base    = File(tempDirectory, "neural_tts_${System.currentTimeMillis()}")
    val wavFile = File("${base.absolutePath}.wav").also { it.writeBytes(wavBytes) }
    val mp3File = File("${base.absolutePath}.mp3")

    val ff = Processes.run(listOf(
        ffmpeg(), "-y", "-i", wavFile.absolutePath,
        "-codec:a", "libmp3lame", "-qscale:a", "2", mp3File.absolutePath
    ))
    wavFile.delete()
    if (ff.exitCode != 0) {
        mp3File.delete()
        throw Exception("FFmpeg WAV→MP3 failed (exit ${ff.exitCode})\n${ff.log.takeLast(400)}")
    }

    val bytes = mp3File.readBytes()
    mp3File.delete()
    AppLogger.success("✓ Neural TTS complete (${bytes.size / 1024} KB)")
    bytes
}

// ── Persistent storage – JVM Preferences ──────────────────────────────────
private val prefs = Preferences.userRoot().node("com/narraframe")

actual fun loadString(key: String, default: String): String = prefs.get(key, default)
actual fun saveString(key: String, value: String)  { prefs.put(key, value) }
actual fun loadDouble(key: String, default: Double): Double = prefs.getDouble(key, default)
actual fun saveDouble(key: String, value: Double)  { prefs.putDouble(key, value) }
actual fun loadLong(key: String, default: Long): Long = prefs.getLong(key, default)
actual fun saveLong(key: String, value: Long)  { prefs.putLong(key, value) }

actual val isAppleSilicon: Boolean =
    hostOs == HostOs.MACOS && System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }

actual val totalMemoryGb: Int = runCatching {
    val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        as com.sun.management.OperatingSystemMXBean
    (bean.totalMemorySize / (1024L * 1024 * 1024)).toInt()
}.getOrDefault(0)

actual val hardwareSummary: String = buildList {
    add(when {
        isAppleSilicon         -> "Apple Silicon"
        hostOs == HostOs.MACOS -> "Intel Mac"
        else -> "${System.getProperty("os.name")} · ${System.getProperty("os.arch")}"
    })
    add("${Runtime.getRuntime().availableProcessors()} CPU threads")
    if (totalMemoryGb > 0) add("$totalMemoryGb GB RAM")
}.joinToString(" · ")

// ── Files & OS integration ─────────────────────────────────────────────────
private val tempRoot: File get() = File(tempDirectory)

actual suspend fun saveCopyAs(sourcePath: String, suggestedName: String): String? {
    val target = withContext(Dispatchers.Main) {
        val chooser = JFileChooser().apply {
            dialogTitle  = "Save $suggestedName"
            selectedFile = File(suggestedName)
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    } ?: return null
    return withContext(Dispatchers.IO) {
        File(sourcePath).copyTo(target, overwrite = true)
        AppLogger.success("Exported to: ${target.absolutePath}")
        target.absolutePath
    }
}

actual fun openWithSystem(path: String) {
    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(File(path)) }
        .onFailure { AppLogger.error("Could not open $path: ${it.message}") }
}

actual fun revealInFileManager(path: String) {
    val file = File(path)
    runCatching {
        when (hostOs) {
            HostOs.MACOS   -> ProcessBuilder("open", "-R", file.absolutePath).start()
            HostOs.WINDOWS -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            else           -> Desktop.getDesktop().open(if (file.isDirectory) file else file.parentFile)
        }
    }.onFailure { AppLogger.error("Could not reveal $path: ${it.message}") }
}

actual fun moveToTrash(path: String): Boolean = runCatching {
    Desktop.isDesktopSupported() &&
        Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
        Desktop.getDesktop().moveToTrash(File(path))
}.getOrDefault(false)

actual fun listDirectories(path: String): List<String> =
    File(path).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        ?.sortedByDescending { it.name }
        ?.map { it.absolutePath }
        ?: emptyList()

actual val defaultLibraryPath: String = run {
    val home   = System.getProperty("user.home")
    val movies = File(home, if (hostOs == HostOs.MACOS) "Movies" else "Videos")
    File(if (movies.isDirectory) movies else File(home), "Narraframe").absolutePath
}

actual suspend fun dataDirectorySizeBytes(): Long = withContext(Dispatchers.IO) {
    if (!DataDir.isAvailable) return@withContext 0L
    DataDir.root
        .walkTopDown()
        .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile }
        .sumOf { it.length() }
}

actual fun dataDirectory(): String = DataDir.root.absolutePath

actual val defaultDataDirectory: String = DataDir.defaultRoot.absolutePath

actual fun isDataDirectoryAvailable(): Boolean = DataDir.isAvailable

actual fun dataDirectoryHasContent(): Boolean = DataDir.isAvailable && DataDir.hasContent()

actual suspend fun changeDataDirectory(newRoot: String, moveExisting: Boolean, onProgress: (String) -> Unit) =
    withContext(Dispatchers.IO) {
        NeuralTtsSidecar.shutdown()
        SdServer.stop()
        DataDir.change(File(newRoot), moveExisting, onProgress)
        _ffmpegExe = null   // a moved FFmpeg has a new path
    }

actual fun isPathAvailable(path: String): Boolean =
    path.isNotBlank() && File(path).let { it.isDirectory || it.parentFile?.isDirectory == true }

actual fun freeSpaceBytes(path: String): Long {
    var f: File? = File(path)
    while (f != null && !f.exists()) f = f.parentFile
    return f?.usableSpace ?: 0L
}

actual val forceSetupRequested: Boolean =
    System.getProperty("narraframe.forceSetup")?.toBoolean() == true

// ── Audio playback (MP3 → WAV via FFmpeg, played with javax.sound) ─────────
@Volatile private var currentClip: javax.sound.sampled.Clip? = null

actual suspend fun playAudio(audioBytes: ByteArray) {
    stopAudio()
    val wav = withContext(Dispatchers.IO) {
        val base    = File(tempDirectory, "preview_${System.currentTimeMillis()}")
        val mp3File = File("${base.absolutePath}.mp3").also { it.writeBytes(audioBytes) }
        val wavFile = File("${base.absolutePath}.wav")
        val proc = processBuilder(listOf(
            ffmpeg(), "-y", "-i", mp3File.absolutePath, "-acodec", "pcm_s16le", wavFile.absolutePath
        )).redirectErrorStream(true).start()
        proc.outputStream.close()
        proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        mp3File.delete()
        if (exit != 0) throw Exception("FFmpeg could not decode the audio (exit $exit)")
        wavFile
    }
    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    val clip = AudioSystem.getClip()
    withContext(Dispatchers.IO) {
        AudioSystem.getAudioInputStream(wav).use { clip.open(it) }
        wav.delete()
    }
    clip.addLineListener { e ->
        if (e.type == javax.sound.sampled.LineEvent.Type.STOP) done.complete(Unit)
    }
    currentClip = clip
    clip.start()
    try {
        done.await()
    } finally {
        clip.stop()
        clip.close()
        if (currentClip === clip) currentClip = null
    }
}

actual fun stopAudio() {
    currentClip?.stop()
}

actual suspend fun listSystemVoices(): List<String> = withContext(Dispatchers.IO) {
    if (hostOs != HostOs.MACOS) return@withContext emptyList()
    runCatching {
        val proc = ProcessBuilder("say", "-v", "?").redirectErrorStream(true).start()
        val out  = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // Lines look like: "Samantha            en_US    # Hello! My name is Samantha."
        out.lines()
            .mapNotNull { line ->
                val head = line.substringBefore('#').trimEnd()
                val m = Regex("""^(.+?)\s+(\w{2}[_-]\w+)$""").find(head) ?: return@mapNotNull null
                val (name, lang) = m.destructured
                if (lang.startsWith("en")) name.trim() else null
            }
            .distinct()
    }.getOrDefault(emptyList())
}

// ── Audio analysis ─────────────────────────────────────────────────────────
actual suspend fun getAudioDurationSeconds(audioBytes: ByteArray): Double =
    withContext(Dispatchers.IO) {
        // WAV: exact from the header. Everything else (MP3 from every voice engine) is
        // decoded with FFmpeg — a size-based guess was up to 2× off for low-bitrate MP3.
        parseWavDuration(audioBytes) ?: run {
            val tmp = File(tempRoot, "audio_probe_${System.currentTimeMillis()}.mp3").also { it.writeBytes(audioBytes) }
            try {
                probeDuration(tmp) ?: throw Exception("Could not read the narration length")
            } finally {
                tmp.delete()
            }
        }
    }

/**
 * Exact playing time of a media file: FFmpeg decodes it to null output and the last
 * "time=" it reports is the true length (the container's Duration header can be off for MP3).
 */
internal suspend fun probeDuration(file: File): Double? {
    fun parse(m: MatchResult): Double {
        val (h, mi, s) = m.destructured
        return h.toDouble() * 3600 + mi.toDouble() * 60 + s.toDouble()
    }
    val r = Processes.run(listOf(ffmpeg(), "-hide_banner", "-nostdin", "-i", file.absolutePath, "-f", "null", "-"))
    val decoded = Regex("""time=(\d+):(\d+):([\d.]+)""").findAll(r.stdout).lastOrNull()?.let(::parse)
    val header  = Regex("""Duration:\s*(\d+):(\d+):([\d.]+)""").find(r.stdout)?.let(::parse)
    return (decoded ?: header)?.takeIf { it > 0 }
}

/**
 * Joins MP3 [parts] into one MP3 with [gapSeconds] of silence between them.
 * Re-encodes so there are no gaps or clicks at the joins.
 */
actual suspend fun concatAudio(parts: List<ByteArray>, gapSeconds: Double): ByteArray = withContext(Dispatchers.IO) {
    if (parts.size == 1 && gapSeconds <= 0) return@withContext parts.first()
    val dir = File(tempRoot, "concat_${System.currentTimeMillis()}").also { it.mkdirs() }
    try {
        val files = parts.mapIndexed { i, b -> File(dir, "part_%03d.mp3".format(i)).also { it.writeBytes(b) } }
        val cmd = buildList {
            add(ffmpeg()); add("-y")
            files.forEach { addAll(listOf("-i", it.absolutePath)) }
            val filter = StringBuilder()
            files.indices.forEach { i ->
                // Same format for every part, then a silent pad after all but the last
                filter.append("[$i:a]aresample=44100,aformat=channel_layouts=mono")
                if (gapSeconds > 0 && i < files.lastIndex) filter.append(",apad=pad_dur=${"%.3f".format(java.util.Locale.ROOT, gapSeconds)}")
                filter.append("[a$i];")
            }
            files.indices.forEach { filter.append("[a$it]") }
            filter.append("concat=n=${files.size}:v=0:a=1[out]")
            addAll(listOf("-filter_complex", filter.toString(), "-map", "[out]"))
            addAll(listOf("-codec:a", "libmp3lame", "-qscale:a", "2", File(dir, "joined.mp3").absolutePath))
        }
        val r = Processes.run(cmd)
        if (r.exitCode != 0) throw Exception("Joining the narration failed: ${r.log.takeLast(300)}")
        File(dir, "joined.mp3").readBytes()
    } finally {
        dir.deleteRecursively()
    }
}

/**
 * Parses WAV header bytes to compute precise audio duration.
 * Returns null if bytes are not a valid RIFF/WAVE file.
 *
 * Uses blockAlign from the fmt chunk so the formula works for
 * 8-bit, 16-bit, 24-bit, 32-bit and any channel count.
 *   duration = (dataChunkSize / blockAlign) / sampleRate
 */
private fun parseWavDuration(bytes: ByteArray): Double? {
    if (bytes.size < 44) return null
    if (bytes.take4(0) != "RIFF" || bytes.take4(8) != "WAVE") return null

    var pos        = 12
    var sampleRate = 0L
    var blockAlign = 0
    var dataBytes  = 0L

    while (pos + 8 <= bytes.size) {
        val tag      = bytes.take4(pos)
        val chunkLen = readLeInt32(bytes, pos + 4)
        when (tag) {
            "fmt " -> {
                if (chunkLen >= 16) {
                    sampleRate = readLeInt32(bytes, pos + 12)
                    blockAlign = readLeInt16(bytes, pos + 20)
                }
            }
            "data" -> { dataBytes = chunkLen; break }
        }
        // WAV chunks are padded to even byte boundaries
        val advance = 8L + ((chunkLen + 1L) and -2L)
        pos = (pos + advance).toInt()
        if (pos <= 0) break   // guard against overflow
    }

    if (sampleRate <= 0 || blockAlign <= 0 || dataBytes <= 0) return null
    return (dataBytes / blockAlign).toDouble() / sampleRate.toDouble()
}

/** Reads 4 bytes at [offset] as an ASCII string (e.g. "RIFF", "data"). */
private fun ByteArray.take4(offset: Int): String =
    if (offset + 4 <= size) String(this, offset, 4, Charsets.ISO_8859_1) else ""

/** Reads a little-endian unsigned 32-bit value as Long. */
private fun readLeInt32(b: ByteArray, off: Int): Long =
    (b[off    ].toLong() and 0xFF) or
    ((b[off + 1].toLong() and 0xFF) shl  8) or
    ((b[off + 2].toLong() and 0xFF) shl 16) or
    ((b[off + 3].toLong() and 0xFF) shl 24)

/** Reads a little-endian unsigned 16-bit value as Int. */
private fun readLeInt16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

// ── Video generation via FFmpeg ────────────────────────────────────────────
actual suspend fun pickOutputDirectory(title: String): String? =
    withContext(Dispatchers.Main) {
        val chooser = JFileChooser().apply {
            dialogTitle           = title
            fileSelectionMode     = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            chooser.selectedFile.absolutePath
        else null
    }

actual suspend fun pickFile(title: String, extensions: List<String>): String? =
    withContext(Dispatchers.Main) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            if (extensions.isNotEmpty()) {
                fileFilter = FileNameExtensionFilter(
                    extensions.joinToString(", ") { ".$it" },
                    *extensions.toTypedArray()
                )
            }
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
            chooser.selectedFile.absolutePath
        else null
    }

actual suspend fun generateVideo(
    imageBytesList:   List<ByteArray>,
    audioBytes:       ByteArray,
    sceneDurations:   List<Double>,
    crossfadeSeconds: Double,
    transitionType:   String,
    zoomAmount:       Double,
    outputName:       String,
    outputDirectory:  String,
    width:            Int,
    height:           Int,
    onProgress:       (Float) -> Unit
): VideoResult = withContext(Dispatchers.IO) {
    // Output frame size; both even for yuv420p
    val W = width / 2 * 2
    val H = height / 2 * 2
    try {
        val sessionDir = File(tempRoot, "session_${System.currentTimeMillis()}").also { it.mkdirs() }
        val n = imageBytesList.size
        require(sceneDurations.size == n) { "Need one duration per scene (${sceneDurations.size} for $n images)" }

        AppLogger.info("Writing $n image files as JPEG… (transition=$transitionType)")
        val imageFiles = imageBytesList.mapIndexed { idx, bytes ->
            File(sessionDir, "image_%04d.jpg".format(idx + 1)).also { it.writeBytes(toJpegBytes(bytes)) }
                .also { onProgress((idx + 1).toFloat() / n * 0.4f) }
        }

        val audioFile = File(sessionDir, "narration.mp3").also { it.writeBytes(audioBytes) }
        val outDir    = File(outputDirectory).also { it.mkdirs() }
        val outputFile = File(outDir, "$outputName.mp4")

        // The video is exactly as long as the narration — measured by decoding it, never estimated
        val audioDuration = probeDuration(audioFile) ?: sceneDurations.sum()
        // Scene times come from the narration of each scene; stretch them so they add up exactly
        val scale = audioDuration / sceneDurations.sum().coerceAtLeast(0.001)
        val durs = sceneDurations.map { (it * scale).coerceAtLeast(0.2) }
        AppLogger.info("Audio duration: ${"%.2f".format(audioDuration)}s, scenes: ${durs.joinToString { "%.1f".format(it) }}")

        val fps = 25
        val useZoom = transitionType == "zoom_in" || transitionType == "zoom_out"
        // Crossfade never longer than 40 % of the shortest scene
        val cf = if (n <= 1 || transitionType == "none" || crossfadeSeconds <= 0.0) 0.0
                 else crossfadeSeconds.coerceIn(0.05, durs.min() * 0.4)
        val useXfade = cf > 0.0

        // Each clip (except the last) runs cf longer so the fade starts exactly at the
        // scene boundary: xfade offset i = sum of the previous scene durations.
        val clipLen = durs.mapIndexed { i, d -> if (useXfade && i < n - 1) d + cf else d }

        val sb = StringBuilder()
        val clampedZoom  = zoomAmount.coerceIn(1.01, 2.0)
        val srcW = ((W * clampedZoom).toInt() / 2) * 2
        val srcH = ((H * clampedZoom).toInt() / 2) * 2
        val shrinkFactor = "%.6f".format(1.0 - 1.0 / clampedZoom)

        // zoom_out starts from a random W×H window on the overscan canvas
        val zoomRng = java.util.Random()
        val maxXOff = ((srcW - W).coerceAtLeast(0) / 2) * 2
        val maxYOff = ((srcH - H).coerceAtLeast(0) / 2) * 2

        for (i in 0 until n) {
            val dur = "%.3f".format(java.util.Locale.ROOT, clipLen[i])
            sb.append("[$i:v]")
            if (useZoom) {
                // Upscale to the overscan canvas, then animate a crop window over time t
                sb.append("scale=$srcW:$srcH:force_original_aspect_ratio=increase:flags=lanczos,")
                sb.append("crop=$srcW:$srcH,")
                val (wExpr, hExpr, xExpr, yExpr) = if (transitionType == "zoom_in") {
                    val p = "min(t,$dur)/$dur"
                    listOf(
                        "trunc(${srcW}.0*(1.0-${shrinkFactor}*($p))/2)*2",
                        "trunc(${srcH}.0*(1.0-${shrinkFactor}*($p))/2)*2",
                        "trunc(${srcW}.0*${shrinkFactor}*($p)/4)*2",
                        "trunc(${srcH}.0*${shrinkFactor}*($p)/4)*2"
                    )
                } else {
                    // round() for the position avoids the 2-pixel jitter trunc() causes
                    val p = "min(t,$dur)/$dur"
                    val xOff = if (maxXOff > 0) zoomRng.nextInt(maxXOff / 2 + 1) * 2 else 0
                    val yOff = if (maxYOff > 0) zoomRng.nextInt(maxYOff / 2 + 1) * 2 else 0
                    listOf(
                        "round(($W.0+(${srcW}.0-$W.0)*($p))/2)*2",
                        "round(($H.0+(${srcH}.0-$H.0)*($p))/2)*2",
                        "round(${xOff}.0*(1.0-($p))/2)*2",
                        "round(${yOff}.0*(1.0-($p))/2)*2"
                    )
                }
                sb.append("crop=w='$wExpr':h='$hExpr':x='$xExpr':y='$yExpr',")
                sb.append("scale=$W:$H:flags=lanczos,setsar=1,")
            } else {
                // Fill the frame and crop the overhang: no black bars when ratios differ slightly
                sb.append("scale=$W:$H:force_original_aspect_ratio=increase:flags=lanczos,")
                sb.append("crop=$W:$H,setsar=1,")
            }
            sb.append("format=yuv420p,fps=fps=$fps[v$i];")
        }

        val finalLabel = when {
            n == 1 -> "v0"
            !useXfade -> {
                for (i in 0 until n) sb.append("[v$i]")
                sb.append("concat=n=$n:v=1:a=0[vout];")
                "vout"
            }
            else -> {
                var start = 0.0
                for (i in 1 until n) {
                    start += durs[i - 1]
                    val inputA   = if (i == 1) "[v0]" else "[x${i - 1}]"
                    val outLabel = if (i == n - 1) "vout" else "x$i"
                    sb.append("$inputA[v$i]xfade=transition=fade:duration=${"%.3f".format(java.util.Locale.ROOT, cf)}:" +
                              "offset=${"%.3f".format(java.util.Locale.ROOT, start)}[$outLabel];")
                }
                "vout"
            }
        }
        // Hold the last frame a little so rounding can never end the picture before the voice
        val filter = "${sb.toString().trimEnd(';', ' ')};[$finalLabel]tpad=stop_mode=clone:stop_duration=1[vfinal]"

        val ffmpegTemplate: List<Any> = buildList<Any> {
            add(ffmpeg()); add("-y")
            imageFiles.forEachIndexed { i, f ->
                addAll(listOf("-loop", "1", "-t", "%.3f".format(java.util.Locale.ROOT, clipLen[i]), "-i", f.absolutePath))
            }
            addAll(listOf("-i", audioFile.absolutePath))
            addAll(listOf("-filter_complex", filter))
            addAll(listOf("-map", "[vfinal]", "-map", "$n:a"))
            add(ENCODER_PLACEHOLDER)
            addAll(listOf("-c:a", "aac", "-b:a", "192k"))
            // Exactly the narration length (plus a short tail so the last word is never clipped)
            add("-t"); add("%.3f".format(java.util.Locale.ROOT, audioDuration + 0.3))
            add(outputFile.absolutePath)
        }

        // Encode with the hardware encoder when there is one; fall back to libx264 on failure
        suspend fun encode(encoder: List<String>): Processes.Result {
            val cmd = ffmpegTemplate.flatMap { if (it === ENCODER_PLACEHOLDER) encoder else listOf(it as String) }
            AppLogger.info("Running FFmpeg (${encoder[1]}): ${cmd.joinToString(" ").take(600)}")
            onProgress(0.45f)
            return Processes.run(cmd, workDir = sessionDir, stallMs = 3 * 60_000L) { line ->
                // "frame=  250 fps= 90 … time=00:00:10.00 …" → share of the audio length done
                Regex("""time=(\d+):(\d+):([\d.]+)""").find(line)?.let { m ->
                    val (h, mi, sec) = m.destructured
                    val t = h.toDouble() * 3600 + mi.toDouble() * 60 + sec.toDouble()
                    if (audioDuration > 0) onProgress(0.45f + 0.5f * (t / audioDuration).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        val started = System.currentTimeMillis()
        val preferred = videoEncoderArgs(W, H)
        var result = encode(preferred)
        if (result.exitCode != 0 && preferred[1] != "libx264") {
            AppLogger.warning("${preferred[1]} failed (exit ${result.exitCode}); retrying with libx264")
            result = encode(SOFTWARE_ENCODER)
        }
        onProgress(0.95f)

        if (result.exitCode != 0) {
            AppLogger.error("FFmpeg exited ${result.exitCode}\n${result.log.takeLast(3000)}")
            return@withContext VideoResult.Error("FFmpeg failed (exit ${result.exitCode}).\n${result.log.takeLast(400)}")
        }
        AppLogger.info("Video encoded in ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)}s")

        AppLogger.success("FFmpeg succeeded → ${outputFile.absolutePath}")

        onProgress(1f)
        VideoResult.Success(handle = outputFile.absolutePath, previewFrames = imageBytesList.take(3))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.error("Video generation error: ${e.message}")
        VideoResult.Error(e.message ?: "Unknown error during video generation")
    }
}

private val ENCODER_PLACEHOLDER = Any()
private val SOFTWARE_ENCODER = listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p")

/** Encoders compiled into the FFmpeg we use, read once. */
private val ffmpegEncoders: Set<String> by lazy {
    runCatching {
        val p = ProcessBuilder(kotlinx.coroutines.runBlocking { ffmpeg() }, "-hide_banner", "-encoders")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.lines().mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1) }.toSet()
    }.getOrDefault(emptySet())
}

/** Hardware H.264 encoder for this machine when available, else libx264. */
private fun videoEncoderArgs(width: Int, height: Int): List<String> {
    // Bitrate for hardware encoders that do not take a CRF: ~0.1 bit per pixel at 25 fps
    val kbps = (width.toLong() * height * 25 / 10_000).coerceIn(2_500, 40_000)
    return when {
        Hardware.computeMode == "cpu" -> SOFTWARE_ENCODER
        Hardware.isMac && "h264_videotoolbox" in ffmpegEncoders ->
            listOf("-c:v", "h264_videotoolbox", "-b:v", "${kbps}k", "-pix_fmt", "yuv420p")
        Hardware.gpu.accel == Accel.CUDA && "h264_nvenc" in ffmpegEncoders ->
            listOf("-c:v", "h264_nvenc", "-preset", "p4", "-cq", "21", "-pix_fmt", "yuv420p")
        else -> SOFTWARE_ENCODER
    }
}

/** Path to sd-server (the image engine). */
actual fun getLocalSDBinaryPath(): String? = LocalSDDownloader.getCachedPath()

actual suspend fun downloadSdBinary(onProgress: (String) -> Unit) {
    LocalSDDownloader.download(onProgress)
}
actual fun getLocalSDModelPath(modelId: String): String? {
    // If modelId looks like a full path to an existing .gguf file, use it directly
    if (modelId.endsWith(".gguf") && File(modelId).exists()) return modelId
    return LocalModelManager.getCachedSDModel(modelId)
}

actual val availableSDModels: List<SDModelInfo> =
    LocalModelManager.sdModels.map { SDModelInfo(it.id, it.name, it.sizeMB, it.desc, it.url) }

actual suspend fun downloadSDModel(modelId: String, onProgress: (String) -> Unit) {
    LocalModelManager.downloadSDModel(modelId, onProgress)
}

// ── Local LLM via llama.cpp ───────────────────────────────────────────────

actual fun getLocalLLMBinaryPath(): String? = LocalLLMDownloader.getCachedPath()

actual suspend fun downloadLLMBinary(onProgress: (String) -> Unit) {
    LocalLLMDownloader.download(onProgress)
}

actual fun getLocalLLMModelPath(modelId: String): String? =
    LocalModelManager.getCachedLLMModel(modelId)

actual val availableLLMModels: List<LLMModelInfo> =
    LocalModelManager.llmModels.map { LLMModelInfo(it.id, it.name, it.sizeMB, it.desc) }

actual suspend fun downloadLLMModel(modelId: String, onProgress: (String) -> Unit) {
    LocalModelManager.downloadLLMModel(modelId, onProgress)
}

/**
 * Runs llama-completion once to generate text from a GGUF model.
 *
 * The prompt is already in the model's chat format. With [jsonSchema] the output is
 * constrained by a grammar, so small models cannot drift into the wrong JSON shape.
 * Layers go to the GPU unless compute mode is CPU; if the GPU run fails it retries on CPU.
 */
actual suspend fun generateLocalLLMText(
    prompt:       String,
    modelPath:    String,
    llamaBinPath: String,
    maxTokens:    Int,
    jsonSchema:   String?
): String = withContext(Dispatchers.IO) {
    suspend fun attempt(gpu: Boolean): Processes.Result {
        val cmd = buildList {
            add(llamaBinPath)
            addAll(listOf("-m", modelPath))
            addAll(listOf("-p", prompt))
            addAll(listOf("-n", maxTokens.toString()))
            // Context: prompt (~3.5 chars per token) + answer + margin
            addAll(listOf("-c", (prompt.length / 3 + maxTokens + 512).coerceIn(4096, 32768).toString()))
            addAll(listOf("--temp", "0.8"))
            addAll(listOf("--repeat-penalty", "1.15"))   // small models otherwise loop on one word
            addAll(listOf("-ngl", if (gpu) "99" else "0"))
            if (jsonSchema != null) addAll(listOf("-j", jsonSchema))
            add("--simple-io")
            add("--no-display-prompt")
            add("-no-cnv")         // one-shot completion, never wait for chat input
            add("-e")              // process escape chars in prompt (\n etc.)
            // No --log-disable: in current builds it also silences the generated text.
        }
        AppLogger.info("🤖 llama.cpp: generating up to $maxTokens tokens (${if (gpu) Hardware.accel.label else "CPU"})…")
        AppLogger.debug("llama.cpp prompt (${prompt.length} chars):\n$prompt")
        // stdout = the answer; stderr = llama.cpp's own log, kept for errors only
        return Processes.run(cmd, mergeErr = false, stallMs = 3 * 60_000L)
    }

    val started = System.currentTimeMillis()
    val useGpu = Hardware.accel.isGpu
    var result = attempt(useGpu)
    if (result.exitCode != 0 && useGpu) {
        AppLogger.warning("llama.cpp failed on ${Hardware.accel.label} (exit ${result.exitCode}); retrying on CPU")
        result = attempt(false)
    }
    if (result.exitCode != 0) {
        throw Exception("llama.cpp exited with code ${result.exitCode}: ${result.log.takeLast(300)}")
    }
    val text = result.stdout.substringBefore("[end of text]").trim()
    AppLogger.success("✓ llama.cpp: ${text.length} chars in ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)}s")
    text
}

// ── Piper neural TTS ─────────────────────────────────────────────────────

/** Piper runs through the Python runtime; the "binary" is the venv's python. */
actual fun getLocalPiperBinaryPath(): String? =
    if (PythonRuntimeInstaller.isPiperInstalled()) PythonRuntimeInstaller.venvPython.absolutePath else null

actual suspend fun downloadPiperBinary(onProgress: (String) -> Unit) {
    PythonRuntimeInstaller.installPiper(onProgress)
}

actual val availablePiperVoices: List<PiperVoiceInfo> =
    LocalModelManager.piperVoices.map { PiperVoiceInfo(it.id, it.name, it.sizeMB, it.lang) }

actual fun getLocalPiperVoicePath(voiceId: String): String? {
    // A custom voice is an absolute .onnx path with its .onnx.json config next to it
    if (voiceId.endsWith(".onnx")) {
        val model = File(voiceId)
        return if (model.exists() && File("$voiceId.json").exists()) voiceId else null
    }
    return LocalModelManager.getCachedVoice(voiceId)
}

actual suspend fun downloadPiperVoice(voiceId: String, onProgress: (String) -> Unit) {
    LocalModelManager.downloadVoice(voiceId, onProgress)
}

/**
 * Generates speech with Piper (piper-tts in the Python runtime) via piper_synth.py.
 * Text goes in on stdin; the WAV is converted to MP3 with FFmpeg.
 *
 * espeak-ng cannot read its data from paths longer than ~160 characters, so the
 * bundled espeak-ng-data is reached through a short symlink in the temp folder.
 */
actual suspend fun generatePiperTts(
    text:      String,
    modelPath: String,
    piperBin:  String,
    tempDir:   String,
    speed:     Double
): ByteArray = generatePiperTtsParts(listOf(text), modelPath, piperBin, tempDir, speed).first()

/**
 * Generates one MP3 per text with a single Piper process, so the voice model loads once
 * instead of once per scene. Each WAV is converted to MP3 with FFmpeg.
 */
actual suspend fun generatePiperTtsParts(
    texts:     List<String>,
    modelPath: String,
    piperBin:  String,
    tempDir:   String,
    speed:     Double
): List<ByteArray> = withContext(Dispatchers.IO) {
    AppLogger.info("🎤 Piper TTS: ${texts.size} part(s) (model: ${File(modelPath).name})")
    PythonRuntimeInstaller.refreshPiperScript()
    val dir = File(tempDir, "piper_${System.currentTimeMillis()}").also { it.mkdirs() }
    try {
        val cmd = buildList {
            add(piperBin)
            add(PythonRuntimeInstaller.piperScript.absolutePath)
            addAll(listOf("--model", modelPath))
            addAll(listOf("--batch-dir", dir.absolutePath))
            addAll(listOf("--length-scale", "%.3f".format(java.util.Locale.ROOT, 1.0 / speed.coerceIn(0.5, 2.0))))
            piperEspeakDir()?.let { addAll(listOf("--espeak-dir", it)) }
        }
        val json = kotlinx.serialization.json.JsonArray(texts.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString()
        // Piper prints a line per finished part; a long part can still take a while
        val piper = Processes.run(cmd, stdin = json, stallMs = 20 * 60_000L)
        val wavs = texts.indices.map { File(dir, "part_%03d.wav".format(it)) }
        if (piper.exitCode != 0 || wavs.any { !it.exists() || it.length() < 100 }) {
            throw Exception("Piper failed (exit ${piper.exitCode}): ${piper.log.takeLast(400)}")
        }
        wavs.map { wav ->
            val mp3 = File(dir, wav.nameWithoutExtension + ".mp3")
            val ff = Processes.run(listOf(ffmpeg(), "-y", "-i", wav.absolutePath, "-codec:a", "libmp3lame", "-qscale:a", "2", mp3.absolutePath))
            if (ff.exitCode != 0) throw Exception("FFmpeg WAV→MP3 failed (exit ${ff.exitCode})\n${ff.log.takeLast(300)}")
            mp3.readBytes()
        }.also { AppLogger.success("✓ Piper TTS complete (${it.sumOf { b -> b.size } / 1024} KB)") }
    } finally {
        dir.deleteRecursively()
    }
}

/** Short symlink to piper's bundled espeak-ng-data, or null when it is not needed or not possible. */
private fun piperEspeakDir(): String? {
    val sitePackages = File(PythonRuntimeInstaller.venvDir, if (hostOs == HostOs.WINDOWS) "Lib/site-packages" else "lib")
    val data = sitePackages.walkTopDown().maxDepth(4)
        .firstOrNull { it.isDirectory && it.name == "espeak-ng-data" && it.parentFile.name == "piper" }
        ?: return null
    if (data.absolutePath.length < 120) return data.absolutePath
    return runCatching {
        val link = File(tempDirectory, "nf-espeak").toPath()
        java.nio.file.Files.deleteIfExists(link)
        java.nio.file.Files.createSymbolicLink(link, data.toPath()).toString()
    }.getOrNull()
}

// ── Local Stable Diffusion via sd.cpp ─────────────────────────────────────
actual suspend fun generateLocalSDImage(
    prompt:         String,
    modelPath:      String,
    sdBinPath:      String,
    outputDir:      String,
    width:          Int,
    height:         Int,
    steps:          Int,
    negativePrompt: String,
    stylePrefix:    String,
    cfgScale:       Float,
    sampler:        String,
    seed:           Long,
    onProgress:     (Float) -> Unit
): ByteArray = withContext(Dispatchers.IO) {
    val fullPrompt = if (stylePrefix.isNotBlank()) "${stylePrefix.trimEnd(',', ' ')}, $prompt" else prompt
    val started = System.currentTimeMillis()
    val bytes = SdServer.generate(
        serverBin      = sdBinPath,
        modelPath      = modelPath,
        prompt         = fullPrompt,
        negativePrompt = negativePrompt,
        width          = width,
        height         = height,
        steps          = steps,
        cfgScale       = cfgScale,
        sampler        = sampler,
        seed           = seed,
        onProgress     = onProgress
    )
    AppLogger.success(
        "sd.cpp image ${width}×$height, $steps steps: ${bytes.size / 1024} KB in " +
        "${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)}s (${SdServer.activeBackend})"
    )
    bytes
}

actual val gpuSummary: String get() =
    Hardware.gpu.let { g -> if (g.accel.isGpu) "${g.name} · ${g.accel.label}" else "No supported GPU · CPU" } +
        (if (Hardware.gpu.vramMb > 0) " · ${Hardware.gpu.vramMb / 1024} GB VRAM" else "")

actual fun activeAccelerator(): String = Hardware.accel.label

actual fun stopImageEngine() = SdServer.stop()

// ── Project file I/O ──────────────────────────────────────────────────────

actual fun ensureProjectDirs(projectPath: String) {
    listOf("images", "voices", "prompts", "videos").forEach {
        File(projectPath, it).mkdirs()
    }
}

actual fun saveProjectFile(projectPath: String, subDir: String, fileName: String, bytes: ByteArray): String {
    val dir = File(projectPath, subDir).also { it.mkdirs() }
    val file = File(dir, fileName)
    file.writeBytes(bytes)
    return file.absolutePath
}

actual fun saveProjectText(projectPath: String, subDir: String, fileName: String, content: String): String {
    val dir = File(projectPath, subDir).also { it.mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content)
    return file.absolutePath
}

actual fun readProjectFile(projectPath: String, subDir: String, fileName: String): ByteArray? {
    val file = if (subDir.isEmpty()) File(projectPath, fileName)
               else File(File(projectPath, subDir), fileName)
    return if (file.exists()) file.readBytes() else null
}

actual fun readProjectText(projectPath: String, subDir: String, fileName: String): String? {
    val file = if (subDir.isEmpty()) File(projectPath, fileName)
               else File(File(projectPath, subDir), fileName)
    return if (file.exists()) file.readText() else null
}

actual fun listProjectFiles(projectPath: String, subDir: String): List<String> {
    val dir = if (subDir.isEmpty()) File(projectPath) else File(projectPath, subDir)
    return if (dir.isDirectory) dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
           else emptyList()
}

// ── Image format conversion ───────────────────────────────────────────────

/**
 * Converts raw image bytes (PNG, JPEG, or any ImageIO-readable format) to JPEG bytes.
 * Handles alpha channels by compositing onto a white background (JPEG has no alpha).
 * Returns the original bytes unchanged if ImageIO cannot decode them.
 */
private fun toJpegBytes(raw: ByteArray, quality: Float = 0.92f): ByteArray {
    val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(raw)) ?: return raw
    // JPEG does not support alpha — flatten ARGB/custom-type images onto white
    val rgb = if (image.colorModel.hasAlpha() ||
                  image.type == java.awt.image.BufferedImage.TYPE_CUSTOM) {
        java.awt.image.BufferedImage(image.width, image.height,
                                     java.awt.image.BufferedImage.TYPE_INT_RGB).also { dst ->
            val g = dst.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, dst.width, dst.height)
            g.drawImage(image, 0, 0, null)
            g.dispose()
        }
    } else image
    val out    = java.io.ByteArrayOutputStream()
    val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next()
    val params = writer.defaultWriteParam.apply {
        compressionMode    = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    writer.output = javax.imageio.ImageIO.createImageOutputStream(out)
    writer.write(null, javax.imageio.IIOImage(rgb, null, null), params)
    writer.dispose()
    return out.toByteArray()
}
