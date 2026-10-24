package com.narraframe.platform

import com.narraframe.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads stable-diffusion.cpp: sd-server (keeps the model loaded between images),
 * sd-cli and the shared library, into <data dir>/bin.
 *
 * The build matches [Hardware.buildVariant]: Metal on macOS, CUDA for NVIDIA on
 * Windows, Vulkan for other GPUs, CPU otherwise. The installed variant is recorded
 * in .sd_variant; a different preferred variant makes the engine count as missing.
 */
internal object LocalSDDownloader {

    private val os   = System.getProperty("os.name").lowercase()

    private val isWindows: Boolean get() = os.contains("win")
    private val isMac:     Boolean get() = os.contains("mac")

    private val binDir: File get() = DataDir.bin
    private val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""

    val binary: File get() = File(binDir, "sd$exe")
    val server: File get() = File(binDir, "sd-server$exe")

    private val rpathMarker: File get() = File(binDir, ".rpath_patched")
    private val variantMarker: File get() = File(binDir, ".sd_variant")

    /** Variant on disk; installs from before variants existed were CPU builds (Metal on macOS). */
    val installedVariant: Accel? get() = when {
        !server.exists() -> null
        variantMarker.exists() -> runCatching { Accel.valueOf(variantMarker.readText().trim()) }.getOrNull()
        else -> if (isMac) Accel.METAL else Accel.CPU
    }

    /** Path to sd-server when the preferred build is installed, else null. */
    fun getCachedPath(): String? {
        val srv = server
        if (!srv.exists() || !srv.canExecute()) return null
        if (installedVariant != Hardware.buildVariant) return null
        // "v3" marker: sd-server is patched too
        if (isMac && rpathMarker.takeIf { it.exists() }?.readText()?.trim() != "v3") {
            listOf(binary, srv).filter { it.exists() }.forEach { patchMacRpaths(it) { msg -> AppLogger.info(msg) } }
            runCatching { rpathMarker.writeText("v3") }
        }
        return srv.absolutePath
    }

    suspend fun download(onLog: (String) -> Unit): String = withContext(Dispatchers.IO) {
        onLog("Looking for the latest stable-diffusion.cpp build…")
        val wanted = Hardware.buildVariant
        val releases = ReleaseAssets.releases("leejet/stable-diffusion.cpp", 10)

        // Asset names, e.g.:
        //   sd-master-<sha>-bin-Darwin-macOS-26.6.2-arm64.zip
        //   sd-master-<sha>-bin-Linux-Ubuntu-24.04-x86_64.zip   (CPU), …-x86_64-vulkan.zip
        //   sd-master-<sha>-bin-win-cpu-x64.zip, …-win-vulkan-x64.zip, …-win-cuda12-x64.zip
        //   cudart-sd-bin-win-cu12-x64.zip                       (CUDA runtime DLLs)
        fun main(n: String, v: Accel): Boolean = n.endsWith(".zip") && !n.startsWith("cudart") && when {
            isMac -> n.contains("darwin") || n.contains("macos")
            isWindows -> when (v) {
                Accel.CUDA   -> n.contains("-win-cuda12-x64")
                Accel.VULKAN -> n.contains("-win-vulkan-x64")
                else         -> n.contains("-win-cpu-x64") || n.contains("-win-avx2-x64")
            }
            else -> n.contains("linux") && n.contains("x86_64") && when (v) {
                Accel.VULKAN -> n.contains("vulkan")
                else         -> !n.contains("vulkan") && !n.contains("rocm") && !n.contains("cuda")
            }
        }
        // Linux has no CUDA build of sd.cpp; Vulkan runs on NVIDIA too
        val order = buildList {
            if (wanted == Accel.CUDA && !isWindows) add(Accel.VULKAN) else add(wanted)
            add(Accel.CPU)
        }.distinct()

        var picked: Pair<ReleaseAssets.Asset, Accel>? = null
        var cudart: ReleaseAssets.Asset? = null
        outer@ for (r in releases) for (v in order) {
            val a = r.assets.firstOrNull { main(it.name.lowercase(), v) } ?: continue
            picked = a to (if (isMac) Accel.METAL else v)
            if (v == Accel.CUDA) cudart = r.assets.firstOrNull { it.name.lowercase().startsWith("cudart-sd-bin-win-cu12") }
            onLog("Found sd.cpp ${r.tag_name} (${picked.second.label})")
            break@outer
        }
        val (asset, variant) = picked ?: error("No stable-diffusion.cpp build found for this computer")
        if (variant != wanted && !isMac) onLog("No ${wanted.label} build available — using ${variant.label}")

        val archives = listOfNotNull(asset, cudart)
        for (a in archives) {
            val tmp = File(binDir, "sd_download_${a.name}")
            try {
                ReleaseAssets.download(a.browser_download_url, tmp, a.name.substringBefore("-bin").take(24), onLog)
                onLog("Extracting ${a.name}…")
                ReleaseAssets.extractFlat(tmp, binDir) { name ->
                    when (name) {
                        "sd-cli", "sd-cli.exe", "sd", "sd.exe" -> binary.name
                        "sd-server", "sd-server.exe"           -> server.name
                        else -> name.takeIf { ReleaseAssets.isLib(it) }
                    }
                }
            } finally {
                tmp.delete()
            }
        }
        if (!server.exists()) error("sd-server was not found in ${asset.name}")
        variantMarker.writeText(variant.name)
        if (isMac) {
            listOf(binary, server).filter { it.exists() }.forEach { patchMacRpaths(it, onLog) }
            runCatching { rpathMarker.writeText("v3") }
        }
        // A server started with the old files must not keep running
        SdServer.stop()
        onLog("stable-diffusion.cpp ready (${variant.label})")
        server.absolutePath
    }

    /** Use install_name_tool to rewrite absolute dylib rpaths to @loader_path-relative ones.
     *  On macOS Ventura/Sonoma, modifying a signed binary invalidates its signature and dyld
     *  kills it with SIGABRT (exit 134). So we: strip sig -> patch -> re-sign ad-hoc. */
    private fun patchMacRpaths(bin: File, onLog: (String) -> Unit) {
        val dylib = File(bin.parentFile, "libstable-diffusion.dylib")

        // Remove quarantine so macOS allows execution of downloaded binaries
        for (f in listOfNotNull(bin, dylib.takeIf { it.exists() })) {
            ProcessBuilder("xattr", "-d", "com.apple.quarantine", f.absolutePath)
                .redirectErrorStream(true).start().waitFor()  // ignore exit; attr may not be set
        }

        // Strip existing code signature so install_name_tool can modify the binary cleanly
        for (f in listOfNotNull(bin, dylib.takeIf { it.exists() })) {
            val rc = ProcessBuilder("codesign", "--remove-signature", f.absolutePath)
                .redirectErrorStream(true).start().waitFor()
            onLog("codesign --remove-signature ${f.name}: exit $rc")
        }

        // Patch hardcoded CI rpaths in the binary -> @loader_path-relative.
        // Handles both absolute paths (/Users/runner/...) and @rpath/... references.
        val otool = ProcessBuilder("otool", "-L", bin.absolutePath)
            .redirectErrorStream(true).start()
        val otoolOutput = otool.inputStream.bufferedReader().readText()
        otool.waitFor()

        val libRegex = Regex("""^\s+((?:/|@rpath/)[^\s]*libstable-diffusion[^\s]*)""", RegexOption.MULTILINE)
        libRegex.findAll(otoolOutput).forEach { m ->
            val oldPath = m.groupValues[1]
            val libName = oldPath.substringAfterLast('/')
            val newPath = "@loader_path/$libName"
            onLog("Patching load command: $oldPath -> $newPath")
            val patchProc = ProcessBuilder("install_name_tool", "-change", oldPath, newPath, bin.absolutePath)
                .redirectErrorStream(true).start()
            val patchOut  = patchProc.inputStream.bufferedReader().readText()
            val patchRc   = patchProc.waitFor()
            if (patchRc != 0) {
                onLog("WARNING: install_name_tool failed (exit $patchRc): $patchOut")
                // Fat binaries with corrupted LINKEDIT cannot be patched directly.
                // Fall back: extract each arch slice with lipo, patch individually, then re-combine.
                if ("LINKEDIT" in patchOut || "order that can be processed" in patchOut) {
                    onLog("Attempting lipo slice-by-slice fallback for fat binary…")
                    patchFatBinaryViaLipo(bin, oldPath, newPath, onLog)
                }
            }
        }

        // Fix the dylib's own install name so it's self-consistent
        if (dylib.exists()) {
            val rc = ProcessBuilder(
                "install_name_tool", "-id", "@loader_path/${dylib.name}", dylib.absolutePath
            ).redirectErrorStream(true).start().waitFor()
            onLog("Patching dylib install name: exit $rc")
        }

        // Re-sign both with ad-hoc signature so dyld accepts them on modern macOS
        for (f in listOfNotNull(bin, dylib.takeIf { it.exists() })) {
            val rc = ProcessBuilder("codesign", "-s", "-", "--force", f.absolutePath)
                .redirectErrorStream(true).start().waitFor()
            onLog("codesign -s - ${f.name}: exit $rc")
        }
    }

    /**
     * Fallback for fat (universal) binaries whose LINKEDIT segment prevents direct patching.
     * Uses `lipo` to split each architecture slice, patches each individually, then re-combines.
     */
    private fun patchFatBinaryViaLipo(bin: File, oldPath: String, newPath: String, onLog: (String) -> Unit) {
        val arches = listOf("x86_64", "arm64")
        val slices  = arches.mapNotNull { arch ->
            val slice = File(bin.parentFile, ".sd_slice_$arch")
            val rc = ProcessBuilder("lipo", bin.absolutePath, "-extract", arch, "-output", slice.absolutePath)
                .redirectErrorStream(true).start().waitFor()
            if (rc == 0) { onLog("lipo extracted $arch"); slice }
            else         { onLog("lipo: $arch not present (exit $rc)"); null }
        }
        if (slices.isEmpty()) { onLog("WARNING: lipo produced no slices, skipping fallback"); return }

        slices.forEach { slice ->
            val rc = ProcessBuilder("install_name_tool", "-change", oldPath, newPath, slice.absolutePath)
                .redirectErrorStream(true).start().waitFor()
            onLog("install_name_tool on ${slice.name}: exit $rc")
        }

        // Re-combine slices back into a single fat binary (overwrite original)
        val lipoCmd = mutableListOf("lipo") + slices.map { it.absolutePath } +
                      listOf("-create", "-output", bin.absolutePath)
        val rc = ProcessBuilder(lipoCmd).redirectErrorStream(true).start().waitFor()
        onLog("lipo re-combine: exit $rc")
        slices.forEach { it.delete() }
    }
}
