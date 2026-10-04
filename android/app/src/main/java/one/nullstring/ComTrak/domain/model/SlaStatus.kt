package one.nullstring.ComTrak.domain.model

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class SlaStatus(
    val label: String,
    val containerColor: Color,
    val contentColor: Color
) {
    class Overdue(days: Long) : SlaStatus(
        label = if (days == 1L) "Overdue by 1 day" else "Overdue by $days days",
        containerColor = Color(0xFFFEE2E2), // Red tint
        contentColor = Color(0xFFB91C1C)
    )

    object DueToday : SlaStatus(
        label = "Due Today",
        containerColor = Color(0xFFFEF3C7), // Amber tint
        contentColor = Color(0xFFB45309)
    )

    class DueSoon(days: Long) : SlaStatus(
        label = if (days == 1L) "Due tomorrow" else "Due in $days days",
        containerColor = Color(0xFFE0F2FE), // Blue tint
        contentColor = Color(0xFF0369A1)
    )

    class Scheduled(val formattedDate: String) : SlaStatus(
        label = "Due $formattedDate",
        containerColor = Color(0xFFF3F4F6), // Grey tint
        contentColor = Color(0xFF4B5563)
    )

    object NoDeadline : SlaStatus(
        label = "No SLA",
        containerColor = Color(0xFFF3F4F6),
        contentColor = Color(0xFF9CA3AF)
    )

    companion object {
        fun calculate(dueDateEpochMs: Long?, isCompleted: Boolean): SlaStatus {
            if (isCompleted) {
                return Scheduled("Completed")
            }
            if (dueDateEpochMs == null || dueDateEpochMs <= 0) {
                return NoDeadline
            }

            val now = System.currentTimeMillis()
            val diffMs = dueDateEpochMs - now
            val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)

            return when {
                diffMs < 0 -> {
                    val overdueDays = TimeUnit.MILLISECONDS.toDays(-diffMs).coerceAtLeast(1)
                    Overdue(overdueDays)
                }
                diffDays == 0L -> DueToday
                diffDays in 1..7 -> DueSoon(diffDays)
                else -> {
                    val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
                    Scheduled(sdf.format(Date(dueDateEpochMs)))
                }
            }
        }
    }
}
