package com.narraframe.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.narraframe.platform.hardwareSummary
import com.narraframe.ui.components.*
import com.narraframe.ui.theme.*
import com.narraframe.viewmodel.StudioViewModel

private enum class WizardStep(val title: String) {
    WELCOME("Welcome"), STORAGE("Storage"), TEXT("Text model"),
    IMAGE("Image model"), VOICE("Voice"), INSTALL("Install"), DONE("Done")
}

/** First-launch setup: one decision per screen, then one install queue. */
@Composable
fun SetupWizard(viewModel: StudioViewModel) {
    val setup = viewModel.setup
    var step by remember { mutableStateOf(WizardStep.WELCOME) }
    val steps = WizardStep.entries

    Column(Modifier.fillMaxSize().appBackground()) {
        // Header with progress dots
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            steps.drop(1).dropLast(1).forEach { s ->
                StepDot(s.title, done = s.ordinal < step.ordinal, active = s == step)
                if (s != WizardStep.INSTALL) Spacer(Modifier.width(18.dp))
            }
        }
        HorizontalDivider(color = Outline)

        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.weight(1f)
        ) { s ->
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 820.dp).padding(horizontal = 32.dp, vertical = 36.dp)) {
                    when (s) {
                        WizardStep.WELCOME -> Welcome()
                        WizardStep.STORAGE -> StorageSection(setup)
                        WizardStep.TEXT    -> TextModelSection(setup)
                        WizardStep.IMAGE   -> ImageModelSection(setup)
                        WizardStep.VOICE   -> VoiceSection(setup)
                        WizardStep.INSTALL -> InstallStep(viewModel)
                        WizardStep.DONE    -> Done()
                    }
                }
            }
        }

        HorizontalDivider(color = Outline)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step != WizardStep.WELCOME && step != WizardStep.DONE) {
                // Installs keep running in the background, so going back is always allowed
                TextButton(onClick = { setup.stopPreview(); step = steps[step.ordinal - 1] }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Back")
                }
            }
            Spacer(Modifier.weight(1f))
            when (step) {
                WizardStep.INSTALL -> {
                    if (setup.installing) {
                        OutlinedButton(onClick = setup::cancelInstall) { Text("Cancel") }
                    } else if (!setup.everythingInstalled) {
                        GradientButton(
                            "Install all · ~${formatSize(setup.pendingDownloadMB)}",
                            onClick = setup::installAll,
                            icon = Icons.Default.Download
                        )
                    } else {
                        GradientButton("Continue", onClick = { step = WizardStep.DONE }, icon = Icons.AutoMirrored.Filled.ArrowForward)
                    }
                }
                WizardStep.DONE -> GradientButton("Open Narraframe", onClick = viewModel::finishSetup, icon = Icons.Default.AutoAwesome)
                WizardStep.TEXT -> GradientButton(
                    "Next",
                    onClick = { step = steps[step.ordinal + 1] },
                    enabled = setup.llmMode != "server" || (setup.llmUrl.isNotBlank() && setup.llmServerModel.isNotBlank()),
                    icon = Icons.AutoMirrored.Filled.ArrowForward
                )
                WizardStep.STORAGE -> GradientButton(
                    "Next",
                    onClick = { step = steps[step.ordinal + 1] },
                    enabled = setup.libraryAvailable && setup.dataDirAvailable && setup.movingData == null,
                    icon = Icons.AutoMirrored.Filled.ArrowForward
                )
                else -> GradientButton(
                    if (step == WizardStep.WELCOME) "Get started" else "Next",
                    onClick = { setup.stopPreview(); step = steps[step.ordinal + 1] },
                    icon = Icons.AutoMirrored.Filled.ArrowForward
                )
            }
        }
    }
}

@Composable
private fun StepDot(label: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                when {
                    active -> Accent
                    done   -> SuccessGreen
                    else   -> Outline
                }
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) TextPrimary else TextMuted
        )
    }
}

@Composable
private fun Welcome() {
    Spacer(Modifier.height(24.dp))
    Text("Turn any script into a narrated video.", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "Narraframe writes image prompts, draws every scene and records the narration — all on this computer. " +
        "No account, no API keys, nothing leaves your machine.",
        style = MaterialTheme.typography.bodyLarge,
        color = TextMuted
    )
    Spacer(Modifier.height(36.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Feature(Icons.Default.RecordVoiceOver, "Narration", "Kokoro, Piper, Qwen3 or system voices")
        Feature(Icons.Default.Image, "Scenes", "Stable Diffusion, one image per scene")
        Feature(Icons.Default.Movie, "Video", "Transitions and zoom, exported as MP4")
    }
    Spacer(Modifier.height(36.dp))
    Panel(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = TextMuted)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("This computer", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text(hardwareSummary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Next you pick a text model, an image model and a voice. The defaults work well on most computers; " +
        "you can change everything later in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted
    )
}

@Composable
private fun RowScope.Feature(icon: ImageVector, title: String, text: String) {
    Panel(Modifier.weight(1f), padding = 18.dp) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}

@Composable
private fun InstallStep(viewModel: StudioViewModel) {
    val setup = viewModel.setup
    SectionTitle(
        "Ready to install",
        if (setup.everythingInstalled && !setup.installing) "Everything you picked is already on this computer."
        else "These downloads go to ${setup.dataDir} and run one after another. " +
            "${formatSize(setup.freeSpaceMB)} free on that drive. You can cancel and resume later."
    )
    Spacer(Modifier.height(20.dp))
    Panel(padding = 16.dp) {
        SummaryLine(Icons.Default.Notes, "Text model", when {
            setup.llmMode == "server" -> "${setup.llmServerModel} @ ${setup.llmUrl}"
            setup.llmModelId.endsWith(".gguf") -> setup.llmModelId.substringAfterLast('/')
            else -> com.narraframe.platform.availableLLMModels.find { it.id == setup.llmModelId }?.name ?: setup.llmModelId
        })
        SummaryLine(Icons.Default.Image, "Image model",
            com.narraframe.platform.availableSDModels.find { it.id == setup.sdModelId }?.name
                ?: setup.sdModelId.substringAfterLast('/'))
        SummaryLine(Icons.Default.RecordVoiceOver, "Voice", setup.voiceLabel)
        SummaryLine(Icons.Default.VideoLibrary, "Videos", setup.libraryPath)
        SummaryLine(Icons.Default.Memory, "Engines", setup.dataDir)
    }
    Spacer(Modifier.height(20.dp))
    InstallList(setup)
}

@Composable
private fun SummaryLine(icon: ImageVector, label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun Done() {
    Spacer(Modifier.height(40.dp))
    Box(Modifier.size(56.dp).clip(CircleShape).background(AccentGradient), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, null, tint = OnAccent, modifier = Modifier.size(30.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text("You're all set.", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(12.dp))
    Text(
        "Paste a script — a story, a documentary narration, an explainer — and press Generate. " +
        "Change models or voices any time from Settings.",
        style = MaterialTheme.typography.bodyLarge,
        color = TextMuted
    )
}
