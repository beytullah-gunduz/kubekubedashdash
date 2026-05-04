package com.kubekubedashdash.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.filter_list_filled
import org.jetbrains.compose.resources.painterResource

@Composable
fun ColumnFilterDropdown(
    expanded: Boolean,
    active: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    availableValues: Set<String>,
    selectedValues: Set<String>,
    onToggleValue: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
) {
    Box {
        Icon(
            painterResource(Res.drawable.filter_list_filled),
            contentDescription = "Filter",
            modifier = Modifier
                .size(14.dp)
                .padding(start = 2.dp)
                .clickable(onClick = onToggle),
            tint = if (active) KdPrimary else KdTextSecondary,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "All",
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onSelectAll).padding(8.dp),
                )
                Text(
                    "None",
                    color = KdPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onSelectNone).padding(8.dp),
                )
            }
            HorizontalDivider()
            availableValues.sorted().forEach { value ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = value in selectedValues,
                                onCheckedChange = { onToggleValue(value) },
                                colors = CheckboxDefaults.colors(checkedColor = KdPrimary),
                            )
                            Text(value)
                        }
                    },
                    onClick = { onToggleValue(value) },
                )
            }
        }
    }
}
