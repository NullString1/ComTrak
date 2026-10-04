package one.nullstring.ComTrak

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import one.nullstring.ComTrak.data.sync.SyncWorker
import java.util.concurrent.TimeUnit

class ComTrakApp : Application() {

    override fun onCreate() {
        super.onCreate()
        setupPeriodicSync()
    }

    private fun setupPeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicSyncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ComTrak_Periodic_Sync",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicSyncRequest
        )
    }
}
