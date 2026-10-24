package com.narraframe.platform

import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences

/**
 * One-time migration from the app's former name (Story2Video Studio).
 *
 *  - Moves ~/.story2video-studio (downloaded binaries, models, AI runtime, logs)
 *    to ~/.narraframe and leaves a symlink at the old path, so absolute paths
 *    baked into the Python venv or saved in settings keep resolving.
 *  - Copies saved settings (API keys, provider choices) from the old JVM
 *    Preferences node to the new one.
 *
 * Must run before anything touches ~/.narraframe or the Preferences node.
 * Safe to call on every launch: each step is skipped once done.
 */
object LegacyDataMigration {

    private const val OLD_DIR_NAME   = ".story2video-studio"
    private const val NEW_DIR_NAME   = ".narraframe"
    private const val OLD_PREFS_PATH = "com/story2video/studio"
    private const val NEW_PREFS_PATH = "com/narraframe"

    fun run() {
        runCatching { migrateDataDir() }
            .onFailure { System.err.println("Legacy data dir migration failed: ${it.message}") }
        runCatching { migratePreferences() }
            .onFailure { System.err.println("Legacy settings migration failed: ${it.message}") }
    }

    private fun migrateDataDir() {
        val home   = File(System.getProperty("user.home"))
        val oldDir = File(home, OLD_DIR_NAME)
        val newDir = File(home, NEW_DIR_NAME)
        if (newDir.exists() || !oldDir.exists() || Files.isSymbolicLink(oldDir.toPath())) return

        Files.move(oldDir.toPath(), newDir.toPath())
        // Symlinks may need extra privileges on Windows; the move alone is enough there.
        runCatching { Files.createSymbolicLink(oldDir.toPath(), newDir.toPath()) }
    }

    private fun migratePreferences() {
        val root = Preferences.userRoot()
        if (!root.nodeExists(OLD_PREFS_PATH)) return
        val oldNode = root.node(OLD_PREFS_PATH)
        val newNode = root.node(NEW_PREFS_PATH)
        if (newNode.keys().isNotEmpty()) return

        for (key in oldNode.keys()) {
            oldNode.get(key, null)?.let { newNode.put(key, it) }
        }
        newNode.flush()
    }
}
