package me.ash.reader.domain.service

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import me.ash.reader.domain.data.SyncProgress
import me.ash.reader.domain.model.feed.Feed

internal suspend fun refreshFeeds(
    feeds: List<Feed>,
    dispatcher: CoroutineDispatcher,
    timeoutMillis: Long = 30_000,
    concurrency: Int = 16,
    onProgress: suspend (SyncProgress) -> Unit = {},
    onFailure: suspend (Feed, Exception) -> Unit = { _, _ -> },
    refresh: suspend (Feed) -> Unit,
): SyncProgress = supervisorScope {
    val semaphore = Semaphore(concurrency)
    val mutex = Mutex()
    var progress = SyncProgress(total = feeds.size, isRunning = true)
    onProgress(progress)
    feeds.map { feed ->
        async(dispatcher) {
            semaphore.withPermit {
                val succeeded = try {
                    withTimeoutOrNull(timeoutMillis) { refresh(feed); true }
                        ?: throw IOException("Feed refresh timed out: ${feed.name}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(feed, e)
                    false
                }
                mutex.withLock {
                    progress = progress.copy(
                        completed = progress.completed + 1,
                        failed = progress.failed + if (succeeded) 0 else 1,
                    )
                    onProgress(progress)
                }
            }
        }
    }.awaitAll()
    progress.copy(isRunning = false)
}
