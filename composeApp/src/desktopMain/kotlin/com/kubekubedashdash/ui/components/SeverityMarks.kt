package com.kubekubedashdash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdSuccess
import com.kubekubedashdash.KdWarning
import com.kubekubedashdash.kdRoundShape
import com.kubekubedashdash.resources.Res
import com.kubekubedashdash.resources.error_filled
import com.kubekubedashdash.resources.warning_filled
import org.jetbrains.compose.resources.painterResource

/** A usage level's status colour, the same three tokens as every other status mark. */
internal fun UsageLevel.color(): Color = when (this) {
    UsageLevel.NORMAL -> KdSuccess
    UsageLevel.WARNING -> KdWarning
    UsageLevel.CRITICAL -> KdError
}

/** Says the level without colour (D18); draws nothing for NORMAL. */
@Composable
internal fun UsageLevelGlyph(level: UsageLevel, size: Dp = 12.dp) {
    val icon = when (level) {
        UsageLevel.NORMAL -> return
        UsageLevel.WARNING -> Res.drawable.warning_filled
        UsageLevel.CRITICAL -> Res.drawable.error_filled
    }
    Icon(
        painterResource(icon),
        contentDescription = if (level == UsageLevel.WARNING) "High usage" else "Critical usage",
        tint = level.color(),
        modifier = Modifier.size(size),
    )
}

/** A severity dot that differs by shape as well as colour (D18): filled = error, hollow ring = warning. */
@Composable
internal fun SeverityDot(color: Color, filled: Boolean, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(
        modifier
            .size(size)
            .clip(kdRoundShape)
            .then(if (filled) Modifier.background(color) else Modifier.border(1.5.dp, color, kdRoundShape)),
    )
}
