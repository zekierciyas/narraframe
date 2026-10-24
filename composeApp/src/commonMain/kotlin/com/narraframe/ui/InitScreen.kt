package com.narraframe.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.narraframe.ui.theme.SurfaceDark
import com.narraframe.viewmodel.AppInitState

/**
 * Full-screen composable shown while the app initializes (e.g. downloads FFmpeg).
 * Transitions automatically to [MainStudioScreen] once [state] is [AppInitState.Ready].
 */
@Composable
fun InitScreen(
    state:     AppInitState,
    onRetry:   () -> Unit
) {
    Box(
        modifier          = Modifier.fillMaxSize().background(SurfaceDark),
        contentAlignment  = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier            = Modifier.widthIn(max = 480.dp).padding(32.dp)
        ) {

            // App title
            Text(
                "Narraframe",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            when (state) {
                is AppInitState.Loading -> LoadingContent(state.message)
                is AppInitState.Failed  -> FailedContent(state.reason, onRetry)
                AppInitState.Ready      -> {}   // parent will navigate away immediately
            }
        }
    }
}

// ── Loading state ─────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(message: String) {
    // Spinning progress ring
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val angle by infiniteTransition.animateFloat(
        initialValue   = 0f,
        targetValue    = 360f,
        animationSpec  = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label          = "rotation"
    )

    CircularProgressIndicator(
        modifier    = Modifier.size(56.dp).rotate(angle),
        strokeWidth = 3.dp,
        color       = MaterialTheme.colorScheme.primary
    )

    // Gradient divider
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), Color.Transparent)
                )
            )
    )

    // Status message
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            message,
            modifier    = Modifier.padding(16.dp),
            style       = MaterialTheme.typography.bodyMedium,
            color       = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign   = TextAlign.Center
        )
    }

    Text(
        "This may take a moment on first launch",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        textAlign = TextAlign.Center
    )
}

// ── Failed state ──────────────────────────────────────────────────────────

@Composable
private fun FailedContent(reason: String, onRetry: () -> Unit) {
    // Error icon placeholder
    Surface(
        shape  = RoundedCornerShape(50),
        color  = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text("✕", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }

    Text(
        "Initialization Failed",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error
    )

    // Error detail card
    Surface(
        shape    = RoundedCornerShape(12.dp),
        color    = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            reason,
            modifier  = Modifier.padding(16.dp),
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Start
        )
    }

    Text(
        "Check your internet connection and try again.\n" +
        "FFmpeg will be downloaded only once (~70 MB).",
        style     = MaterialTheme.typography.bodySmall,
        color     = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Button(
        onClick  = onRetry,
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Text("Retry")
    }
}
