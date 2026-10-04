package com.comtrak.app.domain.model

import androidx.compose.ui.graphics.Color

/**
 * 5 Core states of an artisan / pottery commission
 */
enum class CommissionState(
    val id: String,
    val displayName: String,
    val progress: Float,
    val color: Color
) {
    REQUIRING_DETAILS(
        id = "requiring_details",
        displayName = "Requiring Details",
        progress = 0.05f,
        color = Color(0xFFD97706) // Amber
    ),
    READY_TO_BEGIN(
        id = "ready_to_begin",
        displayName = "Ready to Begin Making",
        progress = 0.25f,
        color = Color(0xFF2563EB) // Blue
    ),
    NEED_GLAZING(
        id = "need_glazing",
        displayName = "Need Glazing",
        progress = 0.50f,
        color = Color(0xFF7C3AED) // Purple
    ),
    PAYMENT_AND_DELIVERY(
        id = "payment_and_delivery",
        displayName = "Payment & Delivery",
        progress = 0.75f,
        color = Color(0xFF0D9488) // Teal
    ),
    DONE(
        id = "done",
        displayName = "Done",
        progress = 1.0f,
        color = Color(0xFF16A34A) // Green
    );

    companion object {
        fun fromId(id: String): CommissionState {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: REQUIRING_DETAILS
        }
    }
}
