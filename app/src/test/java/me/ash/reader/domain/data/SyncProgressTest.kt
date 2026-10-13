package me.ash.reader.domain.data

import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncProgressTest {
    @Test
    fun latestCompletedRefreshKeepsFailureSummaryAfterProgressIsCleared() {
        val first = SyncProgress(total = 15, completed = 15, failed = 3, accountId = 1, startedAt = 100)
        val latest = first.copy(failed = 1, startedAt = 200)
        val result = SyncProgress.fromWorkInfos(
            listOf(work(WorkInfo.State.SUCCEEDED, first), work(WorkInfo.State.SUCCEEDED, latest)), 1,
        )
        assertEquals(latest, result)
    }

    @Test
    fun activeRefreshOverridesOldFailureSummary() {
        val old = SyncProgress(total = 15, completed = 15, failed = 3, accountId = 1, startedAt = 100)
        val current = SyncProgress(total = 15, completed = 2, isRunning = true, accountId = 1, startedAt = 200)
        val result = SyncProgress.fromWorkInfos(
            listOf(work(WorkInfo.State.RUNNING, current), work(WorkInfo.State.SUCCEEDED, old)), 1,
        )
        assertEquals(current, result)
    }

    @Test
    fun anotherAccountCannotLeakProgressOrFailuresIntoCurrentAccount() {
        val other = SyncProgress(total = 15, completed = 15, failed = 3, accountId = 2, startedAt = 100)
        val result = SyncProgress.fromWorkInfos(listOf(work(WorkInfo.State.FAILED, other)), 1)
        assertEquals(SyncProgress(accountId = 1), result)
        assertEquals(SyncProgress(), SyncProgress.fromWorkInfos(emptyList(), null))
    }

    private fun work(state: WorkInfo.State, progress: SyncProgress): WorkInfo {
        val work = mock<WorkInfo>()
        whenever(work.state).thenReturn(state)
        whenever(work.progress).thenReturn(if (state == WorkInfo.State.RUNNING) progress.toData() else Data.EMPTY)
        whenever(work.outputData).thenReturn(if (state == WorkInfo.State.RUNNING) Data.EMPTY else progress.toData())
        return work
    }
}
