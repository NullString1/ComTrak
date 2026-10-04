package one.nullstring.ComTrak.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import one.nullstring.ComTrak.data.local.AppDatabase
import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import one.nullstring.ComTrak.data.local.entities.CommissionUpdateEntity
import one.nullstring.ComTrak.data.local.entities.MediaAttachmentEntity
import one.nullstring.ComTrak.data.remote.ApiClient
import one.nullstring.ComTrak.data.remote.CommissionDto
import one.nullstring.ComTrak.data.remote.CommissionUpdateDto
import one.nullstring.ComTrak.data.remote.SessionManager
import one.nullstring.ComTrak.data.remote.SyncPushRequestDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val db = AppDatabase.getInstance(appContext)
    private val sessionManager = SessionManager(appContext)
    private val apiService = ApiClient(appContext).apiService

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!sessionManager.isLoggedIn) {
            Log.d(TAG, "Skipping sync: user is not logged in.")
            return@withContext Result.success()
        }

        try {
            Log.i(TAG, "Starting ComTrak sync cycle...")

            // 1. Upload Pending Compressed WebP Images to Garage via Backend
            uploadPendingImages()

            // 2. Push Local Mutations (Commissions & Updates)
            pushLocalMutations()

            // 3. Pull Server Deltas Since Last Sync
            pullServerDeltas()

            Log.i(TAG, "ComTrak sync cycle completed successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync cycle encountered an error: ${e.message}", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun uploadPendingImages() {
        val pendingMedia = db.mediaAttachmentDao().getPendingUploads()
        for (media in pendingMedia) {
            val file = File(media.localPath)
            if (!file.exists()) {
                Log.w(TAG, "Local media file missing at ${media.localPath}, skipping upload.")
                continue
            }

            try {
                val requestFile = file.asRequestBody("image/webp".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val commId = media.commissionId.toRequestBody("text/plain".toMediaTypeOrNull())
                val mediaId = media.id.toRequestBody("text/plain".toMediaTypeOrNull())
                val mediaType = media.mediaType.toRequestBody("text/plain".toMediaTypeOrNull())
                val updateId = media.updateId?.toRequestBody("text/plain".toMediaTypeOrNull())

                val resp = apiService.uploadMedia(body, commId, mediaId, mediaType, updateId)
                if (resp.isSuccessful && resp.body() != null) {
                    val uploadResult = resp.body()!!
                    db.mediaAttachmentDao().updateUploadStatus(
                        id = media.id,
                        status = "UPLOADED",
                        remoteUrl = uploadResult.url
                    )
                    Log.d(TAG, "Uploaded media ${media.id} to S3 -> ${uploadResult.url}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed uploading image ${media.id}: ${e.message}")
            }
        }
    }

    private suspend fun pushLocalMutations() {
        val pendingCommissions = db.commissionDao().getPendingSyncCommissions()
        val pendingUpdates = db.commissionUpdateDao().getPendingSyncUpdates()

        if (pendingCommissions.isEmpty() && pendingUpdates.isEmpty()) {
            return
        }

        val pushDto = SyncPushRequestDto(
            commissions = pendingCommissions.map { it.toDto(sessionManager.userId ?: "") },
            updates = pendingUpdates.map { it.toDto(sessionManager.userId ?: "") }
        )

        val resp = apiService.syncPush(pushDto)
        if (resp.isSuccessful && resp.body() != null) {
            val result = resp.body()!!
            if (result.accepted_commission_ids.isNotEmpty()) {
                db.commissionDao().markSynced(result.accepted_commission_ids)
            }
            if (result.accepted_update_ids.isNotEmpty()) {
                db.commissionUpdateDao().markSynced(result.accepted_update_ids)
            }
        }
    }

    private suspend fun pullServerDeltas() {
        val lastSynced = sessionManager.lastSyncedAt
        val resp = apiService.syncPull(lastSynced)

        if (resp.isSuccessful && resp.body() != null) {
            val deltas = resp.body()!!

            // Merge commissions
            deltas.commissions.forEach { dto ->
                if (dto.deleted_at != null) {
                    db.commissionDao().markDeletedLocally(dto.id, dto.deleted_at)
                } else {
                    val local = db.commissionDao().getCommissionDirect(dto.id)
                    // If no local un-synced edits or server is newer
                    if (local == null || (!local.syncPending && dto.updated_at >= local.updatedAt)) {
                        db.commissionDao().insertOrUpdate(dto.toEntity())
                    }
                }
            }

            // Merge updates
            val updateEntities = deltas.updates.map { it.toEntity() }
            if (updateEntities.isNotEmpty()) {
                db.commissionUpdateDao().insertOrUpdateAll(updateEntities)
            }

            // Merge media attachments
            val mediaEntities = deltas.media.map { it.toEntity() }
            if (mediaEntities.isNotEmpty()) {
                db.mediaAttachmentDao().insertOrUpdateAll(mediaEntities)
            }

            // Update sync cursor
            sessionManager.lastSyncedAt = deltas.server_time
        }
    }

    private fun CommissionEntity.toDto(userId: String) = CommissionDto(
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

    private fun CommissionUpdateEntity.toDto(userId: String) = CommissionUpdateDto(
        id = id,
        commission_id = commissionId,
        user_id = userId,
        from_status = fromStatus,
        to_status = toStatus,
        note = note,
        created_at = createdAt,
        deleted_at = deletedAt
    )

    private fun CommissionDto.toEntity() = CommissionEntity(
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

    private fun CommissionUpdateDto.toEntity() = CommissionUpdateEntity(
        id = id,
        commissionId = commission_id,
        fromStatus = from_status,
        toStatus = to_status,
        note = note,
        createdAt = created_at,
        deletedAt = deleted_at,
        syncPending = false
    )

    private fun one.nullstring.ComTrak.data.remote.MediaAttachmentDto.toEntity() = MediaAttachmentEntity(
        id = id,
        commissionId = commission_id,
        updateId = update_id,
        mediaType = media_type,
        localPath = "", // Remote media
        remoteUrl = "/api/v1/media/$id",
        fileSizeBytes = file_size_bytes ?: 0,
        uploadStatus = "UPLOADED",
        createdAt = created_at
    )

    companion object {
        private const val TAG = "ComTrakSyncWorker"
    }
}
