package one.nullstring.ComTrak.data.repository

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import one.nullstring.ComTrak.data.local.AppDatabase
import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import one.nullstring.ComTrak.data.local.entities.CommissionUpdateEntity
import one.nullstring.ComTrak.data.local.entities.MediaAttachmentEntity
import one.nullstring.ComTrak.data.sync.SyncWorker
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class CommissionRepository(
    private val context: Context,
    db: AppDatabase? = null
) {

    private val database: AppDatabase = db ?: AppDatabase.getInstance(context)
    private val commissionDao = database.commissionDao()
    private val updateDao = database.commissionUpdateDao()
    private val mediaDao = database.mediaAttachmentDao()

    // 1. Reactive Queries for UI (Single Source of Truth)
    fun getAllCommissions(): Flow<List<CommissionEntity>> = commissionDao.getAllCommissionsSortedBySla()

    fun getCommissionById(id: String): Flow<CommissionEntity?> = commissionDao.getCommissionById(id)

    fun getUpdatesForCommission(id: String): Flow<List<CommissionUpdateEntity>> =
        updateDao.getUpdatesForCommission(id)

    fun getMediaForCommission(id: String): Flow<List<MediaAttachmentEntity>> =
        mediaDao.getMediaForCommission(id)

    // 2. Offline-First Writes
    suspend fun createCommission(
        title: String,
        description: String?,
        customerName: String,
        customerPhone: String?,
        customerEmail: String?,
        itemName: String,
        size: String?,
        colour: String?,
        quantity: Long,
        priceCents: Long?,
        dueDate: Long?,
        status: String,
        clayBody: String?,
        glazes: String?,
        thumbnailMediaId: String? = null
    ): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        val entity = CommissionEntity(
            id = id,
            title = title,
            description = description,
            customerName = customerName,
            customerPhone = customerPhone,
            customerEmail = customerEmail,
            itemName = itemName,
            size = size,
            colour = colour,
            quantity = quantity,
            priceCents = priceCents,
            dueDate = dueDate,
            status = status,
            clayBody = clayBody,
            glazes = glazes,
            thumbnailMediaId = thumbnailMediaId,
            createdAt = now,
            updatedAt = now,
            syncPending = true,
            deletedLocally = false
        )

        commissionDao.upsertCommission(entity)
        scheduleSync()
        return id
    }

    suspend fun updateCommission(commission: CommissionEntity) {
        val updated = commission.copy(
            updatedAt = System.currentTimeMillis(),
            version = commission.version + 1,
            syncPending = true
        )
        commissionDao.upsertCommission(updated)
        scheduleSync()
    }

    suspend fun getCommissionDirect(id: String): CommissionEntity? =
        commissionDao.getCommissionDirect(id)

    suspend fun deleteCommission(id: String) {
        val now = System.currentTimeMillis()
        commissionDao.markDeletedLocally(id, now)
        updateDao.markDeletedForCommission(id, now)
        mediaDao.markDeletedForCommission(id, now)
        scheduleSync()
    }

    suspend fun addUpdate(
        commissionId: String,
        toStatus: String?,
        note: String?,
        attachedMediaIds: List<String> = emptyList()
    ): String {
        val now = System.currentTimeMillis()
        val updateId = UUID.randomUUID().toString()

        val existingComm = commissionDao.getCommissionDirect(commissionId)
        val fromStatus = existingComm?.status

        val updateEntity = CommissionUpdateEntity(
            id = updateId,
            commissionId = commissionId,
            fromStatus = fromStatus,
            toStatus = toStatus,
            note = note,
            createdAt = now,
            syncPending = true
        )

        updateDao.insertOrUpdate(updateEntity)

        // Update the parent commission's status if a new status was specified
        existingComm?.let { comm ->
            val newStatus = toStatus ?: comm.status
            val updatedComm = comm.copy(
                status = newStatus,
                updatedAt = now,
                version = comm.version + 1,
                syncPending = true
            )
            commissionDao.upsertCommission(updatedComm)
        }

        // Link media attachments to this update
        attachedMediaIds.forEach { mediaId ->
            mediaDao.setUpdateId(mediaId, updateId)
        }

        scheduleSync()
        return updateId
    }

    suspend fun recordMediaAttachment(
        id: String,
        commissionId: String,
        localPath: String,
        mediaType: String, // 'reference', 'progress', 'finished'
        fileSizeBytes: Long,
        updateId: String? = null
    ) {
        val entity = MediaAttachmentEntity(
            id = id,
            commissionId = commissionId,
            updateId = updateId,
            mediaType = mediaType,
            localPath = localPath,
            remoteUrl = null,
            fileSizeBytes = fileSizeBytes,
            uploadStatus = "PENDING",
            createdAt = System.currentTimeMillis(),
            syncPending = true
        )
        mediaDao.insertOrUpdate(entity)

        // Thumbnail rule: latest progress photo wins; the new media is thumbnail
        // only if no progress photos exist (reference-only case).
        commissionDao.getCommissionDirect(commissionId)?.let { comm ->
            val latestProgressId = mediaDao.getLatestProgressId(commissionId)
            val newThumbnailId = latestProgressId ?: id
            if (comm.thumbnailMediaId != newThumbnailId) {
                commissionDao.upsertCommission(comm.copy(thumbnailMediaId = newThumbnailId, syncPending = true))
            }
        }

        scheduleSync()
    }

    fun scheduleSync() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "ComTrak_Opportunistic_Sync",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
        } catch (e: IllegalStateException) {
            // WorkManager isn't initialized (e.g. hermetic unit tests running
            // against an in-memory db). Mutations stay durable via the
            // syncPending flags and sync on the next scheduled trigger.
            Log.w(TAG, "WorkManager unavailable, sync remains queued: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CommissionRepository"
    }
}
