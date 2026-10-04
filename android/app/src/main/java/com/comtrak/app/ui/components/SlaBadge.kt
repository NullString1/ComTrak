package com.comtrak.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comtrak.app.domain.model.SlaStatus

@Composable
fun SlaBadge(
    dueDateEpochMs: Long?,
    isCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    val sla = SlaStatus.calculate(dueDateEpochMs, isCompleted)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(sla.containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = sla.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = sla.contentColor
        )
    }
}
