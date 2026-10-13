package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.ash.reader.domain.model.account.Account

@HiltWorker
class FeedIconWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val iconUpdater: FeedIconUpdater,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getInt("accountId", -1)
        if (accountId == -1) return Result.failure()
        iconUpdater.update(accountId)
        return Result.success()
    }

    companion object {

        fun enqueue(account: Account, workManager: WorkManager) {
            val accountId = account.id ?: return
            workManager.enqueueUniqueWork(
                "FEED_ICONS_$accountId",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FeedIconWorker>()
                    .setInputData(workDataOf("accountId" to accountId))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresCharging(account.syncOnlyWhenCharging.value)
                            .setRequiredNetworkType(
                                if (account.syncOnlyOnWiFi.value) NetworkType.UNMETERED
                                else NetworkType.CONNECTED
                            )
                            .build()
                    )
                    .build(),
            )
        }
    }
}
