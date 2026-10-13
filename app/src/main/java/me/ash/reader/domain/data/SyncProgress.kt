package me.ash.reader.domain.data

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf

data class SyncProgress(
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val isRunning: Boolean = false,
    val accountId: Int = -1,
    val startedAt: Long = 0,
) {
    fun toData(): Data = workDataOf(
        "sync_total" to total,
        "sync_completed" to completed,
        "sync_failed" to failed,
        "sync_account" to accountId,
        "sync_started_at" to startedAt,
    )

    companion object {
        fun fromWorkInfos(workInfos: List<WorkInfo>, accountId: Int?): SyncProgress {
            if (accountId == null) return SyncProgress()
            val candidates = workInfos.mapNotNull { workInfo ->
                val running = workInfo.state == WorkInfo.State.RUNNING
                val data = if (running) workInfo.progress else workInfo.outputData
                if (data.getInt("sync_account", -1) != accountId) return@mapNotNull null
                SyncProgress(
                    total = data.getInt("sync_total", 0),
                    completed = data.getInt("sync_completed", 0),
                    failed = data.getInt("sync_failed", 0),
                    isRunning = running,
                    accountId = accountId,
                    startedAt = data.getLong("sync_started_at", 0),
                )
            }
            return candidates.maxWithOrNull(compareBy({ it.isRunning }, { it.startedAt }))
                ?: SyncProgress(accountId = accountId)
        }
    }
}
