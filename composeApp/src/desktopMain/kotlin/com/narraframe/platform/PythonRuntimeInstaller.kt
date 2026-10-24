package com.narraframe.platform

import com.narraframe.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Bootstraps an embedded, user-local Python runtime used exclusively by the
 * neural TTS sidecar. No system-wide Python install is touched — everything
 * lives under <data dir>/ai-runtime/.
 *
 * Pipeline (all idempotent):
 *   1. Download python-build-standalone tarball (Astral, ~40 MB)
 *   2. Extract it to <aiRuntime>/python/
 *   3. Create a venv at <aiRuntime>/venv/
 *   4. pip install the packages listed in bundled requirements.txt
 *   5. Extract server.py to <aiRuntime>/server.py
 */
internal object PythonRuntimeInstaller {

    private val osName = System.getProperty("os.name").lowercase()
    private val osArch = System.getProperty("os.arch").lowercase()

    val isWindows: Boolean get() = osName.contains("win")
    val isMac:     Boolean get() = osName.contains("mac")

    /** Root of all neural-TTS artifacts. */
    val aiRuntimeDir: File get() = DataDir.aiRuntime

    val pythonRoot: File get() = File(aiRuntimeDir, "python")
    val venvDir:    File get() = File(aiRuntimeDir, "venv")
    val cacheDir:   File get() = File(aiRuntimeDir, "cache").also { if (DataDir.isAvailable) it.mkdirs() }
    val serverPy:   File get() = File(aiRuntimeDir, "server.py")
    val readyFlag:  File get() = File(aiRuntimeDir, ".runtime-ready")
    private val qwenFlag:  File get() = File(aiRuntimeDir, ".qwen-ready")
    private val piperFlag: File get() = File(aiRuntimeDir, ".piper-ready")
    val piperScript: File get() = File(aiRuntimeDir, "piper_synth.py")

    /** Path to the venv's python executable. */
    val venvPython: File get() =
        if (isWindows) File(venvDir, "Scripts/python.exe")
        else File(venvDir, "bin/python")

    /** Path to the base python (inside python-build-standalone). */
    private val basePython: File get() {
        if (isWindows) return File(pythonRoot, "python/python.exe")
        // python-build-standalone ships `python3` as a symlink to the real
        // versioned binary `python3.12`. If symlink materialisation failed
        // (or the FS doesn't support symlinks), fall back to the versioned
        // binary directly.
        val python3 = File(pythonRoot, "python/bin/python3")
        if (python3.exists()) return python3
        val versioned = File(pythonRoot, "python/bin/python3.12")
        if (versioned.exists()) return versioned
        return python3   // for the "still missing" diagnostic path
    }

    fun isInstalled(): Boolean =
        readyFlag.exists() && venvPython.exists() && serverPy.exists()

    /** Runs the full install, reporting progress via [onLog]. Safe to re-run. */
    suspend fun install(onLog: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            onLog("Neural TTS runtime already installed ✓")
            return@withContext
        }

        // 1. Portable Python
        if (!basePython.exists() || !basePython.canExecute()) {
            val url = pythonBuildStandaloneUrl()
            onLog("Downloading portable Python (~67 MB)…")
            AppLogger.info("python-build-standalone: $url")
            val archive = File(aiRuntimeDir, "python.tar.gz")
            if (archive.exists()) archive.delete()
            download(url, archive, onLog)
            val archiveSize = archive.length()
            AppLogger.info("Archive size on disk: ${archiveSize / 1024 / 1024} MB")
            // python-build-standalone install_only tarballs are ~14–30 MB
            // depending on triple. Anything below 8 MB means a hard truncation.
            if (archiveSize < 8L * 1024 * 1024) {
                archive.delete()
                error("Python download incomplete (${archiveSize / 1024} KB). " +
                      "Check your internet connection and retry.")
            }
            onLog("Extracting Python runtime…")
            if (pythonRoot.exists()) pythonRoot.deleteRecursively()
            pythonRoot.mkdirs()
            try {
                extractTarGz(archive, pythonRoot)
            } catch (e: Exception) {
                archive.delete()
                pythonRoot.deleteRecursively()
                error("Failed to extract Python tarball: ${e.message}")
            }
            archive.delete()
            if (!basePython.exists()) {
                val tree = pythonRoot.walkTopDown().take(20).joinToString("\n") {
                    "  ${it.relativeTo(pythonRoot)}"
                }
                error("Python extraction incomplete – missing ${basePython.relativeTo(pythonRoot)}.\n" +
                      "Found:\n$tree")
            }
            basePython.setExecutable(true)
        }

        // 2. venv
        if (!venvPython.exists()) {
            onLog("Creating Python virtual environment…")
            runOrFail(listOf(basePython.absolutePath, "-m", "venv", venvDir.absolutePath), onLog)
            if (!venvPython.exists()) error("venv python missing after `python -m venv`")
            venvPython.setExecutable(true)
        }

        // 3. pip install
        onLog("Upgrading pip/setuptools/wheel…")
        runOrFail(
            listOf(venvPython.absolutePath, "-m", "pip", "install", "-q",
                   "--upgrade", "pip", "setuptools", "wheel"),
            onLog
        )
        val reqFile = File(aiRuntimeDir, "requirements.txt")
        extractResource("/python_sidecar/requirements.txt", reqFile)

        onLog("Installing neural TTS packages (this may take a few minutes)…")
        runOrFail(
            listOf(venvPython.absolutePath, "-m", "pip", "install",
                   "--no-cache-dir", "-r", reqFile.absolutePath),
            onLog
        )

        // 4. server.py
        extractResource("/python_sidecar/server.py", serverPy)

        readyFlag.writeText("ok\n")
        onLog("Neural TTS runtime ready ✓")
    }

    fun isQwenInstalled(): Boolean = isInstalled() && qwenFlag.exists()

    /**
     * Installs the large Qwen3-TTS dependencies (torch, transformers, qwen-tts).
     * Kept out of the base runtime so Kokoro users do not download ~2 GB of torch.
     */
    suspend fun installQwenExtras(onLog: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!isInstalled()) install(onLog)
        if (qwenFlag.exists()) return@withContext
        val reqFile = File(aiRuntimeDir, "requirements-qwen.txt")
        extractResource("/python_sidecar/requirements-qwen.txt", reqFile)
        onLog("Installing Qwen3-TTS packages (torch, ~2 GB — this can take a while)…")
        runOrFail(
            listOf(venvPython.absolutePath, "-m", "pip", "install",
                   "--no-cache-dir", "-r", reqFile.absolutePath),
            onLog
        )
        qwenFlag.writeText("ok\n")
        onLog("Qwen3-TTS packages ready ✓")
    }

    fun isPiperInstalled(): Boolean = isInstalled() && piperFlag.exists() && piperScript.exists()

    /** Re-copies piper_synth.py so an install from an older app version gets the current helper. */
    fun refreshPiperScript() = runCatching { extractResource("/python_sidecar/piper_synth.py", piperScript) }

    /**
     * Installs the piper-tts package, which bundles espeak-ng — no Homebrew needed.
     * Piper then runs through [piperScript] with the venv's Python.
     */
    suspend fun installPiper(onLog: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!isInstalled()) install(onLog)
        extractResource("/python_sidecar/piper_synth.py", piperScript)
        if (piperFlag.exists()) return@withContext
        onLog("Installing Piper…")
        runOrFail(
            listOf(venvPython.absolutePath, "-m", "pip", "install", "--no-cache-dir", "piper-tts>=1.3"),
            onLog
        )
        piperFlag.writeText("ok\n")
        onLog("Piper ready ✓")
    }

    // ── Download URL selection ────────────────────────────────────────────
    // Pinned to a known-good python-build-standalone release (CPython 3.12.7).
    private fun pythonBuildStandaloneUrl(): String {
        val release = "20241008"
        val py      = "3.12.7"
        val triple = when {
            isMac && (osArch.contains("aarch64") || osArch.contains("arm")) ->
                "aarch64-apple-darwin"
            isMac ->
                "x86_64-apple-darwin"
            isWindows ->
                "x86_64-pc-windows-msvc-shared"
            osArch.contains("aarch64") || osArch.contains("arm") ->
                "aarch64-unknown-linux-gnu"
            else ->
                "x86_64-unknown-linux-gnu"
        }
        return "https://github.com/astral-sh/python-build-standalone/releases/download/$release/" +
               "cpython-$py+$release-$triple-install_only.tar.gz"
    }

    // ── HTTP download ──────────────────────────────────────────────────────
    // Plain JDK HttpURLConnection — Ktor/OkHttp was observed to silently end
    // the response stream at ~14 MB on macOS aarch64 (no exception, channel
    // simply marked closed). HttpURLConnection surfaces transport errors
    // reliably, and we wrap the whole thing in retry-with-backoff so a flaky
    // connection self-heals instead of bricking the install.
    private suspend fun download(
        url: String,
        dest: File,
        onLog: (String) -> Unit,
        maxAttempts: Int = 4,
    ) = withContext(Dispatchers.IO) {
        var lastErr: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                downloadOnce(url, dest, onLog, attempt)
                return@withContext
            } catch (e: Exception) {
                lastErr = e
                AppLogger.info("Download attempt $attempt/$maxAttempts failed: ${e.message}")
                if (dest.exists()) dest.delete()
                if (attempt < maxAttempts) {
                    val waitS = 1L shl (attempt - 1)   // 1, 2, 4, 8 s
                    onLog("Download failed (${e.message?.take(80)}); retrying in ${waitS}s…")
                    Thread.sleep(waitS * 1000)
                }
            }
        }
        throw lastErr ?: IllegalStateException("Download failed: no attempts completed")
    }

    /** Single download attempt. Manually follows up to 5 redirects. */
    private fun downloadOnce(url: String, dest: File, onLog: (String) -> Unit, attempt: Int) {
        var current = url
        for (hop in 0..5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout    = 600_000           // 10 min idle window
                requestMethod  = "GET"
                instanceFollowRedirects = false    // we handle redirects ourselves
                setRequestProperty("User-Agent", "narraframe")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) error("HTTP $code with no Location header")
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                continue
            }
            if (code !in 200..299) {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                conn.disconnect()
                error("HTTP $code downloading $current${if (errBody.isNotBlank()) ": ${errBody.take(200)}" else ""}")
            }

            val total   = conn.contentLengthLong
            val totalMb = if (total > 0) total / 1024 / 1024 else -1L
            var written = 0L
            var lastReported = 0L
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written - lastReported >= 5L * 1024 * 1024) {
                                val mb = written / 1024 / 1024
                                if (totalMb > 0) onLog("Downloaded $mb / $totalMb MB")
                                else             onLog("Downloaded $mb MB")
                                lastReported = written
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (total > 0 && written < total) {
                error("Download truncated: got $written / $total bytes from $current (attempt $attempt)")
            }
            val finalMb = written / 1024 / 1024
            if (totalMb > 0) onLog("Downloaded $finalMb / $totalMb MB ✓")
            else             onLog("Downloaded $finalMb MB ✓")
            return
        }
        error("Too many redirects starting from $url")
    }

    // ── Minimal tar.gz extractor ──────────────────────────────────────────
    // Supports USTAR + GNU long-name (type 'L') entries which
    // python-build-standalone tarballs rely on. No external tar dependency.
    private fun extractTarGz(archive: File, targetDir: File) {
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            extractTar(gz, targetDir)
        }
    }

    private fun extractTar(input: InputStream, targetDir: File) {
        val header = ByteArray(512)
        var pendingLongName: String? = null

        while (true) {
            val read = readFully(input, header, 0, 512)
            if (read < 512) break
            if (header.all { it == 0.toByte() }) break   // end of archive

            val nameField = cstr(header, 0, 100)
            val prefix    = cstr(header, 345, 155)
            val linkName  = cstr(header, 157, 100)
            val sizeOct   = cstr(header, 124, 12).trim()
            val modeOct   = cstr(header, 100, 8).trim()
            val typeFlag  = header[156].toInt().toChar()
            val size      = if (sizeOct.isEmpty()) 0L else sizeOct.toLong(8)
            val mode      = if (modeOct.isEmpty()) 0 else modeOct.toInt(8)

            val rawName = when {
                pendingLongName != null -> pendingLongName!!.also { pendingLongName = null }
                prefix.isNotEmpty()     -> "$prefix/$nameField"
                else                    -> nameField
            }

            if (typeFlag == 'L') {
                // GNU long-name record — the next entry's name is stored here
                val buf = ByteArray(size.toInt())
                readFully(input, buf, 0, buf.size)
                pendingLongName = String(buf).trimEnd('\u0000')
                skipPadding(input, size)
                continue
            }

            val outFile = File(targetDir, rawName)
            when (typeFlag) {
                '5' -> {
                    outFile.mkdirs()
                }
                '2' -> {
                    // Symlink – materialise it. python-build-standalone ships
                    // `bin/python3 -> python3.12` as a symlink, and venv
                    // creation expects `python3` to resolve.
                    if (!isWindows && linkName.isNotEmpty()) {
                        outFile.parentFile?.mkdirs()
                        try {
                            if (outFile.exists()) outFile.delete()
                            Files.createSymbolicLink(
                                outFile.toPath(),
                                Paths.get(linkName)
                            )
                        } catch (e: Exception) {
                            // Filesystem may not support symlinks (e.g. some
                            // FAT/exFAT mounts on macOS). Fall back to copying
                            // the target file if it already lives in the same
                            // directory; otherwise leave it absent — basePython
                            // will fall back to the versioned binary.
                            val targetFile = File(outFile.parentFile, linkName)
                            if (targetFile.exists() && targetFile.isFile) {
                                runCatching { targetFile.copyTo(outFile, overwrite = true) }
                            }
                        }
                    }
                    // No payload bytes follow a symlink entry.
                }
                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var remaining = size
                        val buf = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n <= 0) error("Truncated tar entry: $rawName")
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    if (!isWindows && (mode and 0b001_001_001) != 0) {
                        outFile.setExecutable(true, false)
                    }
                }
                // Anything else (GNU/PAX extended headers etc.) — consume payload and skip
                else -> {
                    var remaining = size
                    val buf = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n <= 0) break
                        remaining -= n
                    }
                }
            }
            skipPadding(input, size)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val remainder = (size % 512).toInt()
        if (remainder != 0) {
            val pad = ByteArray(512 - remainder)
            readFully(input, pad, 0, pad.size)
        }
    }

    private fun cstr(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.US_ASCII)
    }

    // ── Misc ───────────────────────────────────────────────────────────────
    private fun extractResource(resourcePath: String, dest: File) {
        val input = PythonRuntimeInstaller::class.java.getResourceAsStream(resourcePath)
            ?: error("Bundled resource missing: $resourcePath")
        dest.parentFile?.mkdirs()
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    /** Runs [cmd]; cancelling the calling coroutine kills the process (e.g. a long pip install). */
    private suspend fun runOrFail(cmd: List<String>, onLog: (String) -> Unit) {
        AppLogger.info("[python] $ ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["PYTHONUNBUFFERED"] = "1"
        val proc = pb.start()
        proc.outputStream.close()
        val killOnCancel = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) proc.descendants().forEach { it.destroyForcibly() }.also { proc.destroyForcibly() }
        }
        val reader = proc.inputStream.bufferedReader()
        val tail = ArrayDeque<String>()
        try { reader.useLines { lines ->
            lines.forEach { line ->
                AppLogger.debug("[python] $line")
                tail.addLast(line)
                if (tail.size > 40) tail.removeFirst()
                if (line.contains("Collecting") || line.contains("Installing") ||
                    line.contains("Downloading") || line.contains("Building wheel")) {
                    onLog(line.trim().take(80))
                }
            }
        } } catch (e: java.io.IOException) {
            // Stream closed because the process was killed on cancel
        } finally {
            killOnCancel?.dispose()
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        val code = proc.waitFor()
        if (code != 0) {
            error("Command failed (exit $code): ${cmd.joinToString(" ")}\n${tail.joinToString("\n")}")
        }
    }
}
