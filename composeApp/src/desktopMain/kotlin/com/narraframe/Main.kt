package com.narraframe

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.narraframe.models.LogEntry
import com.narraframe.platform.LegacyDataMigration
import com.narraframe.ui.App
import com.narraframe.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

fun main() {
    // Move data and settings saved under the old app name before anything reads them.
    LegacyDataMigration.run()
    System.getProperty("narraframe.socialPreview")?.takeIf { it.isNotBlank() }?.let {
        kotlin.system.exitProcess(renderSocialPreview(it, System.getProperty("narraframe.socialShot").orEmpty()))
    }
    System.getProperty("narraframe.screenshots")?.takeIf { it.isNotBlank() }?.let {
        kotlin.system.exitProcess(renderScreenshots(it))
    }
    System.getProperty("narraframe.headlessScript")?.takeIf { it.isNotBlank() }?.let {
        initFileLogging()
        kotlin.system.exitProcess(runHeadless(it))
    }
    runApp()
}

private fun runApp() = application {
    // ── File logging ────────────────────────────────────────────────────────
    // Logs are written to ~/.narraframe/logs/app-YYYY-MM-DD.log
    // Tail the file in a separate terminal: tail -f ~/.narraframe/logs/app-$(date +%F).log
    initFileLogging()

    // ── Global uncaught exception handler ───────────────────────────────────
    // Catches any exception that escapes to the JVM top level (AWT EDT crashes, etc.)
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        AppLogger.error(
            "UNCAUGHT EXCEPTION on thread '${thread.name}': ${throwable.message}\n" +
            throwable.stackTraceToString()
        )
    }

    AppLogger.info("──────────────────────────────────────────")
    AppLogger.info("Narraframe starting up")
    AppLogger.info("Log file: ${logFilePath()}")
    AppLogger.info("JVM: ${System.getProperty("java.version")}  OS: ${System.getProperty("os.name")}")
    AppLogger.info("Debug port: ${System.getProperty("debug.port") ?: "not enabled"}")
    AppLogger.info("──────────────────────────────────────────")

    Window(
        onCloseRequest = ::exitApplication,
        title          = "Narraframe",
        state          = rememberWindowState(width = 1200.dp, height = 780.dp)
    ) {
        App()
    }
}

// ── Log file helpers ────────────────────────────────────────────────────────

private val logDir: File by lazy {
    File(System.getProperty("user.home"), ".narraframe/logs").also { it.mkdirs() }
}

private fun logFilePath(): String {
    val date = SimpleDateFormat("yyyy-MM-dd").format(Date())
    return File(logDir, "app-$date.log").absolutePath
}

private fun initFileLogging() {
    val logFile = File(logFilePath())
    val writer  = logFile.printWriter(Charsets.UTF_8)

    // Write a session separator so multiple runs in one day are clearly delimited
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
    writer.println()
    writer.println("════════════════════════════════════════════")
    writer.println("  Session started  $ts")
    writer.println("════════════════════════════════════════════")
    writer.flush()

    AppLogger.addPersistentHandler { entry ->
        val stepStr = entry.step?.let { " [${it.displayName}]" } ?: ""
        val tag     = entry.level.name.padEnd(7)
        writer.println("[${entry.formattedTime}] [$tag]$stepStr ${entry.message}")
        writer.flush()
    }

    // Flush and close when the JVM exits
    Runtime.getRuntime().addShutdownHook(Thread {
        writer.println()
        writer.println("── Session ended ──────────────────────────")
        writer.flush()
        writer.close()
    })
}
