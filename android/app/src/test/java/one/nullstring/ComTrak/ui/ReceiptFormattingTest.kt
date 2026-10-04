package one.nullstring.ComTrak.ui

import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import one.nullstring.ComTrak.ui.list.buildReceiptText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReceiptFormattingTest {

    @Test
    fun testBuildReceiptText_fullDetailsIncluded() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.OCTOBER, 25, 12, 0, 0)
        }

        val commission = CommissionEntity(
            id = "test-receipt-id-1",
            title = "Set of Ramen Bowls",
            description = "Deep flared bowls with chopstick notches",
            customerName = "Chef Marcus",
            customerPhone = "+44 7700 900222",
            customerEmail = "marcus@ramen.co.uk",
            itemName = "Ramen Bowl",
            size = "20cm diameter x 10cm depth",
            colour = "Tenmoku Rust with speckled rim",
            quantity = 4,
            priceCents = 16000, // $160.00
            dueDate = calendar.timeInMillis,
            status = "ready_to_begin",
            clayBody = "Toasted Stoneware",
            glazes = "Tenmoku + Oatmeal Overdip",
            thumbnailMediaId = null,
            createdAt = 1000,
            updatedAt = 1000,
            deletedAt = null,
            version = 1,
            syncPending = false
        )

        val receipt = buildReceiptText(commission)

        assertTrue(receipt.contains("Project: Set of Ramen Bowls"))
        assertTrue(receipt.contains("Customer: Chef Marcus"))
        assertTrue(receipt.contains("Phone: +44 7700 900222"))
        assertTrue(receipt.contains("Email: marcus@ramen.co.uk"))
        assertTrue(receipt.contains("Item: Ramen Bowl"))
        assertTrue(receipt.contains("Quantity: 4"))
        assertTrue(receipt.contains("Dimensions: 20cm diameter x 10cm depth"))
        assertTrue(receipt.contains("Target Colour: Tenmoku Rust with speckled rim"))
        assertTrue(receipt.contains("Price: $160.00"))
        assertTrue(receipt.contains("Status: Ready to Begin Making"))
        assertTrue(receipt.contains("Clay Body: Toasted Stoneware"))
        assertTrue(receipt.contains("Glazes: Tenmoku + Oatmeal Overdip"))
        assertTrue(receipt.contains("Notes: Deep flared bowls with chopstick notches"))
        assertTrue(receipt.contains("Tracked with ComTrak Studio"))
    }

    @Test
    fun testBuildReceiptText_minimalFieldsWithoutOptionals() {
        val commission = CommissionEntity(
            id = "test-receipt-minimal",
            title = "Quick Mug",
            description = null,
            customerName = "Walk-in Guest",
            customerPhone = null,
            customerEmail = null,
            itemName = "Mug",
            size = null,
            colour = null,
            quantity = 1,
            priceCents = null,
            dueDate = null,
            status = "requiring_details",
            clayBody = null,
            glazes = null,
            thumbnailMediaId = null,
            createdAt = 1000,
            updatedAt = 1000,
            deletedAt = null,
            version = 1,
            syncPending = false
        )

        val receipt = buildReceiptText(commission)

        assertTrue(receipt.contains("Project: Quick Mug"))
        assertTrue(receipt.contains("Customer: Walk-in Guest"))
        assertTrue(receipt.contains("Item: Mug"))
        assertTrue(receipt.contains("Quantity: 1"))
        assertTrue(receipt.contains("Price: Unset"))
        assertTrue(receipt.contains("Due Date: No SLA set"))
        assertTrue(receipt.contains("Status: Requiring Details"))

        // Optional fields should NOT have empty lines
        assertFalse(receipt.contains("Phone:"))
        assertFalse(receipt.contains("Email:"))
        assertFalse(receipt.contains("Dimensions:"))
        assertFalse(receipt.contains("Clay Body:"))
        assertFalse(receipt.contains("Glazes:"))
        assertFalse(receipt.contains("Notes:"))
    }
}
