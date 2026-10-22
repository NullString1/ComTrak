package one.nullstring.ComTrak.data.sync

import one.nullstring.ComTrak.data.local.AppDatabase
import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import one.nullstring.ComTrak.data.local.entities.CommissionUpdateEntity
import one.nullstring.ComTrak.data.local.entities.MediaAttachmentEntity
import one.nullstring.ComTrak.data.remote.CommissionDto
import one.nullstring.ComTrak.data.remote.CommissionUpdateDto
import one.nullstring.ComTrak.data.remote.MediaAttachmentDto
import one.nullstring.ComTrak.data.remote.SyncPullResponseDto

// Entity <-> DTO mappings shared by push and pull.

fun CommissionEntity.toDto(userId: String) = CommissionDto(
    id = id,
    user_id = userId,
    title = title,
    description = description,
    customer_name = customerName,
    customer_phone = customerPhone,
    customer_email = customerEmail,
    item_name = itemName,
    size = size,
    colour = colour,
    quantity = quantity,
    price_cents = priceCents,
    due_date = dueDate,
    status = status,
    clay_body = clayBody,
    glazes = glazes,
    thumbnail_media_id = thumbnailMediaId,
    created_at = createdAt,
    updated_at = updatedAt,
    deleted_at = deletedAt,
    version = version
)

fun CommissionUpdateEntity.toDto(userId: String) = CommissionUpdateDto(
    id = id,
    commission_id = commissionId,
    user_id = userId,
    from_status = fromStatus,
    to_status = toStatus,
    note = note,
    created_at = createdAt,
    deleted_at = deletedAt
)

fun MediaAttachmentEntity.toDto(userId: String) = MediaAttachmentDto(
    id = id,
    user_id = userId,
    commission_id = commissionId,
    update_id = updateId,
    media_type = mediaType,
    s3_key = remoteUrl ?: "",
    mime_type = mimeType,
    width = null,
    height = null,
    file_size_bytes = fileSizeBytes,
    created_at = createdAt,
    deleted_at = deletedAt
)

fun CommissionDto.toEntity() = CommissionEntity(
    id = id,
    title = title,
    description = description,
    customerName = customer_name,
    customerPhone = customer_phone,
    customerEmail = customer_email,
    itemName = item_name,
    size = size,
    colour = colour,
    quantity = quantity,
    priceCents = price_cents,
    dueDate = due_date,
    status = status,
    clayBody = clay_body,
    glazes = glazes,
    thumbnailMediaId = thumbnail_media_id,
    createdAt = created_at,
    updatedAt = updated_at,
    deletedAt = deleted_at,
    version = version,
    syncPending = false,
    deletedLocally = deleted_at != null
)

fun CommissionUpdateDto.toEntity() = CommissionUpdateEntity(
    id = id,
    commissionId = commission_id,
    fromStatus = from_status,
    toStatus = to_status,
    note = note,
    createdAt = created_at,
    deletedAt = deleted_at,
    syncPending = false
)

fun MediaAttachmentDto.toEntity() = MediaAttachmentEntity(
    id = id,
    commissionId = commission_id,
    updateId = update_id,
    mediaType = media_type,
    localPath = "", // Remote media has no on-device file
    remoteUrl = "/api/v1/media/$id",
    fileSizeBytes = file_size_bytes ?: 0,
    uploadStatus = "UPLOADED",
    createdAt = created_at,
    deletedAt = deleted_at,
    syncPending = false
)

/**
 * Merges server pull deltas into the local database without data loss:
 * - Commissions use a non-destructive upsert so child updates/media survive.
 * - Server tombstones (deleted_at != null) become local soft-deletes instead
 *   of being resurrected by insert.
 * - Existing local media rows keep their on-device localPath/fileSizeBytes/
 *   uploadStatus; PENDING rows are never touched by a pull.
 */
class SyncPullMerger(private val db: AppDatabase) {

    suspend fun merge(deltas: SyncPullResponseDto) {
        mergeCommissions(deltas.commissions)
        mergeUpdates(deltas.updates)
        mergeMedia(deltas.media)
    }

    suspend fun mergeCommissions(dtos: List<CommissionDto>) {
        dtos.forEach { dto ->
            if (dto.deleted_at != null) {
                db.commissionDao().markDeletedLocally(dto.id, dto.deleted_at)
            } else {
                val local = db.commissionDao().getCommissionDirect(dto.id)
                // If no local un-synced edits or server is newer
                if (local == null || (!local.syncPending && dto.updated_at >= local.updatedAt)) {
                    db.commissionDao().upsertCommission(dto.toEntity())
                }
            }
        }
    }

    suspend fun mergeUpdates(dtos: List<CommissionUpdateDto>) {
        dtos.forEach { dto ->
            if (dto.deleted_at != null) {
                db.commissionUpdateDao().markDeleted(dto.id, dto.deleted_at)
            } else {
                val local = db.commissionUpdateDao().getUpdateDirect(dto.id)
                if (local == null || !local.syncPending) {
                    db.commissionUpdateDao().insertOrUpdate(dto.toEntity())
                }
            }
        }
    }

    suspend fun mergeMedia(dtos: List<MediaAttachmentDto>) {
        dtos.forEach { dto ->
            if (dto.deleted_at != null) {
                db.mediaAttachmentDao().markDeleted(dto.id, dto.deleted_at)
            } else {
                val local = db.mediaAttachmentDao().getMediaDirect(dto.id)
                if (local == null) {
                    db.mediaAttachmentDao().insertOrUpdate(dto.toEntity())
                } else if (!local.syncPending && local.uploadStatus != "PENDING") {
                    val serverEntity = dto.toEntity()
                    db.mediaAttachmentDao().insertOrUpdate(
                        serverEntity.copy(
                            localPath = local.localPath,
                            remoteUrl = local.remoteUrl ?: serverEntity.remoteUrl,
                            fileSizeBytes = local.fileSizeBytes,
                            uploadStatus = local.uploadStatus,
                            syncPending = local.syncPending
                        )
                    )
                }
                // else: keep the local PENDING row untouched so the on-device
                // file path and upload state survive the pull.
            }
        }
    }
}
