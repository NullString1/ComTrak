package one.nullstring.ComTrak.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "commissions",
    indices = [
        Index(value = ["dueDate"]),
        Index(value = ["updatedAt"]),
        Index(value = ["status"])
    ]
)
data class CommissionEntity(
    @PrimaryKey
    val id: String, // Client-generated UUID for offline creation
    val title: String,
    val description: String?,
    val customerName: String,
    val customerPhone: String?,
    val customerEmail: String?,
    val itemName: String,
    val size: String?,
    val colour: String?,
    val quantity: Long = 1,
    val priceCents: Long?,
    val dueDate: Long?, // Epoch ms
    val status: String, // 'requiring_details', 'ready_to_begin', 'need_glazing', 'payment_and_delivery', 'done'
    val clayBody: String?,
    val glazes: String?,
    val thumbnailMediaId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val version: Long = 1,

    // Offline sync tracking fields
    val syncPending: Boolean = false,
    val deletedLocally: Boolean = false
)
