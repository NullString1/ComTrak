package one.nullstring.ComTrak.domain

import one.nullstring.ComTrak.domain.model.CommissionState
import one.nullstring.ComTrak.domain.model.SlaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CommissionDomainTest {

    @Test
    fun testCommissionState_progressMonotonicallyIncreases() {
        val states = listOf(
            CommissionState.REQUIRING_DETAILS,
            CommissionState.READY_TO_BEGIN,
            CommissionState.NEED_GLAZING,
            CommissionState.PAYMENT_AND_DELIVERY,
            CommissionState.DONE
        )

        for (i in 0 until states.size - 1) {
            assertTrue(
                "Progress of ${states[i].id} (${states[i].progress}) must be strictly less than ${states[i+1].id} (${states[i+1].progress})",
                states[i].progress < states[i+1].progress
            )
        }
        assertEquals(1.0f, CommissionState.DONE.progress, 0.001f)
    }

    @Test
    fun testCommissionState_fromIdCaseInsensitiveAndFallback() {
        assertEquals(CommissionState.NEED_GLAZING, CommissionState.fromId("need_glazing"))
        assertEquals(CommissionState.NEED_GLAZING, CommissionState.fromId("NEED_GLAZING"))
        assertEquals(CommissionState.READY_TO_BEGIN, CommissionState.fromId("ready_to_begin"))
        assertEquals(CommissionState.DONE, CommissionState.fromId("done"))
        // Fallback for unknown state
        assertEquals(CommissionState.REQUIRING_DETAILS, CommissionState.fromId("unknown_custom_state"))
    }

    @Test
    fun testSlaStatus_completedItemAlwaysReturnsCompleted() {
        val now = System.currentTimeMillis()
        val overduePast = now - TimeUnit.DAYS.toMillis(10)

        // Even if due date is in the past, completed commissions must report completed
        val status = SlaStatus.calculate(dueDateEpochMs = overduePast, isCompleted = true)
        assertTrue(status is SlaStatus.Scheduled)
        assertEquals("Due Completed", status.label)
    }

    @Test
    fun testSlaStatus_noDeadlineReturnsNoSla() {
        assertEquals(SlaStatus.NoDeadline, SlaStatus.calculate(dueDateEpochMs = null, isCompleted = false))
        assertEquals(SlaStatus.NoDeadline, SlaStatus.calculate(dueDateEpochMs = 0L, isCompleted = false))
        assertEquals(SlaStatus.NoDeadline, SlaStatus.calculate(dueDateEpochMs = -100L, isCompleted = false))
    }

    @Test
    fun testSlaStatus_overdueCalculations() {
        val now = System.currentTimeMillis()

        val threeDaysAgo = now - TimeUnit.DAYS.toMillis(3) - 1000
        val statusThree = SlaStatus.calculate(threeDaysAgo, isCompleted = false)
        assertTrue(statusThree is SlaStatus.Overdue)
        assertEquals("Overdue by 3 days", statusThree.label)

        val oneDayAgo = now - TimeUnit.DAYS.toMillis(1) - 1000
        val statusOne = SlaStatus.calculate(oneDayAgo, isCompleted = false)
        assertTrue(statusOne is SlaStatus.Overdue)
        assertEquals("Overdue by 1 day", statusOne.label)
    }

    @Test
    fun testSlaStatus_dueSoonAndScheduled() {
        val now = System.currentTimeMillis()

        // Due tomorrow (between 1 and 2 days)
        val tomorrow = now + TimeUnit.DAYS.toMillis(1) + 1000
        val statusTomorrow = SlaStatus.calculate(tomorrow, isCompleted = false)
        assertTrue(statusTomorrow is SlaStatus.DueSoon)
        assertEquals("Due tomorrow", statusTomorrow.label)

        // Due in 4 days
        val fourDays = now + TimeUnit.DAYS.toMillis(4) + 1000
        val statusFour = SlaStatus.calculate(fourDays, isCompleted = false)
        assertTrue(statusFour is SlaStatus.DueSoon)
        assertEquals("Due in 4 days", statusFour.label)

        // Far future (30 days) -> Scheduled
        val farFuture = now + TimeUnit.DAYS.toMillis(30)
        val statusScheduled = SlaStatus.calculate(farFuture, isCompleted = false)
        assertTrue(statusScheduled is SlaStatus.Scheduled)
        assertTrue(statusScheduled.label.startsWith("Due "))
    }
}
