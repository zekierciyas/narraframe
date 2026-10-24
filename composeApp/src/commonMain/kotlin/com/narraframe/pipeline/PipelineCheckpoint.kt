package com.narraframe.pipeline

import com.narraframe.platform.readProjectText
import com.narraframe.platform.saveProjectText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persisted state of a running or failed generation pipeline.
 *
 * Saved to  {projectPath}/pipeline_checkpoint.json  after every step.
 * Used to:
 *  • Resume a failed run (in-session Retry or cross-session Resume)
 *  • Show the user where a previous run stopped
 *
 * status: "running" | "completed" | "failed"
 */
@Serializable
data class PipelineCheckpoint(
    val script:            String,
    val imageInterval:     Double,
    val imageCount:        Int    = 0,
    val status:            String = "running",
    val lastCompletedStep: String = "",
    val failedStep:        String = "",
    val errorMessage:      String = "",
    val timestamp:         Long   = 0L
) {
    /** True when the pipeline failed mid-run and has a script to resume from. */
    val isResumable: Boolean
        get() = status == "failed" && script.isNotBlank()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val FILE = "pipeline_checkpoint.json"

        fun save(projectPath: String, cp: PipelineCheckpoint) {
            try { saveProjectText(projectPath, "", FILE, json.encodeToString(cp)) }
            catch (_: Exception) { /* best-effort — never crash pipeline on checkpoint save */ }
        }

        fun load(projectPath: String): PipelineCheckpoint? {
            val text = readProjectText(projectPath, "", FILE) ?: return null
            return try { json.decodeFromString(text) } catch (_: Exception) { null }
        }

        /** Mark the checkpoint as completed (clears resumable state). */
        fun markCompleted(projectPath: String, cp: PipelineCheckpoint) =
            save(projectPath, cp.copy(status = "completed", failedStep = "", errorMessage = ""))
    }
}
