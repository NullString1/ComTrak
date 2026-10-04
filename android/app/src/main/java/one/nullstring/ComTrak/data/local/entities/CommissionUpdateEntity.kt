package one.nullstring.ComTrak.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "commission_updates",
    foreignKeys = [
        ForeignKey(
            entity = CommissionEntity::class,
            parentColumns = ["id"],
            childColumns = ["commissionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["commissionId"]),
        Index(value = ["createdAt"])
    ]
)
data class CommissionUpdateEntity(
    @PrimaryKey
    val id: String, // Client-generated UUID
    val commissionId: String,
    val fromStatus: String?,
    val toStatus: String?,
    val note: String?,
    val createdAt: Long,
    val deletedAt: Long? = null,

    // Offline sync tracking fields
    val syncPending: Boolean = false
)
