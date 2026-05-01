package com.kubekubedashdash.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.check_filled
import org.jetbrains.compose.resources.painterResource

internal data class ThemePreviewColors(
    val sidebar: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
)

internal val DarkPreviewColors = ThemePreviewColors(
    sidebar = Color(0xFF161819),
    background = Color(0xFF1E2124),
    surface = Color(0xFF252A31),
    text = Color(0xFFC8D1DC),
)

internal val LightPreviewColors = ThemePreviewColors(
    sidebar = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFE2E8F0),
    text = Color(0xFF1E293B),
)

@Composable
internal fun ThemePreviewCard(
    label: String,
    selected: Boolean,
    primaryColors: ThemePreviewColors,
    secondaryColors: ThemePreviewColors? = null,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) KdPrimary else KdBorder
    val borderWidth = if (selected) 2.dp else 1.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 160.dp, height = 110.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
        ) {
            if (secondaryColors == null) {
                ThemeMockup(primaryColors, modifier = Modifier.fillMaxSize())
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    ThemeMockup(
                        primaryColors,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ThemeMockup(
                        secondaryColors,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(KdPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(Res.drawable.check_filled),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, KdBorder, CircleShape),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onBackground else KdTextSecondary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ThemeMockup(
    colors: ThemePreviewColors,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.sidebar)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.text.copy(alpha = if (it == 0) 0.3f else 0.12f)),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(3f)
                .fillMaxHeight()
                .background(colors.background)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.text.copy(alpha = 0.25f)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surface),
                    )
                }
            }
            repeat(3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.text.copy(alpha = 0.10f)),
                )
            }
        }
    }
}
