package one.nullstring.ComTrak.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import one.nullstring.ComTrak.data.local.dao.CommissionDao
import one.nullstring.ComTrak.data.local.dao.CommissionUpdateDao
import one.nullstring.ComTrak.data.local.dao.MediaAttachmentDao
import one.nullstring.ComTrak.data.local.entities.CommissionEntity
import one.nullstring.ComTrak.data.local.entities.CommissionUpdateEntity
import one.nullstring.ComTrak.data.local.entities.MediaAttachmentEntity

@Database(
    entities = [
        CommissionEntity::class,
        CommissionUpdateEntity::class,
        MediaAttachmentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun commissionDao(): CommissionDao
    abstract fun commissionUpdateDao(): CommissionUpdateDao
    abstract fun mediaAttachmentDao(): MediaAttachmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "comtrak_local.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
