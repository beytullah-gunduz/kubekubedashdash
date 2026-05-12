package com.kubekubedashdash.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.logging.AppLogStore
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.delete_filled
import com.kubekubedashdash.resources.folder_open_filled
import com.kubekubedashdash.ui.screens.logs.LogEntryRow
import com.kubekubedashdash.util.SystemDirectories
import kotlinx.coroutines.flow.sample
import org.jetbrains.compose.resources.painterResource
import java.awt.Desktop
import java.io.File

private fun openLogsFolder() {
    val dir = File(SystemDirectories.logsDirectory)
    runCatching {
        dir.mkdirs()
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(dir)
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun DrawerAppLogPane(modifier: Modifier = Modifier) {
    val entries by AppLogStore.entries.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshotFlow { entries.size }
            .sample(200)
            .collect { size ->
                if (size > 0) listState.animateScrollToItem(size - 1)
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${entries.size} entries",
                color = KdTextSecondary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { openLogsFolder() },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    painterResource(Res.drawable.folder_open_filled),
                    contentDescription = "Open log folder",
                    modifier = Modifier.size(14.dp),
                    tint = KdTextSecondary,
                )
            }
            IconButton(
                onClick = { AppLogStore.clear() },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    painterResource(Res.drawable.delete_filled),
                    contentDescription = "Clear logs",
                    modifier = Modifier.size(14.dp),
                    tint = KdTextSecondary,
                )
            }
        }

        if (entries.isEmpty()) {
            return@Column
        }

        Box(modifier = Modifier.fillMaxSize()) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}
