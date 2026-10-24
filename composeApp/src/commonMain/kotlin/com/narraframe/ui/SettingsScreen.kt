package com.narraframe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.narraframe.platform.dataDirectorySizeBytes
import com.narraframe.platform.revealInFileManager
import com.narraframe.ui.components.*
import com.narraframe.ui.setup.*
import com.narraframe.ui.theme.*
import com.narraframe.viewmodel.Screen
import com.narraframe.viewmodel.StudioViewModel

private enum class SettingsTab(val label: String) { MODELS("Models"), VOICE("Voice"), VIDEO("Video"), STORAGE("Storage") }

/** Same sections as the setup wizard, plus video and storage options. */
@Composable
fun SettingsScreen(viewModel: StudioViewModel) {
    val setup = viewModel.setup
    var tab by remember { mutableStateOf(SettingsTab.MODELS) }

    Column(Modifier.fillMaxSize().appBackground()) {
        TopBar(viewModel, title = "Settings", onBack = { setup.stopPreview(); viewModel.navigate(Screen.STUDIO) })
        Row(Modifier.weight(1f)) {
            // Tabs
            Column(Modifier.width(220.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsTab.entries.forEach { t ->
                    NavigationDrawerItem(
                        label = { Text(t.label) },
                        selected = tab == t,
                        onClick = { setup.stopPreview(); tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    SettingsTab.MODELS  -> Icons.Default.Psychology
                                    SettingsTab.VOICE   -> Icons.Default.RecordVoiceOver
                                    SettingsTab.VIDEO   -> Icons.Default.Movie
                                    SettingsTab.STORAGE -> Icons.Default.Storage
                                },
                                null
                            )
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = Accent.copy(alpha = 0.14f),
                            unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            selectedTextColor = TextPrimary,
                            selectedIconColor = Accent,
                            unselectedTextColor = TextMuted,
                            unselectedIconColor = TextMuted
                        )
                    )
                }
            }
            VerticalDivider(color = Outline)
            Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 820.dp).padding(horizontal = 32.dp, vertical = 28.dp)) {
                    if (tab != SettingsTab.VIDEO && tab != SettingsTab.STORAGE && setup.tasks.isNotEmpty()) {
                        PendingInstalls(viewModel)
                        Spacer(Modifier.height(28.dp))
                    }
                    when (tab) {
                        SettingsTab.MODELS -> {
                            ComputeSection(setup)
                            Spacer(Modifier.height(36.dp))
                            TextModelSection(setup)
                            Spacer(Modifier.height(36.dp))
                            ImageModelSection(setup)
                        }
                        SettingsTab.VOICE   -> VoiceSection(setup)
                        SettingsTab.VIDEO   -> VideoSection(viewModel)
                        SettingsTab.STORAGE -> StorageSection(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingInstalls(viewModel: StudioViewModel) {
    val setup = viewModel.setup
    Panel(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Download, null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Text("Your selection needs a few downloads", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (setup.installing) {
                OutlinedButton(onClick = setup::cancelInstall) { Text("Cancel") }
            } else {
                GradientButton("Install · ~${formatSize(setup.pendingDownloadMB)}", onClick = setup::installAll)
            }
        }
        Spacer(Modifier.height(14.dp))
        InstallList(setup)
    }
}

@Composable
private fun VideoSection(viewModel: StudioViewModel) {
    SectionTitle("Scenes", "How long each image stays on screen. Shorter means more scenes and longer render time.")
    Spacer(Modifier.height(12.dp))
    SliderRow("Seconds per scene", viewModel.imageInterval, 3.0..30.0, steps = 26, format = { "${it.toInt()} s" }) {
        viewModel.updateImageInterval(it.toInt().toDouble())
    }

    Spacer(Modifier.height(32.dp))
    SectionTitle("Transitions")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("none" to "Cut", "fade" to "Crossfade", "zoom_in" to "Zoom in", "zoom_out" to "Zoom out").forEach { (id, label) ->
            OptionCard(selected = viewModel.transitionType == id, onClick = { viewModel.updateTransition(id) }, modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    if (viewModel.transitionType != "none") {
        SliderRow("Crossfade", viewModel.crossfade, 0.1..2.0, steps = 18, format = { "%.1f s".format(it) }) {
            viewModel.updateCrossfade((it * 10).toInt() / 10.0)
        }
    }
    if (viewModel.transitionType.startsWith("zoom")) {
        SliderRow("Zoom amount", viewModel.zoomAmount, 1.05..1.5, steps = 8, format = { "${((it - 1) * 100).toInt()} %" }) {
            viewModel.updateZoom((it * 100).toInt() / 100.0)
        }
    }

    Spacer(Modifier.height(32.dp))
    SectionTitle("Resolution")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("720p", "1080p", "4K").forEach { r ->
            OptionCard(selected = viewModel.videoResolution == r, onClick = { viewModel.updateResolution(r) }, modifier = Modifier.weight(1f)) {
                Text(r, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    steps: Int,
    format: (Double) -> String,
    onChange: (Double) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMuted, modifier = Modifier.width(160.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = steps,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(format(value), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun StorageSection(viewModel: StudioViewModel) {
    val setup = viewModel.setup
    StorageSection(setup)

    Spacer(Modifier.height(32.dp))
    SectionTitle("Downloaded engines and models", "Remove a model by deleting its file in this folder.")
    Spacer(Modifier.height(12.dp))
    var size by remember(setup.dataDir) { mutableStateOf<Long?>(null) }
    LaunchedEffect(setup.dataDir, setup.movingData) { size = dataDirectorySizeBytes() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            size?.let { formatSize((it / (1024 * 1024)).toInt()) } ?: "Calculating…",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.width(16.dp))
        OutlinedButton(onClick = { revealInFileManager(setup.dataDir) }, enabled = setup.dataDirAvailable) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Show in folder")
        }
    }

    Spacer(Modifier.height(32.dp))
    SectionTitle("Setup", "Walk through the first-launch wizard again. Your current choices are kept.")
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { viewModel.navigate(Screen.SETUP) }, enabled = !viewModel.state.isGenerating) {
        Icon(Icons.Default.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Run setup again")
    }
}
