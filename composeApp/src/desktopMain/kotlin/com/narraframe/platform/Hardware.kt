package com.narraframe.platform

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/** Which build of llama.cpp / sd.cpp to download and how to run it. */
internal enum class Accel(val label: String) {
    METAL("Metal"), CUDA("CUDA"), VULKAN("Vulkan"), CPU("CPU");

    val isGpu: Boolean get() = this != CPU
}

/**
 * GPU detection and the user's compute preference ("auto" | "gpu" | "cpu",
 * stored by AppSettings under the same Preferences key).
 */
internal object Hardware {

    private val prefs = Preferences.userRoot().node("com/narraframe")
    private const val COMPUTE_KEY = "compute_mode"

    private val os   = System.getProperty("os.name").lowercase()
    private val arch = System.getProperty("os.arch").lowercase()
    val isMac     = os.contains("mac")
    val isWindows = os.contains("win")
    val isArm     = arch.contains("aarch64") || arch.contains("arm")

    data class Gpu(val accel: Accel, val name: String, val vramMb: Int = 0)

    /** Best GPU this machine offers, detected once. CPU when none is usable. */
    val gpu: Gpu by lazy { detect() }

    val computeMode: String get() = prefs.get(COMPUTE_KEY, "auto")

    /** Accelerator the engines should use now, honouring the compute preference. */
    val accel: Accel get() = if (computeMode == "cpu") Accel.CPU else gpu.accel

    /**
     * Build to download. macOS has a single Metal build that can also run on CPU,
     * so it is always METAL there; CPU mode is applied at run time.
     */
    val buildVariant: Accel get() = if (isMac) Accel.METAL else accel

    private fun detect(): Gpu {
        if (isMac) {
            return if (isArm) Gpu(Accel.METAL, cpuBrand() ?: "Apple Silicon")
                   else Gpu(Accel.CPU, "Intel Mac")
        }
        nvidia()?.let { return it }
        if (hasVulkanLoader()) return Gpu(Accel.VULKAN, vulkanDeviceName() ?: "Vulkan GPU")
        return Gpu(Accel.CPU, "No supported GPU")
    }

    private fun run(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); null }
        else if (p.exitValue() == 0) out else null
    }.getOrNull()

    private fun cpuBrand(): String? = run("sysctl", "-n", "machdep.cpu.brand_string")?.trim()?.ifBlank { null }

    private fun nvidia(): Gpu? {
        val out = run("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits")
            ?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return null
        val parts = out.split(',').map { it.trim() }
        return Gpu(Accel.CUDA, parts.getOrNull(0) ?: "NVIDIA GPU", parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun hasVulkanLoader(): Boolean =
        if (isWindows) File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\vulkan-1.dll").exists()
        else listOf("/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu")
            .any { File(it, "libvulkan.so.1").exists() }

    private fun vulkanDeviceName(): String? =
        run("vulkaninfo", "--summary")?.lineSequence()
            ?.firstOrNull { it.trim().startsWith("deviceName") }
            ?.substringAfter('=')?.trim()
}
