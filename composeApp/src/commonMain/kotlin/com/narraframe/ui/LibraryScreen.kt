package com.narraframe.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.narraframe.models.Generation
import com.narraframe.ui.components.*
import com.narraframe.ui.theme.*
import com.narraframe.viewmodel.Screen
import com.narraframe.viewmodel.StudioViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** Grid of every generation in the library folder. */
@Composable
fun LibraryScreen(viewModel: StudioViewModel) {
    var pendingDelete by remember { mutableStateOf<Generation?>(null) }

    Column(Modifier.fillMaxSize().appBackground()) {
        TopBar(viewModel, title = "Library", onBack = { viewModel.navigate(Screen.STUDIO) })
        val items = viewModel.library
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.VideoLibrary, null, tint = TextMuted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("No videos yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Videos you generate are saved in ${viewModel.setup.libraryPath}", color = TextMuted,
                     style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp),
                contentPadding = PaddingValues(32.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.path }) { gen ->
                    LibraryCard(
                        gen       = gen,
                        isRunning = viewModel.state.isGenerating && viewModel.current?.path == gen.path,
                        onOpen    = { viewModel.openGeneration(gen) },
                        onPlay    = { viewModel.playVideo(gen) },
                        onReveal  = { viewModel.revealInFolder(gen) },
                        onExport  = { viewModel.exportVideo(gen) },
                        onDelete  = { pendingDelete = gen }
                    )
                }
            }
        }
    }

    pendingDelete?.let { gen ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Move to Trash?") },
            text = { Text("“${gen.meta.title}” and all its files will be moved to the Trash.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteGeneration(gen); pendingDelete = null }) {
                    Text("Move to Trash", color = ErrorRed)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LibraryCard(
    gen:       Generation,
    isRunning: Boolean,
    onOpen:    () -> Unit,
    onPlay:    () -> Unit,
    onReveal:  () -> Unit,
    onExport:  () -> Unit,
    onDelete:  () -> Unit
) {
    val thumb = rememberImageFile(gen.thumbnailPath.takeIf { File(it).exists() })
    val hasVideo = remember(gen.path, gen.meta.status) { File(gen.videoPath).exists() }
    var menu by remember { mutableStateOf(false) }

    // Only the thumbnail is rounded; clipping the whole card would cut the first letters of the text
    Column(Modifier.clickable(onClick = onOpen)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
                .background(Surface1).border(1.dp, Outline, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Default.Movie, null, tint = TextMuted)
            if (hasVideo) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.PlayArrow, "Play", tint = Color.White) }
            }
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                when {
                    isRunning -> Badge("Generating", Accent)
                    hasVideo  -> if (gen.meta.durationSec > 0)
                        Badge(StudioViewModel.formatDuration(gen.meta.durationSec), Color.White)
                    else      -> Badge(if (gen.meta.status == "cancelled") "Stopped" else "Unfinished", WarningOrange)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(gen.meta.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    SimpleDateFormat("d MMM yyyy, HH:mm").format(Date(gen.meta.createdAt)) +
                        (if (gen.meta.sceneCount > 0) " · ${gen.meta.sceneCount} scenes" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "More", tint = TextMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open storyboard") }, leadingIcon = { Icon(Icons.Default.ViewModule, null) },
                        onClick = { menu = false; onOpen() })
                    if (hasVideo) {
                        DropdownMenuItem(text = { Text("Export video…") }, leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                            onClick = { menu = false; onExport() })
                    }
                    DropdownMenuItem(text = { Text("Show in folder") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                        onClick = { menu = false; onReveal() })
                    DropdownMenuItem(
                        text = { Text("Move to Trash", color = ErrorRed) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = ErrorRed) },
                        enabled = !isRunning,
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}
