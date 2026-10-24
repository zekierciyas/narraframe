package com.narraframe.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.narraframe.platform.readLocalFileBytes
import com.narraframe.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

// ── Image loading ──────────────────────────────────────────────────────────

private object ImageCache {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 120
    }
    @Synchronized fun get(key: String): ImageBitmap? = cache[key]
    @Synchronized fun put(key: String, bmp: ImageBitmap) { cache[key] = bmp }
}

/** Loads an image file off the main thread and caches it. Returns null while loading or if missing. */
@Composable
fun rememberImageFile(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf(path?.let { ImageCache.get(it) }) }
    LaunchedEffect(path) {
        if (path == null || bitmap != null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching { readLocalFileBytes(path)?.decodeToImageBitmap() }.getOrNull()
        }?.also { ImageCache.put(path, it) }
    }
    return bitmap
}

// ── Layout helpers ─────────────────────────────────────────────────────────

/** Window background: graphite with a faint teal and lime glow in the top corners. */
fun Modifier.appBackground(): Modifier = this
    .background(Ink)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Accent.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.12f, 0f),
                radius = size.maxDimension * 0.55f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(AccentEnd.copy(alpha = 0.05f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.05f),
                radius = size.maxDimension * 0.4f
            )
        )
    }

/** Rounded panel on [Surface1] with a hairline outline. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding:  Dp = 20.dp,
    content:  @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides TextPrimary) {
        Column(
            modifier
                .clip(MaterialTheme.shapes.large)
                .background(Surface1)
                .border(1.dp, Outline, MaterialTheme.shapes.large)
                .padding(padding),
            content = content
        )
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
    }
}

/** Small rounded label, e.g. "Recommended" or "Installed". */
@Composable
fun Badge(text: String, color: Color = Accent, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun InstalledBadge() = Badge("Installed", SuccessGreen, Icons.Default.CheckCircle)

/**
 * Selectable card used for models, engines and voices.
 * The selected card gets an accent border and a tinted background.
 */
@Composable
fun OptionCard(
    selected: Boolean,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    enabled:  Boolean = true,
    content:  @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(
        when {
            selected -> Accent
            hovered && enabled -> TextMuted.copy(alpha = 0.6f)
            else -> Outline
        }
    )
    CompositionLocalProvider(LocalContentColor provides TextPrimary) { Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) Accent.copy(alpha = 0.08f) else Surface2)
            .border(if (selected) 1.5.dp else 1.dp, border, MaterialTheme.shapes.medium)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        content = content
    ) }
}

/** Primary call-to-action with the accent gradient. */
@Composable
fun GradientButton(
    text:     String,
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
    enabled:  Boolean = true,
    icon:     ImageVector? = null
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) AccentGradient else Brush.horizontalGradient(listOf(Surface2, Surface2)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (enabled) OnAccent else TextMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = if (enabled) OnAccent else TextMuted,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Thin progress bar with the accent gradient. [progress] null = indeterminate shimmer. */
@Composable
fun GradientProgress(progress: Float?, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val shape = RoundedCornerShape(50)
    Box(modifier.height(height).clip(shape).background(Surface2)) {
        if (progress != null) {
            val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(400))
            Box(Modifier.fillMaxHeight().fillMaxWidth(animated).clip(shape).background(AccentGradient))
        } else {
            val t = rememberInfiniteTransition()
            val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .offset(x = maxWidth * (x * 1.4f - 0.4f))
                        .width(maxWidth * 0.4f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(AccentGradient)
                )
            }
        }
    }
}

/** Pulsing placeholder for content that is still being generated. */
@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition()
    val alpha by t.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Box(modifier.background(Surface2.copy(alpha = alpha)))
}

/** Wordmark: gradient dot + "Narraframe". */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(AccentGradient))
        Spacer(Modifier.width(10.dp))
        Text("Narraframe", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
    }
}

/** Formats a size in MB as "820 MB" or "1.8 GB". */
fun formatSize(mb: Int): String = if (mb >= 1000) "%.1f GB".format(mb / 1000.0) else "$mb MB"
