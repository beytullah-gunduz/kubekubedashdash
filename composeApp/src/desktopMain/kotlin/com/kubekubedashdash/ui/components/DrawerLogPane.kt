package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.services.ActiveLogStream
import com.kubekubedashdash.ui.screens.logviewer.LogLine

@Composable
fun DrawerLogPane(stream: ActiveLogStream, modifier: Modifier = Modifier) {
    val lines by stream.lines.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize().padding(8.dp),
        ) {
            items(lines) { line ->
                LogLine(line, highlight = "", wrap = false)
            }
        }
    }
}
