package com.narraframe.platform

import com.narraframe.util.AppLogger
import java.io.File
import java.util.prefs.Preferences

/**
 * Where Narraframe keeps downloaded engines, models and the Python voice runtime.
 *
 * Defaults to ~/.narraframe; the user can pick another folder (e.g. an external drive)
 * during setup. Logs always stay in ~/.narraframe/logs so they exist even when the
 * chosen drive is unplugged.
 */
internal object DataDir {

    private const val KEY = "data_dir"
    private val prefs = Preferences.userRoot().node("com/narraframe")

    val defaultRoot: File = File(System.getProperty("user.home"), ".narraframe")

    val logsDir: File get() = File(defaultRoot, "logs").also { it.mkdirs() }

    val root: File get() = prefs.get(KEY, null)?.let(::File) ?: defaultRoot

    /**
     * False when the chosen folder's drive is not connected. In that case nothing may
     * create folders there, or they would silently land on the system disk.
     */
    val isAvailable: Boolean get() = root == defaultRoot || root.parentFile?.isDirectory == true

    /** Sub-folder of [root], created on first use while the drive is available. */
    fun dir(name: String): File = File(root, name).also { if (isAvailable) it.mkdirs() }

    val bin: File get() = dir("bin")
    val models: File get() = dir("models")
    val aiRuntime: File get() = dir("ai-runtime")

    /** True when the current folder already holds engines or models worth moving. */
    fun hasContent(): Boolean =
        listOf("bin", "models", "ai-runtime").any { File(root, it).listFiles()?.isNotEmpty() == true }

    /**
     * Points the data folder at [newRoot]. With [moveExisting], copies bin/, models/ and
     * ai-runtime/ there first and deletes the originals. The Python venv is rebuilt on
     * next use because it stores absolute paths.
     */
    fun change(newRoot: File, moveExisting: Boolean, onProgress: (String) -> Unit) {
        val old = root
        if (newRoot.absoluteFile == old.absoluteFile) return
        newRoot.mkdirs()
        if (!newRoot.canWrite()) error("Cannot write to ${newRoot.absolutePath}")

        if (moveExisting && old.isDirectory) {
            val parts = listOf("bin", "models", "ai-runtime").map { File(old, it) }.filter { it.exists() }
            val total = parts.sumOf { p -> p.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.coerceAtLeast(1)
            var copied = 0L
            for (part in parts) {
                val target = File(newRoot, part.name)
                part.walkTopDown().forEach { src ->
                    val dest = File(target, src.relativeTo(part).path)
                    if (src.isDirectory) dest.mkdirs()
                    else {
                        src.copyTo(dest, overwrite = true)
                        if (src.canExecute()) dest.setExecutable(true)
                        copied += src.length()
                        onProgress("${(copied * 100 / total).toInt()}%  –  ${copied / 1_048_576} MB / ${total / 1_048_576} MB")
                    }
                }
            }
            // venv scripts embed absolute paths; force a rebuild in the new location
            File(newRoot, "ai-runtime/venv").deleteRecursively()
            listOf(".runtime-ready", ".qwen-ready", ".piper-ready").forEach { File(newRoot, "ai-runtime/$it").delete() }
            parts.forEach { it.deleteRecursively() }
        }

        if (newRoot.absoluteFile == defaultRoot.absoluteFile) prefs.remove(KEY) else prefs.put(KEY, newRoot.absolutePath)
        prefs.flush()
        AppLogger.success("Engines and models folder → ${newRoot.absolutePath}")
    }
}
