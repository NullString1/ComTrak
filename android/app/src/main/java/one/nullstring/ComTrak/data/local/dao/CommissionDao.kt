package one.nullstring.ComTrak.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommissionDao {

    @Query("""
        SELECT * FROM commissions 
        WHERE deletedLocally = 0 AND deletedAt IS NULL
        ORDER BY 
            CASE WHEN dueDate IS NULL THEN 1 ELSE 0 END,
            dueDate ASC,
            createdAt DESC
    """)
    fun getAllCommissionsSortedBySla(): Flow<List<CommissionEntity>>

    @Query("SELECT * FROM commissions WHERE id = :id AND deletedLocally = 0 AND deletedAt IS NULL")
    fun getCommissionById(id: String): Flow<CommissionEntity?>

    @Query("SELECT * FROM commissions WHERE id = :id")
    suspend fun getCommissionDirect(id: String): CommissionEntity?

    @Query("SELECT * FROM commissions WHERE syncPending = 1 OR deletedLocally = 1")
    suspend fun getPendingSyncCommissions(): List<CommissionEntity>

    // Non-destructive upsert: @Upsert performs INSERT-or-UPDATE in place,
    // unlike REPLACE (DELETE+INSERT) which would cascade-delete child
    // commission_updates and media_attachments rows.
    @Upsert
    suspend fun upsertCommission(commission: CommissionEntity)

    @Upsert
    suspend fun upsertAll(commissions: List<CommissionEntity>)

    @Query("UPDATE commissions SET deletedLocally = 1, deletedAt = :now, updatedAt = :now, syncPending = 1 WHERE id = :id")
    suspend fun markDeletedLocally(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE commissions SET syncPending = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
