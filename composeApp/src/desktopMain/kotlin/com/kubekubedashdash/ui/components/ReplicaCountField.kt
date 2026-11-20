package com.kubekubedashdash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.add_filled
import com.kubekubedashdash.resources.remove_filled
import org.jetbrains.compose.resources.painterResource

/**
 * A digits-only replica count input with −/+ stepper buttons. The raw text is
 * owned by the caller; [onRawInputChange] is called only with "" or all-digit
 * strings.
 */
@Composable
fun ReplicaCountField(
    rawInput: String,
    onRawInputChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = {
                val cur = rawInput.toIntOrNull() ?: 0
                if (cur > 0) onRawInputChange((cur - 1).toString())
            },
            enabled = enabled && (rawInput.toIntOrNull() ?: 0) > 0,
            shape = kdRoundShape,
        ) {
            Icon(painterResource(Res.drawable.remove_filled), contentDescription = "Decrease")
        }
        OutlinedTextField(
            value = rawInput,
            onValueChange = { v ->
                if (v.isEmpty() || v.all { it.isDigit() }) onRawInputChange(v)
            },
            singleLine = true,
            enabled = enabled,
            isError = isError,
            modifier = Modifier.width(96.dp),
        )
        IconButton(
            onClick = {
                val cur = rawInput.toIntOrNull() ?: 0
                onRawInputChange((cur + 1).toString())
            },
            enabled = enabled,
            shape = kdRoundShape,
        ) {
            Icon(painterResource(Res.drawable.add_filled), contentDescription = "Increase")
        }
    }
}
