package com.narraframe.platform

import com.narraframe.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Thrown when a child process stops producing output for longer than its stall limit. */
class ProcessStalledException(message: String) : Exception(message)

/**
 * Runs the app's child processes (llama.cpp, sd.cpp, Piper, FFmpeg, OS voices) so that
 *  - cancelling the calling coroutine kills the process and its children,
 *  - a process that prints nothing for [run]'s `stallMs` is treated as hung and killed,
 *  - every process still running when the app quits is killed.
 */
internal object Processes {

    private val live = ConcurrentHashMap.newKeySet<Process>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { live.toList().forEach(::kill) })
    }

    fun kill(p: Process) {
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
        p.destroyForcibly()
    }

    fun track(p: Process): Process = p.also { live += it; it.onExit().thenRun { live -= it } }

    data class Result(val exitCode: Int, val stdout: String, val log: String)

    /**
     * Starts [cmd] and waits for it.
     *
     * @param stdin     text written to the process's stdin, then closed
     * @param mergeErr  true: stderr is merged into stdout. false: stdout is kept apart
     *                  (e.g. llama.cpp's answer) and stderr only feeds the log.
     * @param stallMs   kill the process when neither stream produced output for this long
     * @param onLine    every output line (stderr lines too), for progress parsing
     */
    suspend fun run(
        cmd:      List<String>,
        stdin:    String? = null,
        workDir:  File? = null,
        env:      Map<String, String> = emptyMap(),
        mergeErr: Boolean = true,
        stallMs:  Long = 5 * 60_000L,
        onLine:   (String) -> Unit = {}
    ): Result {
        val pb = ProcessBuilder(cmd).redirectErrorStream(mergeErr)
        workDir?.let { pb.directory(it) }
        pb.environment().putAll(env)
        val proc = track(pb.start())

        val activity = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val stdout = StringBuilder()
        val log = ArrayDeque<String>()

        fun pump(stream: java.io.InputStream, keep: Boolean) = Thread {
            runCatching {
                if (keep) {
                    // Character-wise so token streams count as activity before a newline
                    val reader = stream.bufferedReader()
                    val line = StringBuilder()
                    val buf = CharArray(1024)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        activity.set(System.currentTimeMillis())
                        synchronized(stdout) { stdout.append(buf, 0, n) }
                        for (i in 0 until n) {
                            val c = buf[i]
                            if (c == '\n' || c == '\r') { if (line.isNotEmpty()) { onLine(line.toString()); line.clear() } }
                            else line.append(c)
                        }
                    }
                    if (line.isNotEmpty()) onLine(line.toString())
                } else {
                    stream.bufferedReader().forEachLine { l ->
                        activity.set(System.currentTimeMillis())
                        synchronized(log) { log.addLast(l); if (log.size > 60) log.removeFirst() }
                        onLine(l)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val outThread = pump(proc.inputStream, keep = true)
        val errThread = if (mergeErr) null else pump(proc.errorStream, keep = false)

        try {
            proc.outputStream.use { os -> if (stdin != null) os.write(stdin.toByteArray()) }
        } catch (_: java.io.IOException) { /* process exited early; reported below */ }

        try {
            while (proc.isAlive) {
                coroutineContext.ensureActive()
                if (System.currentTimeMillis() - activity.get() > stallMs) {
                    kill(proc)
                    throw ProcessStalledException(
                        "${File(cmd.first()).name} stopped responding (no output for ${stallMs / 60_000} min)"
                    )
                }
                delay(200)
            }
        } catch (e: CancellationException) {
            kill(proc)
            throw e
        }
        outThread.join(2_000)
        errThread?.join(2_000)

        val out = synchronized(stdout) { stdout.toString() }
        val logText = if (mergeErr) out.takeLast(4_000) else synchronized(log) { log.joinToString("\n") }
        return Result(proc.exitValue(), out, logText)
    }

    /** Like [run] but throws with the tail of the log when the exit code is not 0. */
    suspend fun runOrThrow(
        cmd:      List<String>,
        what:     String,
        stdin:    String? = null,
        workDir:  File? = null,
        env:      Map<String, String> = emptyMap(),
        mergeErr: Boolean = true,
        stallMs:  Long = 5 * 60_000L,
        onLine:   (String) -> Unit = {}
    ): Result {
        val r = run(cmd, stdin, workDir, env, mergeErr, stallMs, onLine)
        if (r.exitCode != 0) {
            AppLogger.error("$what exited ${r.exitCode}\n${r.log.takeLast(1500)}")
            throw Exception("$what failed (exit ${r.exitCode}): ${r.log.takeLast(300)}")
        }
        return r
    }
}
