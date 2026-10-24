package com.narraframe.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.narraframe.models.LogEntry
import com.narraframe.ui.theme.*
import com.narraframe.viewmodel.StudioViewModel

@Composable
fun LogsPanel(modifier: Modifier = Modifier, viewModel: StudioViewModel) {
    var filterLevel by remember { mutableStateOf<LogEntry.Level?>(null) }

    val logs = remember(viewModel.state.logs, filterLevel) {
        if (filterLevel != null) viewModel.state.logs.filter { it.level == filterLevel }
        else viewModel.state.logs
    }

    Column(modifier = modifier.background(Ink)) {

        // Toolbar
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .background(Surface1)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Logs (${viewModel.state.logs.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))

            // Level filter chips
            listOf(LogEntry.Level.ERROR, LogEntry.Level.WARNING, LogEntry.Level.SUCCESS, LogEntry.Level.INFO).forEach { lvl ->
                val selected = filterLevel == lvl
                FilterChip(
                    selected  = selected,
                    onClick   = { filterLevel = if (selected) null else lvl },
                    label     = { Text(lvl.name, style = MaterialTheme.typography.labelSmall) },
                    modifier  = Modifier.padding(end = 4.dp).height(24.dp),
                    colors    = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = levelColor(lvl).copy(alpha = 0.2f),
                        selectedLabelColor     = levelColor(lvl)
                    )
                )
            }

            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick  = { viewModel.clearLogs() },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Delete, "Clear logs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp))
            }
        }

        // Log list
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
        }

        LazyColumn(
            state    = listState,
            modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)
        ) {
            items(logs, key = { it.id }) { entry ->
                LogRow(entry)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (entry.level == LogEntry.Level.ERROR) Color.Red.copy(alpha = 0.05f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            entry.formattedTime,
            modifier  = Modifier.width(68.dp),
            style     = MaterialTheme.typography.labelSmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "[${entry.level.name}]",
            modifier  = Modifier.width(64.dp),
            style     = MaterialTheme.typography.labelSmall,
            color     = levelColor(entry.level),
            fontFamily = FontFamily.Monospace
        )
        entry.step?.let { step ->
            Text(
                "[${step.displayName}]",
                modifier  = Modifier.width(150.dp),
                style     = MaterialTheme.typography.labelSmall,
                color     = MaterialTheme.colorScheme.secondary,
                fontFamily = FontFamily.Monospace,
                maxLines  = 1
            )
        } ?: Spacer(Modifier.width(150.dp))
        Text(
            entry.message,
            style     = MaterialTheme.typography.labelSmall,
            color     = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            modifier  = Modifier.weight(1f)
        )
    }
}

@Composable
private fun levelColor(level: LogEntry.Level): Color = when (level) {
    LogEntry.Level.DEBUG   -> MaterialTheme.colorScheme.onSurfaceVariant
    LogEntry.Level.INFO    -> MaterialTheme.colorScheme.onSurface
    LogEntry.Level.WARNING -> WarningOrange
    LogEntry.Level.ERROR   -> MaterialTheme.colorScheme.error
    LogEntry.Level.SUCCESS -> SuccessGreen
}
