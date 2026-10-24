package com.narraframe.platform

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Downloads and caches the FFmpeg binary at startup so users never need to
 * install it manually.
 *
 * Cache location: <data dir>/bin/ffmpeg  (ffmpeg.exe on Windows)
 *
 * All sources are shaka-project/static-ffmpeg-binaries (Google's media team):
 *   macOS arm64  → ffmpeg-osx-arm64  (raw binary)
 *   macOS x86_64 → ffmpeg-osx-x64    (raw binary)
 *   Linux x64    → ffmpeg-linux-x64  (raw binary)
 *   Windows x64  → BtbN zip          (zip, extracted via ZipInputStream)
 *
 * Raw-binary platforms skip extraction entirely — download, write, chmod, done.
 * The HTTP response status is checked before any bytes are written to disk so a
 * server-side error page never ends up treated as a binary.
 */
internal object FfmpegDownloader {

    private val os   = System.getProperty("os.name").lowercase()
    private val arch = System.getProperty("os.arch").lowercase()

    val isWindows: Boolean get() = os.contains("win")
    val isMac:     Boolean get() = os.contains("mac")

    // FFmpeg is needed before anything else (even the setup wizard), so when the chosen
    // engines drive is not connected it falls back to the default folder on the system disk.
    val cacheDir: File get() =
        if (DataDir.isAvailable) DataDir.bin else File(DataDir.defaultRoot, "bin").also { it.mkdirs() }

    val binary: File get() = File(cacheDir, if (isWindows) "ffmpeg.exe" else "ffmpeg")

    /** Returns the cached binary path if it already exists and is executable. */
    fun getCachedPath(): String? =
        binary.takeIf { it.exists() && it.canExecute() }?.absolutePath

    /**
     * Downloads the appropriate FFmpeg binary, saves it to [cacheDir],
     * makes it executable, and returns its path.
     */
    suspend fun download(onLog: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val url   = downloadUrl()
        val isZip = url.endsWith(".zip")

        onLog("FFmpeg not found – downloading (one-time setup)…")
        onLog("Source: $url")

        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 600_000   // 10 min — binaries can be 40–130 MB
                connectTimeoutMillis =  30_000
                socketTimeoutMillis  = 120_000
            }
        }

        val bytes: ByteArray
        try {
            val response: HttpResponse = client.get(url)
            // Validate HTTP status BEFORE treating the body as a binary.
            // Without this check an error HTML/JSON page gets written to disk and
            // the next run tries to execute it, producing "line 1: syntax error".
            if (!response.status.isSuccess()) {
                error("HTTP ${response.status.value} (${response.status.description}) downloading FFmpeg from $url")
            }
            bytes = response.body()
            val mb = bytes.size / 1024 / 1024
            onLog("Downloaded $mb MB – ${if (isZip) "extracting…" else "saving…"}")
        } finally {
            client.close()
        }

        if (isZip) {
            val tmp = File(cacheDir, "ffmpeg_download.zip")
            tmp.writeBytes(bytes)
            try {
                extractFromZip(tmp)
            } finally {
                tmp.delete()
            }
        } else {
            // Direct binary (shaka-project) — write straight to cache path, no extraction
            binary.writeBytes(bytes)
        }

        if (!binary.exists()) error("FFmpeg binary not found in $cacheDir after download")
        binary.setExecutable(true)
        onLog("FFmpeg ready → ${binary.absolutePath}")
        binary.absolutePath
    }

    // ── Download URLs ─────────────────────────────────────────────────────────

    private fun downloadUrl(): String = when {
        isMac && (arch.contains("aarch64") || arch.contains("arm")) ->
            // Apple Silicon – shaka-project direct binary (~90 MB)
            "https://github.com/shaka-project/static-ffmpeg-binaries/releases/latest/download/ffmpeg-osx-arm64"

        isMac ->
            // Intel Mac – shaka-project direct binary (~90 MB)
            "https://github.com/shaka-project/static-ffmpeg-binaries/releases/latest/download/ffmpeg-osx-x64"

        isWindows ->
            // Windows x64 – BtbN GPL static zip (~120 MB, contains bin/ffmpeg.exe)
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"

        else ->
            // Linux x64 – shaka-project direct binary (~90 MB)
            "https://github.com/shaka-project/static-ffmpeg-binaries/releases/latest/download/ffmpeg-linux-x64"
    }

    // ── Zip extraction (Windows only) ─────────────────────────────────────────

    /**
     * Scans the zip for an entry whose bare filename is `ffmpeg` or `ffmpeg.exe`
     * and writes it to [binary]. Handles both flat zips and nested ones like BtbN
     * (…/bin/ffmpeg.exe).
     */
    private fun extractFromZip(zip: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = File(entry.name).name          // strip directory components
                if (name == "ffmpeg" || name == "ffmpeg.exe") {
                    binary.outputStream().buffered().use { zis.copyTo(it) }
                    return
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        error("ffmpeg binary not found inside ${zip.name}")
    }
}
