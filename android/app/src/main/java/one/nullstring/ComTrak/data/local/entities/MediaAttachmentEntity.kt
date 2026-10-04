package one.nullstring.ComTrak.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_attachments",
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
        Index(value = ["uploadStatus"])
    ]
)
data class MediaAttachmentEntity(
    @PrimaryKey
    val id: String, // Client-generated UUID
    val commissionId: String,
    val updateId: String?,
    val mediaType: String, // 'reference', 'progress', 'finished'
    val localPath: String, // Local compressed WebP file path
    val remoteUrl: String?, // Backend streaming URL /api/v1/media/{id}
    val mimeType: String = "image/webp",
    val fileSizeBytes: Long = 0,
    val uploadStatus: String = "PENDING", // 'PENDING', 'UPLOADING', 'UPLOADED'
    val createdAt: Long,
    val deletedAt: Long? = null,
    val syncPending: Boolean = false
)
