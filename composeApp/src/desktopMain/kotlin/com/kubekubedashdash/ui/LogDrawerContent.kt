package com.kubekubedashdash.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.close_filled
import com.kubekubedashdash.services.LogStreamRegistry
import com.kubekubedashdash.ui.components.DrawerLogPane
import org.jetbrains.compose.resources.painterResource

@Composable
fun LogDrawerContent() {
    val streams by LogStreamRegistry.streams.collectAsState()

    if (streams.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Log streams stay open across navigation.\nOpen one from a pod's row menu — Cmd/Ctrl+J reopens this drawer.",
                style = MaterialTheme.typography.bodyMedium,
                color = KdTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
    } else {
        var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
        val streamList = streams.values.sortedBy { it.openedAt }
        val displayedKey = focusedKey?.takeIf { it in streams } ?: streamList.first().id.key
        val selectedIndex = streamList.indexOfFirst { it.id.key == displayedKey }.coerceAtLeast(0)

        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                streamList.forEach { stream ->
                    Tab(
                        selected = stream.id.key == displayedKey,
                        onClick = { focusedKey = stream.id.key },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stream.displayLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .pointerInput(stream.id) {
                                            detectTapGestures(onTap = { LogStreamRegistry.close(stream.id) })
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.close_filled),
                                        contentDescription = "Close ${stream.displayLabel}",
                                        modifier = Modifier.size(10.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            DrawerLogPane(
                stream = streams[displayedKey] ?: streamList.first(),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
