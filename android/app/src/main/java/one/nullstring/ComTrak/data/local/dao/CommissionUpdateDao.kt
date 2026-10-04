package one.nullstring.ComTrak.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import one.nullstring.ComTrak.data.local.entities.CommissionUpdateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommissionUpdateDao {

    @Query("SELECT * FROM commission_updates WHERE commissionId = :commissionId AND deletedAt IS NULL ORDER BY createdAt ASC")
    fun getUpdatesForCommission(commissionId: String): Flow<List<CommissionUpdateEntity>>

    @Query("SELECT * FROM commission_updates WHERE syncPending = 1")
    suspend fun getPendingSyncUpdates(): List<CommissionUpdateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(update: CommissionUpdateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(updates: List<CommissionUpdateEntity>)

    @Query("UPDATE commission_updates SET syncPending = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE commission_updates SET deletedAt = :now, syncPending = 1 WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE commission_updates SET deletedAt = :now, syncPending = 1 WHERE commissionId = :commissionId AND deletedAt IS NULL")
    suspend fun markDeletedForCommission(commissionId: String, now: Long = System.currentTimeMillis())
}
