package com.kubedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kubedash.KdBorder
import com.kubedash.KdPrimary
import com.kubedash.KdSurface
import com.kubedash.KdSurfaceVariant
import com.kubedash.KdTextPrimary
import com.kubedash.KdTextSecondary
import com.kubedash.Screen

@Composable
fun TopBar(
    currentScreen: Screen,
    selectedNamespace: String,
    namespaces: List<String>,
    searchQuery: String,
    onNamespaceChange: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onBack: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = KdSurface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = KdTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            Text(
                currentScreen.title,
                style = MaterialTheme.typography.titleMedium,
                color = KdTextPrimary,
            )

            Spacer(Modifier.weight(1f))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = {
                    Text("Search...", style = MaterialTheme.typography.bodySmall, color = KdTextSecondary)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = KdTextSecondary)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(16.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = KdTextSecondary)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.width(220.dp).height(34.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = KdTextPrimary),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KdPrimary,
                    unfocusedBorderColor = KdBorder,
                    cursorColor = KdPrimary,
                    focusedContainerColor = KdSurfaceVariant,
                    unfocusedContainerColor = KdSurfaceVariant,
                ),
            )

            Spacer(Modifier.width(12.dp))

            NamespaceSelector(selectedNamespace, namespaces, onNamespaceChange)
        }
    }
}

@Composable
private fun NamespaceSelector(
    selectedNamespace: String,
    namespaces: List<String>,
    onNamespaceChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(32.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = KdTextPrimary),
            border = ButtonDefaults.outlinedButtonBorder(true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(KdBorder),
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Icon(Icons.Default.FolderSpecial, null, Modifier.size(14.dp), tint = KdTextSecondary)
            Spacer(Modifier.width(6.dp))
            Text(selectedNamespace, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, null, Modifier.size(14.dp))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(KdSurface)
                .heightIn(max = 400.dp),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        "All Namespaces",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedNamespace == "All Namespaces") KdPrimary else KdTextPrimary,
                    )
                },
                onClick = {
                    onNamespaceChange("All Namespaces")
                    expanded = false
                },
                leadingIcon = {
                    if (selectedNamespace == "All Namespaces") {
                        Icon(Icons.Default.Check, null, tint = KdPrimary, modifier = Modifier.size(16.dp))
                    }
                },
            )
            HorizontalDivider(color = KdBorder)
            namespaces.forEach { ns ->
                DropdownMenuItem(
                    text = {
                        Text(
                            ns,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ns == selectedNamespace) KdPrimary else KdTextPrimary,
                        )
                    },
                    onClick = {
                        onNamespaceChange(ns)
                        expanded = false
                    },
                    leadingIcon = {
                        if (ns == selectedNamespace) {
                            Icon(Icons.Default.Check, null, tint = KdPrimary, modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
    }
}
