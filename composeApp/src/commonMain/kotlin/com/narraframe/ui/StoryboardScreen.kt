package com.narraframe.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.narraframe.models.GenerationStep
import com.narraframe.models.ImageAspectRatio
import com.narraframe.models.SDStylePreset
import com.narraframe.ui.components.*
import com.narraframe.ui.theme.*
import com.narraframe.viewmodel.Screen
import com.narraframe.viewmodel.StepStatus
import com.narraframe.viewmodel.StudioViewModel

/** Main screen: script composer on top, live storyboard of scenes below. */
@Composable
fun StoryboardScreen(viewModel: StudioViewModel) {
    val state = viewModel.state
    val hasRun = viewModel.current != null || !state.isIdle

    Column(Modifier.fillMaxSize().appBackground()) {
        TopBar(viewModel)
        Box(Modifier.weight(1f)) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 1180.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    viewModel.resumeInfo?.let { ResumeBanner(viewModel) }
                    Composer(viewModel)
                    if (hasRun) {
                        StepBar(viewModel)
                        when {
                            state.isComplete -> ResultCard(viewModel)
                            state.hasFailed  -> FailureCard(viewModel)
                        }
                        SceneGrid(viewModel)
                    } else {
                        EmptyHint()
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible  = viewModel.showLogs,
                enter    = slideInVertically { it } + fadeIn(),
                exit     = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                LogsPanel(Modifier.fillMaxWidth().height(280.dp), viewModel)
            }
        }
    }
}

// ── Top bar ────────────────────────────────────────────────────────────────

@Composable
fun TopBar(viewModel: StudioViewModel, title: String? = null, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Spacer(Modifier.width(4.dp))
            Text(title.orEmpty(), style = MaterialTheme.typography.titleLarge)
        } else {
            Wordmark()
        }
        Spacer(Modifier.weight(1f))
        if (onBack == null) {
            TextButton(onClick = viewModel::newVideo, enabled = !viewModel.state.isGenerating) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New")
            }
            TextButton(onClick = { viewModel.navigate(Screen.LIBRARY) }) {
                Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Library")
            }
            IconToggleButton(checked = viewModel.showLogs, onCheckedChange = { viewModel.showLogs = it }) {
                Icon(Icons.Default.Terminal, "Logs", tint = if (viewModel.showLogs) Accent else TextMuted)
            }
            IconButton(onClick = { viewModel.navigate(Screen.SETTINGS) }) {
                Icon(Icons.Default.Settings, "Settings", tint = TextMuted)
            }
        }
    }
    HorizontalDivider(color = Outline)
}

// ── Composer ───────────────────────────────────────────────────────────────

@Composable
private fun Composer(viewModel: StudioViewModel) {
    val generating = viewModel.state.isGenerating
    Panel(padding = 0.dp) {
        TextField(
            value = viewModel.script,
            onValueChange = { viewModel.script = it },
            enabled = !generating,
            placeholder = {
                Text(
                    "Paste or write your script…\n\nA story, a documentary narration, an explainer — every sentence is read aloud " +
                    "and illustrated scene by scene.",
                    color = TextMuted
                )
            },
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor   = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor  = Color.Transparent,
                focusedIndicatorColor   = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor  = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp, max = 340.dp).padding(8.dp)
        )
        HorizontalDivider(color = Outline)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VoiceChip(viewModel, enabled = !generating)
            StyleChip(viewModel, enabled = !generating)
            AspectChip(viewModel, enabled = !generating)
            Spacer(Modifier.weight(1f))
            if (viewModel.wordCount > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "~${viewModel.estimatedDuration} · ${viewModel.estimatedSceneCount} scenes",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "images ${viewModel.estimatedRenderTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            if (generating) {
                OutlinedButton(onClick = viewModel::cancelGeneration) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cancel")
                }
            } else {
                GradientButton(
                    "Generate",
                    onClick = viewModel::startGeneration,
                    enabled = viewModel.canGenerate,
                    icon = Icons.Default.AutoAwesome
                )
            }
        }
        viewModel.generateBlocker?.takeIf { viewModel.script.isNotBlank() }?.let { msg ->
            Row(
                Modifier.fillMaxWidth().background(WarningOrange.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, tint = WarningOrange, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall, color = WarningOrange, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.navigate(Screen.SETTINGS) }) { Text("Open Settings") }
            }
        }
    }
}

@Composable
private fun ChipButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
        trailingIcon = { Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = Surface2),
        border = AssistChipDefaults.assistChipBorder(enabled = enabled, borderColor = Outline)
    )
}

@Composable
private fun VoiceChip(viewModel: StudioViewModel, enabled: Boolean) {
    val setup = viewModel.setup
    var open by remember { mutableStateOf(false) }
    Box {
        ChipButton(Icons.Default.RecordVoiceOver, setup.voiceLabel, enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (setup.previewState == com.narraframe.viewmodel.SetupController.PreviewState.Playing) "Stop preview" else "Preview voice") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                onClick = { setup.previewVoice() }
            )
            DropdownMenuItem(
                text = { Text("Change voice…") },
                leadingIcon = { Icon(Icons.Default.Tune, null) },
                onClick = { open = false; viewModel.navigate(Screen.SETTINGS) }
            )
        }
    }
}

@Composable
private fun StyleChip(viewModel: StudioViewModel, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val current = SDStylePreset.fromId(viewModel.stylePresetId)
    Box {
        ChipButton(Icons.Default.Palette, current.label, enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SDStylePreset.entries.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.label)
                            Text(p.description, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    },
                    trailingIcon = { if (p == current) Icon(Icons.Default.Check, null, tint = Accent) },
                    onClick = { viewModel.selectStyle(p); open = false }
                )
            }
        }
    }
}

@Composable
private fun AspectChip(viewModel: StudioViewModel, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChipButton(Icons.Default.AspectRatio, viewModel.aspectRatio, enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ImageAspectRatio.entries.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.label + "  " + aspectHint(r), maxLines = 1) },
                    trailingIcon = { if (r.id == viewModel.aspectRatio) Icon(Icons.Default.Check, null, tint = Accent) },
                    onClick = { viewModel.selectAspectRatio(r); open = false }
                )
            }
        }
    }
}

private fun aspectHint(r: ImageAspectRatio) = when (r) {
    ImageAspectRatio.LANDSCAPE_16_9 -> "YouTube"
    ImageAspectRatio.PORTRAIT_9_16  -> "Shorts, Reels, TikTok"
    ImageAspectRatio.SQUARE_1_1     -> "Square"
    ImageAspectRatio.CLASSIC_4_3    -> "Classic"
    ImageAspectRatio.TALL_3_4       -> "Portrait"
}

// ── Progress ───────────────────────────────────────────────────────────────

private val stepLabels = mapOf(
    GenerationStep.GENERATING_AUDIO         to ("Voice" to Icons.Default.RecordVoiceOver),
    GenerationStep.ANALYZING_AUDIO          to ("Timing" to Icons.Default.Timer),
    GenerationStep.GENERATING_IMAGE_PROMPTS to ("Prompts" to Icons.AutoMirrored.Filled.Notes),
    GenerationStep.GENERATING_IMAGES        to ("Images" to Icons.Default.Image),
    GenerationStep.COMPOSING_VIDEO          to ("Video" to Icons.Default.Movie)
)

@Composable
private fun StepBar(viewModel: StudioViewModel) {
    val state = viewModel.state
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GenerationStep.pipelineSteps.forEachIndexed { i, step ->
                val status = viewModel.stepStatus(step)
                val (label, icon) = stepLabels.getValue(step)
                val extra = if (step == GenerationStep.GENERATING_IMAGES && state.scenePrompts.isNotEmpty())
                    " ${state.sceneImages.size}/${state.scenePrompts.size}" else ""
                StepPill(label + extra, icon, status)
                if (i < GenerationStep.pipelineSteps.lastIndex) {
                    Box(
                        Modifier.weight(1f).height(1.dp).padding(horizontal = 6.dp)
                            .background(if (status == StepStatus.COMPLETED) Accent.copy(alpha = 0.6f) else Outline)
                    )
                }
            }
        }
        if (state.isGenerating) {
            Spacer(Modifier.height(14.dp))
            GradientProgress(state.overallProgress, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                state.logs.lastOrNull { it.level != com.narraframe.models.LogEntry.Level.DEBUG }?.message ?: "Starting…",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StepPill(label: String, icon: ImageVector, status: StepStatus) {
    val (bg, fg) = when (status) {
        StepStatus.COMPLETED   -> SuccessGreen.copy(alpha = 0.12f) to SuccessGreen
        StepStatus.IN_PROGRESS -> Accent.copy(alpha = 0.16f) to TextPrimary
        StepStatus.FAILED      -> ErrorRed.copy(alpha = 0.14f) to ErrorRed
        StepStatus.PENDING     -> Surface2 to TextMuted
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (status) {
            StepStatus.IN_PROGRESS -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
            StepStatus.COMPLETED   -> Icon(Icons.Default.Check, null, tint = fg, modifier = Modifier.size(14.dp))
            StepStatus.FAILED      -> Icon(Icons.Default.Close, null, tint = fg, modifier = Modifier.size(14.dp))
            StepStatus.PENDING     -> Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

// ── Result / failure ───────────────────────────────────────────────────────

@Composable
private fun ResultCard(viewModel: StudioViewModel) {
    val gen = viewModel.current ?: return
    val thumb = rememberImageFile(viewModel.state.sceneImages[0])
    Panel(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(176.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).background(Surface2)
                    .clickable { viewModel.playVideo() },
                contentAlignment = Alignment.Center
            ) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, "Play", tint = Color.White)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Badge("Video ready", SuccessGreen, Icons.Default.CheckCircle)
                Spacer(Modifier.height(8.dp))
                Text(gen.meta.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val took = viewModel.state.timings["TOTAL"] ?: gen.meta.timings["TOTAL"]
                Text(
                    "${StudioViewModel.formatDuration(viewModel.state.audioDurationSec)} · " +
                    "${viewModel.state.scenePrompts.size} scenes · ${gen.meta.voice} · ${gen.meta.style}" +
                    (took?.let { " · made in ${StudioViewModel.formatDuration(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientButton("Play", onClick = { viewModel.playVideo() }, icon = Icons.Default.PlayArrow)
                OutlinedButton(onClick = viewModel::toggleNarration, enabled = viewModel.state.narrationPath != null) {
                    Icon(if (viewModel.narrationPlaying) Icons.Default.Stop else Icons.Default.GraphicEq, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Audio")
                }
                IconButton(onClick = { viewModel.revealInFolder() }) { Icon(Icons.Default.FolderOpen, "Show in folder", tint = TextMuted) }
                IconButton(onClick = { viewModel.exportVideo() }) { Icon(Icons.Default.SaveAlt, "Export", tint = TextMuted) }
            }
        }
    }
}

@Composable
private fun FailureCard(viewModel: StudioViewModel) {
    val state = viewModel.state
    Panel(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Generation stopped", style = MaterialTheme.typography.titleSmall)
                Text(
                    state.errorMessage ?: "Unknown error",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { viewModel.showLogs = true }) { Text("Show logs") }
            Spacer(Modifier.width(4.dp))
            GradientButton("Retry", onClick = viewModel::retryCurrent, icon = Icons.Default.Refresh)
        }
    }
}

@Composable
private fun ResumeBanner(viewModel: StudioViewModel) {
    val info = viewModel.resumeInfo ?: return
    Panel(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("“${info.generation.meta.title}” did not finish", style = MaterialTheme.typography.titleSmall)
                Text(
                    info.errorMessage.ifBlank { "The app was closed while it was generating." } +
                        " Finished steps are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 2
                )
            }
            TextButton(onClick = viewModel::dismissResumeInfo) { Text("Dismiss") }
            Spacer(Modifier.width(4.dp))
            GradientButton("Resume", onClick = viewModel::resumeGeneration, enabled = !viewModel.state.isGenerating, icon = Icons.Default.PlayArrow)
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(5) { i ->
                Box(
                    Modifier.width(96.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small)
                        .background(Surface1).border(1.dp, Outline, MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Your storyboard appears here", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Each scene shows up as soon as its image is ready.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
    }
}

// ── Scene grid ─────────────────────────────────────────────────────────────

@Composable
private fun SceneGrid(viewModel: StudioViewModel) {
    val state = viewModel.state
    val count = when {
        state.scenePrompts.isNotEmpty() -> state.scenePrompts.size
        state.estimatedImageCount > 0   -> state.estimatedImageCount
        state.isGenerating              -> viewModel.estimatedSceneCount
        else                            -> 0
    }
    if (count == 0) return
    val ratio = ImageAspectRatio.fromId(viewModel.current?.meta?.aspectRatio ?: viewModel.aspectRatio)
    val (rw, rh) = ratio.id.split(':').map { it.toFloat() }
    var opened by remember { mutableStateOf<Int?>(null) }

    Text("Scenes", style = MaterialTheme.typography.titleMedium)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (rw >= rh) maxOf(2, (maxWidth / 240.dp).toInt()) else maxOf(3, (maxWidth / 170.dp).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            (0 until count).chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { idx ->
                        SceneCard(
                            index    = idx,
                            prompt   = state.scenePrompts.getOrNull(idx),
                            image    = state.sceneImages[idx],
                            ratio    = rw / rh,
                            start    = idx * viewModel.imageInterval,
                            active   = state.isGenerating && state.renderingScene == idx,
                            progress = if (state.renderingScene == idx) state.renderingProgress else null,
                            onClick  = { if (state.sceneImages[idx] != null) opened = idx },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    opened?.let { idx ->
        SceneDialog(
            index   = idx,
            prompt  = state.scenePrompts.getOrNull(idx).orEmpty(),
            image   = state.sceneImages[idx],
            onClose = { opened = null }
        )
    }
}

@Composable
private fun SceneCard(
    index:    Int,
    prompt:   String?,
    image:    String?,
    ratio:    Float,
    start:    Double,
    active:   Boolean,
    progress: Float?,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = rememberImageFile(image)
    Column(modifier.clickable(enabled = image != null, onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).clip(MaterialTheme.shapes.medium)
                .border(if (active) 1.5.dp else 1.dp, if (active) Accent else Outline, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Shimmer(Modifier.fillMaxSize())
                if (active) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Accent)
                        if (progress != null && progress > 0f) {
                            Spacer(Modifier.height(6.dp))
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        }
                    }
                    if (progress != null) {
                        GradientProgress(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), height = 3.dp)
                    }
                }
            }
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(StudioViewModel.formatDuration(start), style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(
            prompt ?: " ",
            style = MaterialTheme.typography.bodySmall,
            color = if (prompt != null) TextPrimary.copy(alpha = 0.85f) else TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SceneDialog(index: Int, prompt: String, image: String?, onClose: () -> Unit) {
    val bitmap = rememberImageFile(image)
    Dialog(onDismissRequest = onClose) {
        Panel(Modifier.widthIn(max = 900.dp), padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scene ${index + 1}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            Spacer(Modifier.height(8.dp))
            bitmap?.let {
                Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium))
            }
            Spacer(Modifier.height(12.dp))
            Text("Prompt", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            SelectionContainerText(prompt)
        }
    }
}

@Composable
private fun SelectionContainerText(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
