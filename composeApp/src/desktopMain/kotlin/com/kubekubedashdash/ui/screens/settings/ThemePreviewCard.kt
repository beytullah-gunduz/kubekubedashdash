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
import com.kubekubedashdash.KdOnPrimary
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ThemeStyle
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

internal val RetroDarkPreviewColors = ThemePreviewColors(
    sidebar = Color(0xFF0A0B1A),
    background = Color(0xFF12142B),
    surface = Color(0xFF1E2240),
    text = Color(0xFFE3E6F5),
)

internal val RetroLightPreviewColors = ThemePreviewColors(
    sidebar = Color(0xFFE6DCC3),
    background = Color(0xFFEFE7D2),
    surface = Color(0xFFF8F2E3),
    text = Color(0xFF2B2418),
)

/** Resolves the four preview palettes (D10): [ThemeStyle] × dark/light. */
internal fun previewColorsFor(style: ThemeStyle, dark: Boolean): ThemePreviewColors = when (style) {
    ThemeStyle.DEFAULT -> if (dark) DarkPreviewColors else LightPreviewColors
    ThemeStyle.RETRO -> if (dark) RetroDarkPreviewColors else RetroLightPreviewColors
}

@Composable
internal fun ThemePreviewCard(
    label: String,
    selected: Boolean,
    primaryColors: ThemePreviewColors,
    secondaryColors: ThemePreviewColors? = null,
    squared: Boolean = false,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) KdPrimary else KdBorder
    val borderWidth = if (selected) 2.dp else 1.dp
    val frameShape = if (squared) RoundedCornerShape(0.dp) else RoundedCornerShape(10.dp)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 160.dp, height = 110.dp)
                .clip(frameShape)
                .border(borderWidth, borderColor, frameShape)
                .clickable(onClick = onClick),
        ) {
            if (secondaryColors == null) {
                ThemeMockup(primaryColors, squared = squared, modifier = Modifier.fillMaxSize())
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    ThemeMockup(
                        primaryColors,
                        squared = squared,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    ThemeMockup(
                        secondaryColors,
                        squared = squared,
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
                        tint = KdOnPrimary,
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
    squared: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val thinBarShape = if (squared) RoundedCornerShape(0.dp) else RoundedCornerShape(3.dp)
    val wideBarShape = if (squared) RoundedCornerShape(0.dp) else RoundedCornerShape(4.dp)
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
                        .clip(thinBarShape)
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
                    .clip(wideBarShape)
                    .background(colors.text.copy(alpha = 0.25f)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                            .clip(wideBarShape)
                            .background(colors.surface),
                    )
                }
            }
            repeat(3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(thinBarShape)
                        .background(colors.text.copy(alpha = 0.10f)),
                )
            }
        }
    }
}
