package com.narraframe.platform

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipInputStream

/** GitHub release lookup and file download shared by the engine downloaders. */
internal object ReleaseAssets {

    @Serializable
    data class Release(val tag_name: String = "", val draft: Boolean = false, val assets: List<Asset>)

    @Serializable
    data class Asset(val name: String, val browser_download_url: String, val size: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }

    /** Recent non-draft releases of [repo] ("owner/name"), newest first. */
    suspend fun releases(repo: String, count: Int = 30): List<Release> {
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 30_000 }
        }
        try {
            val resp: HttpResponse = client.get("https://api.github.com/repos/$repo/releases?per_page=$count") {
                header("Accept", "application/vnd.github+json")
            }
            if (!resp.status.isSuccess()) error("GitHub API error: ${resp.status}")
            return json.decodeFromString<List<Release>>(resp.body()).filter { !it.draft }
        } finally {
            client.close()
        }
    }

    /** Downloads [url] to [dest], reporting "NN%  –  X MB / Y MB". Cancellable. */
    suspend fun download(url: String, dest: File, label: String, onLog: (String) -> Unit) {
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 3_600_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis  = 120_000
            }
        }
        try {
            client.prepareGet(url).execute { resp ->
                if (!resp.status.isSuccess()) error("Download failed: ${resp.status}")
                val total = resp.contentLength() ?: -1L
                var done = 0L
                var lastPct = -1
                val channel = resp.bodyAsChannel()
                dest.outputStream().buffered().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (!channel.isClosedForRead) {
                        currentCoroutineContext().ensureActive()
                        val n = channel.readAvailable(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onLog("$pct%  –  ${done / 1_048_576} MB / ${total / 1_048_576} MB  ($label)")
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        } finally {
            client.close()
        }
    }

    fun isLib(name: String) =
        name.endsWith(".dylib") || name.endsWith(".dll") || name.endsWith(".so") || name.contains(".so.")

    /**
     * Extracts a .zip or .tar.gz flat into [destDir], keeping only files for which
     * [rename] returns a name (null = skip). Returns the names written.
     */
    fun extractFlat(archive: File, destDir: File, rename: (String) -> String?): List<String> {
        val written = mutableListOf<String>()
        fun save(name: String, write: (File) -> Unit) {
            val target = rename(name) ?: return
            val dest = File(destDir, target)
            write(dest)
            if (!target.contains('.') || target.endsWith(".exe")) dest.setExecutable(true)
            written += target
        }
        if (archive.name.endsWith(".zip")) {
            ZipInputStream(archive.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        save(File(entry.name).name) { dest -> dest.outputStream().buffered().use { zis.copyTo(it) } }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } else {
            // tar keeps library symlinks; unpack with the system tar and copy files with links resolved
            val staging = File(destDir, ".extract_${System.currentTimeMillis()}").also { it.mkdirs() }
            try {
                val p = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", staging.absolutePath)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                if (p.waitFor() != 0) error("tar failed: ${out.take(300)}")
                staging.walkTopDown().filter { it.isFile }.forEach { f ->
                    save(f.name) { dest -> f.inputStream().use { i -> dest.outputStream().use { i.copyTo(it) } } }
                }
            } finally {
                staging.deleteRecursively()
            }
        }
        return written
    }
}
