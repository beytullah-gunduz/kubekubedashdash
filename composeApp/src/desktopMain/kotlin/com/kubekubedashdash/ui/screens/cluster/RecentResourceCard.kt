package com.kubekubedashdash.ui.screens.cluster

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kubekubedashdash.KdBorder
import com.kubekubedashdash.KdError
import com.kubekubedashdash.KdPrimary
import com.kubekubedashdash.KdSurface
import com.kubekubedashdash.KdTextPrimary
import com.kubekubedashdash.KdTextSecondary
import com.kubekubedashdash.ui.screens.cluster.viewmodel.CLUSTER_OVERVIEW_RECENT_LIMIT
import com.kubekubedashdash.ui.screens.cluster.viewmodel.RecentSlice

@Composable
internal fun <T> RecentResourceCard(
    title: String,
    slice: RecentSlice<T>,
    emptyLabel: String,
    viewAllLabel: (total: Int) -> String,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (T) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = KdSurface,
        border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(KdBorder)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = KdTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            when {
                slice.loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = KdPrimary,
                    )
                }

                slice.errorMessage != null -> Text(
                    slice.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = KdError,
                )

                slice.items.isEmpty() -> Text(
                    emptyLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = KdTextSecondary,
                )

                else -> Column { slice.items.forEach { row(it) } }
            }
            if (slice.total > CLUSTER_OVERVIEW_RECENT_LIMIT) {
                Spacer(Modifier.height(8.dp))
                Text(
                    viewAllLabel(slice.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = KdPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onViewAll)
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}
