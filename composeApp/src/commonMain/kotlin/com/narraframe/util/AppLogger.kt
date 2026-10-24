package com.narraframe.util

import com.narraframe.models.GenerationStep
import com.narraframe.models.LogEntry

/**
 * Singleton logger.
 *
 * Handlers:
 *   - persistentHandlers : survive across sessions (e.g. file logger, set at app start)
 *   - sessionHandler     : replaced each pipeline run to fan logs into the UI panel
 *
 * Console output includes ANSI colours and is always active.
 */
object AppLogger {

    // ── Handlers ───────────────────────────────────────────────────────────

    private val persistentHandlers = mutableListOf<(LogEntry) -> Unit>()
    private var sessionHandler: ((LogEntry) -> Unit)? = null

    /** Add a handler that survives across pipeline sessions (e.g. log-file writer). */
    fun addPersistentHandler(h: (LogEntry) -> Unit) { persistentHandlers.add(h) }

    /** Replace the per-session handler (typically the UI panel callback). */
    fun setHandler(h: (LogEntry) -> Unit) { sessionHandler = h }

    fun clearHandler() { sessionHandler = null }

    // ── Emit ───────────────────────────────────────────────────────────────

    private fun emit(level: LogEntry.Level, step: GenerationStep?, message: String) {
        val entry = LogEntry(level = level, step = step, message = message)

        // DEBUG messages go to console only; keep the UI log panel concise
        if (level != LogEntry.Level.DEBUG) sessionHandler?.invoke(entry)
        persistentHandlers.forEach { it(entry) }

        // ── Coloured console output ────────────────────────────────────────
        val color = when (level) {
            LogEntry.Level.DEBUG   -> ANSI_DARK_GRAY
            LogEntry.Level.INFO    -> ANSI_CYAN
            LogEntry.Level.WARNING -> ANSI_YELLOW
            LogEntry.Level.ERROR   -> ANSI_RED
            LogEntry.Level.SUCCESS -> ANSI_GREEN
        }
        val tag     = "[${level.name.padEnd(7)}]"
        val stepStr = step?.let { " [${it.displayName}]" } ?: ""
        println("$color[${entry.formattedTime}] $tag$stepStr $message$ANSI_RESET")
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun debug  (msg: String, step: GenerationStep? = null) = emit(LogEntry.Level.DEBUG,   step, msg)
    fun info   (msg: String, step: GenerationStep? = null) = emit(LogEntry.Level.INFO,    step, msg)
    fun warning(msg: String, step: GenerationStep? = null) = emit(LogEntry.Level.WARNING, step, msg)
    fun error  (msg: String, step: GenerationStep? = null) = emit(LogEntry.Level.ERROR,   step, msg)
    fun success(msg: String, step: GenerationStep? = null) = emit(LogEntry.Level.SUCCESS, step, msg)

    // ── ANSI escape codes ──────────────────────────────────────────────────

    private const val ANSI_RESET     = "\u001B[0m"
    private const val ANSI_DARK_GRAY = "\u001B[90m"
    private const val ANSI_CYAN      = "\u001B[36m"
    private const val ANSI_YELLOW    = "\u001B[33m"
    private const val ANSI_RED       = "\u001B[31m"
    private const val ANSI_GREEN     = "\u001B[32m"
}
