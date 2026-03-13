package com.kubekubedashdash.ui.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.logging.AppLogStore

@Composable
fun LogsScreen() {
    val entries by AppLogStore.entries.collectAsState()
    val listState = rememberLazyListState()
    var isReady by remember { mutableStateOf(false) }
    var itemCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        isReady = true
    }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty() && isReady) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    if (!isReady) return

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Application Logs",
                    style = MaterialTheme.typography.headlineMedium,
                    color = KdTextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${entries.size} entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = KdTextSecondary,
                )
            }
            IconButton(onClick = { AppLogStore.clear() }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Clear logs",
                    tint = KdTextSecondary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No log entries yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = KdTextSecondary,
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(KdSurface)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            LogEntryRow(entry)
                        }
                    }
                }
            }
        }
    }
}
