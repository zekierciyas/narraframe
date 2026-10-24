package com.narraframe.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.narraframe.models.LocalSDPreset
import com.narraframe.platform.availableLLMModels
import com.narraframe.platform.availablePiperVoices
import com.narraframe.platform.availableSDModels
import com.narraframe.platform.isAppleSilicon
import com.narraframe.platform.totalMemoryGb
import com.narraframe.services.NeuralTtsService
import com.narraframe.ui.components.*
import com.narraframe.ui.theme.*
import com.narraframe.viewmodel.InstallTask
import com.narraframe.viewmodel.SetupController

// Sections shared by the setup wizard and the Settings screen.

// ── Storage: library + engines & models ────────────────────────────────────

@Composable
fun StorageSection(setup: SetupController) {
    SectionTitle("Where should Narraframe keep its files?", "Both folders can be on an external drive. You can change them later in Settings → Storage.")
    Spacer(Modifier.height(20.dp))

    FolderRow(
        icon        = Icons.Default.VideoLibrary,
        title       = "Videos",
        description = "Every video gets its own folder here with the narration, images and final MP4.",
        path        = setup.libraryPath,
        available   = setup.libraryAvailable,
        onChange    = setup::chooseLibraryFolder
    )
    Spacer(Modifier.height(14.dp))
    FolderRow(
        icon        = Icons.Default.Memory,
        title       = "Engines and models",
        description = "Downloaded AI engines, models and voices. Needs a few GB; ${formatSize(setup.freeSpaceMB)} free on this drive.",
        path        = setup.dataDir,
        available   = setup.dataDirAvailable,
        onChange    = setup::chooseDataFolder,
        onReset     = if (setup.dataDir != com.narraframe.platform.defaultDataDirectory) setup::useDefaultDataFolder else null,
        enabled     = !setup.installing && setup.movingData == null
    )
    setup.movingData?.let { msg ->
        Spacer(Modifier.height(12.dp))
        Text("Moving downloads… $msg", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        GradientProgress(msg.substringBefore('%').trim().toIntOrNull()?.div(100f), Modifier.fillMaxWidth())
    }
    setup.dataDirError?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = ErrorRed)
    }

    setup.pendingDataDir?.let { target ->
        AlertDialog(
            onDismissRequest = setup::dismissDataDir,
            title = { Text("Move existing downloads?") },
            text = {
                Text(
                    "Engines and models are already downloaded in ${setup.dataDir}.\n\n" +
                    "Move them to $target, or start empty there and download again?"
                )
            },
            confirmButton = { TextButton(onClick = { setup.confirmDataDir(moveExisting = true) }) { Text("Move them") } },
            dismissButton = {
                Row {
                    TextButton(onClick = setup::dismissDataDir) { Text("Cancel") }
                    TextButton(onClick = { setup.confirmDataDir(moveExisting = false) }) { Text("Start empty") }
                }
            }
        )
    }
}

@Composable
private fun FolderRow(
    icon:        androidx.compose.ui.graphics.vector.ImageVector,
    title:       String,
    description: String,
    path:        String,
    available:   Boolean,
    onChange:    () -> Unit,
    onReset:     (() -> Unit)? = null,
    enabled:     Boolean = true
) {
    Panel(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            if (onReset != null) TextButton(onClick = onReset, enabled = enabled) { Text("Use default") }
            OutlinedButton(onClick = onChange, enabled = enabled) { Text("Change…") }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().background(Surface2, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f)
            )
            if (!available) Badge("Drive not connected", ErrorRed, Icons.Default.ErrorOutline)
        }
    }
}

// ── Compute: GPU / CPU and a quick benchmark ───────────────────────────────

@Composable
fun ComputeSection(setup: SetupController) {
    SectionTitle("Compute", "Where the AI engines run. GPU is many times faster when available.")
    Spacer(Modifier.height(12.dp))
    Panel(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(com.narraframe.platform.gpuSummary, style = MaterialTheme.typography.titleSmall)
                Text("Engines run on: ${com.narraframe.platform.activeAccelerator()}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            "auto" to ("Automatic" to "GPU when available, otherwise CPU"),
            "gpu"  to ("GPU" to "Use the GPU build of each engine"),
            "cpu"  to ("CPU only" to "Slower; for debugging or unsupported GPUs")
        ).forEach { (id, text) ->
            OptionCard(selected = setup.computeMode == id, onClick = { setup.selectComputeMode(id) }, modifier = Modifier.weight(1f)) {
                Text(text.first, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(text.second, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val bench = setup.benchResult
        OutlinedButton(
            onClick = setup::runBenchmark,
            enabled = bench != SetupController.Bench.Running && setup.imageModelInstalled
        ) {
            Icon(Icons.Default.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Test image speed")
        }
        Spacer(Modifier.width(14.dp))
        when (bench) {
            SetupController.Bench.Running -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Rendering two test images…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            is SetupController.Bench.Done ->
                Badge("${"%.1f".format(bench.seconds)} s per image on ${bench.backend}", SuccessGreen, Icons.Default.CheckCircle)
            is SetupController.Bench.Failed ->
                Text(bench.message, style = MaterialTheme.typography.bodySmall, color = ErrorRed, maxLines = 2)
            null -> setup.measuredSecsPerImage?.let {
                Text("Last measured: ${"%.1f".format(it)} s per image", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
}

/** True when a model of [sizeMB] leaves enough RAM for the other engines. */
private fun fitsInRam(sizeMB: Int): Boolean =
    totalMemoryGb <= 0 || sizeMB * 1.5 < totalMemoryGb * 1024 * 0.5

// ── Text model ─────────────────────────────────────────────────────────────

@Composable
fun TextModelSection(setup: SetupController) {
    SectionTitle(
        "Text model",
        "Reads your script and writes one image prompt per scene. A small model is enough."
    )
    Spacer(Modifier.height(16.dp))

    val recommended = if (totalMemoryGb in 1..8) "llama-3.2-1b-q4" else "llama-3.2-3b-q4"
    val custom = setup.llmMode == "offline" && setup.llmModelId.endsWith(".gguf")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        availableLLMModels.forEach { m ->
            ModelRow(
                selected    = setup.llmMode == "offline" && setup.llmModelId == m.id,
                title       = m.name,
                description = m.desc,
                size        = m.sizeMB,
                recommended = m.id == recommended,
                installed   = setup.isLlmModelCached(m.id),
                warning     = if (fitsInRam(m.sizeMB)) null else "May be slow with $totalMemoryGb GB RAM",
                onClick     = { setup.selectLlmMode("offline"); setup.selectLlmModel(m.id) }
            )
        }
        ModelRow(
            selected    = custom,
            title       = "Use my own .gguf file",
            description = if (custom) setup.llmModelId else "Any instruction-tuned GGUF model that llama.cpp can run.",
            onClick     = { setup.selectLlmMode("offline"); setup.browseLlmModel() },
            icon        = Icons.Default.FileOpen
        )
        ModelRow(
            selected    = setup.llmMode == "server",
            title       = "Use Ollama or LM Studio",
            description = "Connect to a model server already running on this computer.",
            onClick     = { setup.selectLlmMode("server") },
            icon        = Icons.Default.Dns
        )
    }

    if (setup.llmMode == "server") {
        Spacer(Modifier.height(16.dp))
        ServerFields(setup)
    }
}

@Composable
private fun ServerFields(setup: SetupController) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = setup.llmUrl,
            onValueChange = setup::updateLlmUrl,
            label = { Text("Server URL") },
            supportingText = { Text("Ollama: http://localhost:11434 · LM Studio: http://localhost:1234") },
            singleLine = true,
            modifier = Modifier.weight(1.4f)
        )
        OutlinedTextField(
            value = setup.llmServerModel,
            onValueChange = setup::updateLlmServerModel,
            label = { Text("Model name") },
            supportingText = { Text("e.g. llama3.2") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = setup::testServer, enabled = setup.serverTest != SetupController.ServerTest.Running) {
            Text("Test connection")
        }
        Spacer(Modifier.width(12.dp))
        when (val t = setup.serverTest) {
            SetupController.ServerTest.Running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            is SetupController.ServerTest.Ok -> Badge("Connected — “${t.sample}”", SuccessGreen, Icons.Default.CheckCircle)
            is SetupController.ServerTest.Failed -> Text(t.message, color = ErrorRed, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            null -> {}
        }
    }
}

// ── Image model ────────────────────────────────────────────────────────────

@Composable
fun ImageModelSection(setup: SetupController) {
    SectionTitle("Image model", "Draws one picture per scene with Stable Diffusion.")
    Spacer(Modifier.height(16.dp))

    val custom = setup.sdModelId.endsWith(".gguf")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        availableSDModels.forEachIndexed { i, m ->
            ModelRow(
                selected    = setup.sdModelId == m.id,
                title       = m.name,
                description = m.desc,
                size        = m.sizeMB,
                recommended = i == 0,
                installed   = setup.isSdModelCached(m.id),
                warning     = if (fitsInRam(m.sizeMB)) null else "May be slow with $totalMemoryGb GB RAM",
                onClick     = { setup.selectSdModel(m.id) }
            )
        }
        ModelRow(
            selected    = custom,
            title       = "Use my own .gguf file",
            description = if (custom) setup.sdModelId else "A Stable Diffusion model converted to GGUF for stable-diffusion.cpp.",
            onClick     = setup::browseSdModel,
            icon        = Icons.Default.FileOpen
        )
    }

    Spacer(Modifier.height(24.dp))
    SectionTitle(
        "Quality",
        if (isAppleSilicon) "Times are for a CPU; Apple Silicon is usually 2–3× faster."
        else "Times are rough CPU estimates per image."
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LocalSDPreset.entries.forEach { p ->
            OptionCard(
                selected = setup.sdPreset == p.id,
                onClick  = { setup.selectSdPreset(p.id) },
                modifier = Modifier.weight(1f)
            ) {
                Text(p.label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("${p.perImageLabel} per image · ${p.steps} steps", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Text(p.qualityNote, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
}

// ── Voice ──────────────────────────────────────────────────────────────────

private data class EngineCard(val id: String, val title: String, val blurb: String, val size: String, val tag: String?)

@Composable
fun VoiceSection(setup: SetupController) {
    SectionTitle("Voice", "Reads your script aloud. You can preview every voice before you generate.")
    Spacer(Modifier.height(16.dp))

    val engines = listOf(
        EngineCard("kokoro", "Kokoro", "Natural neural voices, fast on any CPU.", "~360 MB", "Recommended"),
        EngineCard("piper", "Piper", "Very light and fast. Bring your own .onnx voices.", "~340 MB", null),
        EngineCard("qwen3", "Qwen3-TTS", "Most expressive, voice design and cloning.", "3–6 GB", "GPU"),
        EngineCard("os", "System voices", "Built into your OS. Nothing to download.", "0 MB", null)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        engines.forEach { e ->
            OptionCard(
                selected = setup.voiceEngine == e.id,
                onClick  = { setup.selectVoiceEngine(e.id) },
                modifier = Modifier.weight(1f).heightIn(min = 118.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    e.tag?.let { Badge(it, if (it == "GPU") WarningOrange else Accent) }
                }
                Spacer(Modifier.height(6.dp))
                Text(e.blurb, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                Spacer(Modifier.height(6.dp))
                Text(e.size, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    when (setup.voiceEngine) {
        "kokoro" -> VoiceGrid(
            voices   = NeuralTtsService.kokoroVoices,
            selected = setup.kokoroVoice,
            onSelect = setup::selectKokoroVoice
        )
        "qwen3"  -> QwenOptions(setup)
        "piper"  -> PiperOptions(setup)
        "os"     -> OsVoiceOptions(setup)
    }

    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        PreviewButton(setup)
        Spacer(Modifier.width(24.dp))
        Text("Speed", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Spacer(Modifier.width(12.dp))
        Slider(
            value = setup.voiceSpeedState.toFloat(),
            onValueChange = { setup.updateVoiceSpeed((it * 20).toInt() / 20.0) },
            valueRange = 0.5f..2f,
            modifier = Modifier.width(220.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("%.2f×".format(setup.voiceSpeedState), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VoiceGrid(voices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val rows = voices.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, label) ->
                    FilterChip(
                        selected = selected == id,
                        onClick  = { onSelect(id) },
                        label    = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QwenOptions(setup: SetupController) {
    val variants = NeuralTtsService.engines.filter { it.id.startsWith("qwen3") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        variants.forEach { e ->
            FilterChip(
                selected = setup.neuralEngine == e.id,
                onClick  = { setup.selectNeuralEngine(e.id) },
                label    = { Text("${e.displayName.removePrefix("Qwen3-TTS ")} · ${e.sizeLabel}") }
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    if (setup.neuralEngine == "qwen3_vd") {
        OutlinedTextField(
            value = setup.voiceDesignPrompt,
            onValueChange = setup::updateVoiceDesignPrompt,
            label = { Text("Describe the voice") },
            supportingText = { Text("Tone, accent, age, emotion — e.g. “deep, calm documentary narrator”") },
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        VoiceGrid(NeuralTtsService.qwen3Voices, setup.qwen3Voice, setup::selectQwen3Voice)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Qwen3 installs PyTorch (~2 GB) and runs slowly without an NVIDIA GPU or Apple Silicon.",
        style = MaterialTheme.typography.bodySmall,
        color = WarningOrange
    )
}

@Composable
private fun PiperOptions(setup: SetupController) {
    val custom = setup.piperVoiceId.endsWith(".onnx")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        availablePiperVoices.forEach { v ->
            ModelRow(
                selected    = setup.piperVoiceId == v.id,
                title       = v.name,
                description = v.lang,
                size        = v.sizeMB,
                installed   = setup.isPiperVoiceCached(v.id),
                onClick     = { setup.selectPiperVoice(v.id) },
                compact     = true
            )
        }
        ModelRow(
            selected    = custom,
            title       = "Use my own Piper voice",
            description = if (custom) setup.piperVoiceId else "Pick an .onnx file; its .onnx.json must be in the same folder.",
            onClick     = setup::browsePiperVoice,
            icon        = Icons.Default.FileOpen,
            compact     = true
        )
    }
}

@Composable
private fun OsVoiceOptions(setup: SetupController) {
    if (setup.systemVoices.isNotEmpty()) {
        VoiceGrid(
            voices   = listOf("" to "System default") + setup.systemVoices.map { it to it },
            selected = setup.osVoice,
            onSelect = setup::selectOsVoice
        )
    } else {
        OutlinedTextField(
            value = setup.osVoice,
            onValueChange = setup::selectOsVoice,
            label = { Text("Voice name (optional)") },
            supportingText = { Text("Leave empty to use the system default voice.") },
            singleLine = true,
            modifier = Modifier.width(360.dp)
        )
    }
}

@Composable
fun PreviewButton(setup: SetupController) {
    val st = setup.previewState
    val busy = st == SetupController.PreviewState.Generating || st == SetupController.PreviewState.Playing
    FilledTonalButton(onClick = { setup.previewVoice() }) {
        when (st) {
            SetupController.PreviewState.Generating -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("Preparing…")
            }
            SetupController.PreviewState.Playing -> {
                Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop")
            }
            else -> {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Preview voice")
            }
        }
    }
    if (!busy && st is SetupController.PreviewState.Failed) {
        Spacer(Modifier.width(12.dp))
        Text(st.message, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

// ── Model row ──────────────────────────────────────────────────────────────

@Composable
fun ModelRow(
    selected:    Boolean,
    title:       String,
    description: String,
    onClick:     () -> Unit,
    size:        Int? = null,
    recommended: Boolean = false,
    installed:   Boolean = false,
    icon:        androidx.compose.ui.graphics.vector.ImageVector? = null,
    compact:     Boolean = false,
    warning:     String? = null
) {
    OptionCard(selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            if (icon != null) {
                Icon(icon, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (recommended) Badge("Recommended")
                    if (installed) InstalledBadge()
                    if (warning != null) Badge(warning, WarningOrange)
                }
                if (!compact || description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (size != null && size > 0) {
                Spacer(Modifier.width(12.dp))
                Text(formatSize(size), style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}

// ── Install list ───────────────────────────────────────────────────────────

/** Shows what the current selection still needs, with progress and per-item retry. */
@Composable
fun InstallList(setup: SetupController, modifier: Modifier = Modifier) {
    val tasks = setup.tasks
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!setup.dataDirAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed)
                Spacer(Modifier.width(10.dp))
                Text(
                    "The drive for engines and models is not connected (${setup.dataDir}). Connect it or choose another folder.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }
        if (tasks.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen)
                Spacer(Modifier.width(10.dp))
                Text("Everything is installed.", style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }
        tasks.forEach { t -> TaskRow(t, onRetry = { setup.retry(t.id) }, canRetry = !setup.installing) }
    }
}

@Composable
private fun TaskRow(task: InstallTask, onRetry: () -> Unit, canRetry: Boolean) {
    Panel(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (task.status) {
                InstallTask.Status.DONE    -> Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                InstallTask.Status.FAILED  -> Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(20.dp))
                InstallTask.Status.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                InstallTask.Status.PENDING -> Icon(Icons.Default.Download, null, tint = TextMuted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(task.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    task.message?.takeIf { task.status != InstallTask.Status.PENDING } ?: task.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == InstallTask.Status.FAILED) ErrorRed else TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (task.sizeMB > 0) {
                Text("~" + formatSize(task.sizeMB), style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            if (task.status == InstallTask.Status.FAILED) {
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onRetry, enabled = canRetry) { Text("Retry") }
            }
        }
        if (task.status == InstallTask.Status.RUNNING) {
            Spacer(Modifier.height(10.dp))
            GradientProgress(task.progress, Modifier.fillMaxWidth())
        }
    }
}
