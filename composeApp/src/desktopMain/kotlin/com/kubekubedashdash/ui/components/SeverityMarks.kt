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

/** One threshold rule for every gauge, usage bar and quota row (was two: `> 0.70f` vs `< 0.7f`). */
internal enum class UsageTier { OK, WARN, CRITICAL }

internal fun usageTier(fraction: Float): UsageTier = when {
    fraction > 0.85f -> UsageTier.CRITICAL
    fraction > 0.70f -> UsageTier.WARN
    else -> UsageTier.OK
}

internal fun UsageTier.color(): Color = when (this) {
    UsageTier.OK -> KdSuccess
    UsageTier.WARN -> KdWarning
    UsageTier.CRITICAL -> KdError
}

/** Says the tier without colour (D18); draws nothing for OK. */
@Composable
internal fun UsageTierGlyph(tier: UsageTier, size: Dp = 12.dp) {
    val icon = when (tier) {
        UsageTier.OK -> return
        UsageTier.WARN -> Res.drawable.warning_filled
        UsageTier.CRITICAL -> Res.drawable.error_filled
    }
    Icon(
        painterResource(icon),
        contentDescription = if (tier == UsageTier.WARN) "High usage" else "Critical usage",
        tint = tier.color(),
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
