package com.narraframe.platform

import com.narraframe.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads and caches the llama.cpp binary used for local text generation.
 *
 * Stored at <data dir>/bin/llama-completion (.exe on Windows) with its shared libraries.
 * Newer llama.cpp builds turned `llama-cli` into an interactive chat; `llama-completion`
 * is the one-shot tool.
 *
 * The build matches [Hardware.buildVariant] (Metal / CUDA / Vulkan / CPU) and is
 * recorded in .llama_variant. ggml-org/llama.cpp publishes binaries on build
 * pre-releases (e.g. "b11840"), so recent releases are scanned.
 */
internal object LocalLLMDownloader {

    private val os   = System.getProperty("os.name").lowercase()
    private val arch = System.getProperty("os.arch").lowercase()

    private val isWindows: Boolean get() = os.contains("win")
    private val isMac:     Boolean get() = os.contains("mac")
    private val isArm:     Boolean get() = arch.contains("aarch64") || arch.contains("arm")

    private val binDir: File get() = DataDir.bin

    private val exe = if (isWindows) ".exe" else ""

    val binary: File get() = File(binDir, "llama-completion$exe")

    private val rpathMarker: File get() = File(binDir, ".llama_rpath_patched")
    private val variantMarker: File get() = File(binDir, ".llama_variant")

    /** Variant on disk; installs from before variants existed were CPU builds (Metal on macOS). */
    val installedVariant: Accel? get() = when {
        !binary.exists() -> null
        variantMarker.exists() -> runCatching { Accel.valueOf(variantMarker.readText().trim()) }.getOrNull()
        else -> if (isMac) Accel.METAL else Accel.CPU
    }

    /** Path to llama-completion when the preferred build is installed, else null. */
    fun getCachedPath(): String? {
        val bin = binary.takeIf { it.exists() && it.canExecute() } ?: return null
        if (installedVariant != Hardware.buildVariant) return null
        if (isMac && !rpathMarker.exists()) {
            patchMacRpaths(bin) { AppLogger.info(it) }
            runCatching { rpathMarker.writeText("v1") }
        }
        return bin.absolutePath
    }

    suspend fun download(onLog: (String) -> Unit): String = withContext(Dispatchers.IO) {
        onLog("Looking for the latest llama.cpp build…")
        val wanted = Hardware.buildVariant
        val releases = ReleaseAssets.releases("ggml-org/llama.cpp", 30)

        // Asset names, e.g. llama-b11840-bin-macos-arm64.tar.gz, …-bin-ubuntu-x64.tar.gz,
        // …-bin-ubuntu-vulkan-x64.tar.gz, …-bin-ubuntu-cuda-12.8-x64.tar.gz,
        // …-bin-win-cpu-x64.zip, …-bin-win-vulkan-x64.zip, …-bin-win-cuda-12.4-x64.zip,
        // plus cudart-llama-…-cuda-12.x-x64 archives with the CUDA runtime.
        val cpuArch = if (isArm) "arm64" else "x64"
        fun main(n: String, v: Accel): Boolean =
            (n.endsWith(".zip") || n.endsWith(".tar.gz")) && !n.startsWith("cudart") && when {
                isMac -> n.contains("-bin-macos-$cpuArch")
                isWindows -> when (v) {
                    Accel.CUDA   -> n.contains("-bin-win-cuda-12.4-x64")
                    Accel.VULKAN -> n.contains("-bin-win-vulkan-x64")
                    else         -> n.contains("-bin-win-cpu-x64") || n.contains("-bin-win-avx2-x64")
                }
                else -> when (v) {
                    Accel.CUDA   -> n.contains("-bin-ubuntu-cuda-12.8-x64")
                    Accel.VULKAN -> n.contains("-bin-ubuntu-vulkan-$cpuArch")
                    else         -> n.contains("-bin-ubuntu-$cpuArch")
                }
            }
        fun cudart(n: String) = n.startsWith("cudart-llama") &&
            (if (isWindows) n.contains("win-cuda-12.4-x64") else n.contains("ubuntu-cuda-12.8-x64"))

        val order = listOf(wanted, Accel.CPU).distinct()
        var picked: Pair<ReleaseAssets.Asset, Accel>? = null
        var runtime: ReleaseAssets.Asset? = null
        outer@ for (v in order) for (r in releases) {
            val a = r.assets.firstOrNull { main(it.name.lowercase(), v) } ?: continue
            picked = a to (if (isMac) Accel.METAL else v)
            if (v == Accel.CUDA) runtime = r.assets.firstOrNull { cudart(it.name.lowercase()) }
            onLog("Found llama.cpp ${r.tag_name} (${picked.second.label})")
            break@outer
        }
        val (asset, variant) = picked ?: error(
            "No llama.cpp build for $os/$arch in the last ${releases.size} releases. " +
            "Newest: ${releases.firstOrNull()?.tag_name} ${releases.firstOrNull()?.assets?.map { it.name }?.take(5)}"
        )
        if (variant != wanted && !isMac) onLog("No ${wanted.label} build available — using ${variant.label}")

        for (a in listOfNotNull(asset, runtime)) {
            val tmp = File(binDir, "llama_download_${a.name}")
            try {
                ReleaseAssets.download(a.browser_download_url, tmp, "llama.cpp", onLog)
                onLog("Extracting ${a.name}…")
                ReleaseAssets.extractFlat(tmp, binDir) { name ->
                    name.takeIf { it == binary.name || ReleaseAssets.isLib(it) }
                }
            } finally {
                tmp.delete()
            }
        }
        if (!binary.exists()) error("llama-completion not found in ${asset.name}")
        variantMarker.writeText(variant.name)
        if (isMac) {
            patchMacRpaths(binary, onLog)
            runCatching { rpathMarker.writeText("v1") }
        }
        onLog("llama.cpp ready (${variant.label})")
        binary.absolutePath
    }

    /** Patch hardcoded CI rpaths on macOS so shared libs resolve from @loader_path. */
    private fun patchMacRpaths(bin: File, onLog: (String) -> Unit = {}) {
        // Remove quarantine
        ProcessBuilder("xattr", "-d", "com.apple.quarantine", bin.absolutePath)
            .redirectErrorStream(true).start().waitFor()

        // Find libs referenced from the binary
        val otool = ProcessBuilder("otool", "-L", bin.absolutePath)
            .redirectErrorStream(true).start()
        val otoolOutput = otool.inputStream.bufferedReader().readText()
        otool.waitFor()

        // Strip existing signature before modifying
        ProcessBuilder("codesign", "--remove-signature", bin.absolutePath)
            .redirectErrorStream(true).start().waitFor()

        // Patch any absolute or @rpath references
        val libRegex = Regex("""^\s+((?:/|@rpath/)[^\s]*\.dylib[^\s]*)""", RegexOption.MULTILINE)
        libRegex.findAll(otoolOutput).forEach { m ->
            val oldPath = m.groupValues[1]
            // System libraries must keep their absolute paths
            if (oldPath.startsWith("/usr/lib/") || oldPath.startsWith("/System/")) return@forEach
            val libName = oldPath.substringAfterLast('/')
            val newPath = "@loader_path/$libName"
            onLog("Patching: $oldPath -> $newPath")
            ProcessBuilder("install_name_tool", "-change", oldPath, newPath, bin.absolutePath)
                .redirectErrorStream(true).start().waitFor()
        }

        // Re-sign
        ProcessBuilder("codesign", "-s", "-", "--force", bin.absolutePath)
            .redirectErrorStream(true).start().waitFor()
        onLog("macOS rpaths patched for ${bin.name}")
    }
}
