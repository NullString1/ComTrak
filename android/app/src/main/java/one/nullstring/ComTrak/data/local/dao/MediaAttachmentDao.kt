package one.nullstring.ComTrak.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import one.nullstring.ComTrak.data.local.entities.MediaAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaAttachmentDao {

    @Query("SELECT * FROM media_attachments WHERE commissionId = :commissionId AND deletedAt IS NULL ORDER BY createdAt ASC")
    fun getMediaForCommission(commissionId: String): Flow<List<MediaAttachmentEntity>>

    @Query("SELECT * FROM media_attachments WHERE uploadStatus = 'PENDING' AND deletedAt IS NULL")
    suspend fun getPendingUploads(): List<MediaAttachmentEntity>

    @Query("SELECT * FROM media_attachments WHERE syncPending = 1")
    suspend fun getPendingSyncMedia(): List<MediaAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(media: MediaAttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(mediaList: List<MediaAttachmentEntity>)

    @Query("UPDATE media_attachments SET uploadStatus = :status, remoteUrl = :remoteUrl WHERE id = :id")
    suspend fun updateUploadStatus(id: String, status: String, remoteUrl: String?)

    @Query("UPDATE media_attachments SET deletedAt = :now, syncPending = 1 WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE media_attachments SET deletedAt = :now, syncPending = 1 WHERE commissionId = :commissionId AND deletedAt IS NULL")
    suspend fun markDeletedForCommission(commissionId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE media_attachments SET syncPending = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
