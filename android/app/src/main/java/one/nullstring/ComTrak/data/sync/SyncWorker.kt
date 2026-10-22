package one.nullstring.ComTrak.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import one.nullstring.ComTrak.data.local.AppDatabase
import one.nullstring.ComTrak.data.remote.ApiClient
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

            // 1. Push Local Mutations FIRST so parent commissions exist on the
            // server before we upload image binaries for them (upload 404s on
            // unknown commissions) and before pulling deltas.
            pushLocalMutations()

            // 2. Upload Pending Compressed WebP Images to Garage via Backend
            uploadPendingImages()

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
        val pendingMedia = db.mediaAttachmentDao().getPendingSyncMedia()

        if (pendingCommissions.isEmpty() && pendingUpdates.isEmpty() && pendingMedia.isEmpty()) {
            return
        }

        val pushDto = SyncPushRequestDto(
            commissions = pendingCommissions.map { it.toDto(sessionManager.userId ?: "") },
            updates = pendingUpdates.map { it.toDto(sessionManager.userId ?: "") },
            media = pendingMedia.map { it.toDto(sessionManager.userId ?: "") }
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
            // accepted_media_ids may be absent (null via Gson) when talking to an
            // older backend that doesn't return it yet — treat as empty.
            val acceptedMediaIds: List<String> = result.accepted_media_ids ?: emptyList()
            if (acceptedMediaIds.isNotEmpty()) {
                db.mediaAttachmentDao().markSynced(acceptedMediaIds)
            }
        }
    }

    private suspend fun pullServerDeltas() {
        val lastSynced = sessionManager.lastSyncedAt
        val resp = apiService.syncPull(lastSynced)

        if (resp.isSuccessful && resp.body() != null) {
            val deltas = resp.body()!!

            SyncPullMerger(db).merge(deltas)

            // Update sync cursor
            sessionManager.lastSyncedAt = deltas.server_time
        }
    }

    companion object {
        private const val TAG = "ComTrakSyncWorker"
    }
}
