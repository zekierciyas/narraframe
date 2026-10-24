package com.narraframe.library

import com.narraframe.models.Generation
import com.narraframe.models.GenerationMeta
import com.narraframe.platform.ensureProjectDirs
import com.narraframe.platform.listDirectories
import com.narraframe.platform.readProjectText
import com.narraframe.platform.saveProjectText
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date

/** Reads and writes generation folders inside the library folder. */
object Library {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private const val META = "meta.json"

    /** Creates `<library>/<yyyyMMdd-HHmmss>-<slug>/` with its sub-folders and meta.json. */
    fun create(libraryPath: String, meta: GenerationMeta): Generation {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date(meta.createdAt))
        val path  = "${libraryPath.trimEnd('/', '\\')}/$stamp-${slug(meta.title)}"
        ensureProjectDirs(path)
        return Generation(path, meta).also { save(it) }
    }

    fun save(generation: Generation) {
        saveProjectText(generation.path, "", META, json.encodeToString(GenerationMeta.serializer(), generation.meta))
    }

    fun load(path: String): Generation? {
        val text = readProjectText(path, "", META) ?: return null
        return runCatching { Generation(path, json.decodeFromString(GenerationMeta.serializer(), text)) }.getOrNull()
    }

    /** All generations in [libraryPath], newest first. Folders without meta.json are skipped. */
    fun list(libraryPath: String): List<Generation> =
        if (libraryPath.isBlank()) emptyList()
        else listDirectories(libraryPath).mapNotNull { load(it) }.sortedByDescending { it.meta.createdAt }

    /** Title for a script: its first sentence, cut to about 48 characters. */
    fun titleFor(script: String): String {
        val first = script.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            .split(Regex("(?<=[.!?])\\s")).first().trim()
        return when {
            first.isEmpty()   -> "Untitled"
            first.length > 48 -> first.take(48).substringBeforeLast(' ').trimEnd(',', ';', ':') + "…"
            else              -> first.trimEnd('.')
        }
    }

    private fun slug(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
            .trimEnd('-')
            .ifEmpty { "video" }
}
