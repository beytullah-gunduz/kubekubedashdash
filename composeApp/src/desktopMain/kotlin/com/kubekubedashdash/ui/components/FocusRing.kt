package com.kubekubedashdash.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary

fun Modifier.kdFocusRing(): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    onFocusChanged { isFocused = it.isFocused }
        .then(if (isFocused) border(2.dp, KdPrimary, RoundedCornerShape(6.dp)) else Modifier)
}
