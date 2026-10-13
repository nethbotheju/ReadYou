package me.ash.reader.domain.service

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.data.SyncProgress
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.*
import org.junit.Test

class FeedRefreshTest {
    @Test
    fun articlesAreSavedBeforeSlowFeedFinishes() = runBlocking {
        val fastSaved = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val result = async {
            refreshFeeds(listOf(feed("fast"), feed("slow")), Dispatchers.Default) {
                if (it.id == "slow") releaseSlow.await() else fastSaved.complete(Unit)
            }
        }
        withTimeout(2_000) { fastSaved.await() }
        assertFalse(result.isCompleted)
        releaseSlow.complete(Unit)
        assertEquals(2, result.await().completed)
    }

    @Test
    fun failedAndTimedOutFeedsDoNotDiscardSuccessfulResults() = runBlocking {
        val saved = AtomicBoolean(false)
        val progress = mutableListOf<SyncProgress>()
        val failures = mutableListOf<String>()
        val result = refreshFeeds(
            listOf(feed("fast"), feed("broken"), feed("slow")),
            Dispatchers.Unconfined,
            timeoutMillis = 50,
            onProgress = { progress.add(it) },
            onFailure = { feed, _ -> failures.add(feed.id) },
        ) {
            when (it.id) {
                "fast" -> saved.set(true)
                "broken" -> throw IOException("Feed unavailable")
                "slow" -> awaitCancellation()
            }
        }
        assertTrue(saved.get())
        assertEquals(3, result.completed)
        assertEquals(2, result.failed)
        assertFalse(result.isRunning)
        assertEquals(listOf(0, 1, 2, 3), progress.map { it.completed })
        assertEquals(setOf("broken", "slow"), failures.toSet())
    }

    @Test
    fun cancellationStopsWorkWithoutBeingReportedAsFeedFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = AtomicBoolean(false)
        var failures = 0
        val result = async {
            refreshFeeds(
                listOf(feed("slow")), Dispatchers.Unconfined,
                onFailure = { _, _ -> failures++ },
            ) {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.set(true) }
            }
        }
        started.await()
        result.cancelAndJoin()
        assertTrue(stopped.get())
        assertEquals(0, failures)
    }

    @Test
    fun emptySubscriptionsCompleteSuccessfully() = runBlocking {
        val result = refreshFeeds(emptyList(), Dispatchers.Unconfined) { fail("Nothing to refresh") }
        assertEquals(0, result.total)
        assertEquals(0, result.failed)
        assertFalse(result.isRunning)
    }

    @Test
    fun concurrencyIsBoundedAndQueueingDoesNotConsumeFeedDeadline() = runBlocking {
        var running = 0
        var maxRunning = 0
        val result = refreshFeeds(
            (1..4).map { feed(it.toString()) }, Dispatchers.Unconfined,
            timeoutMillis = 200, concurrency = 1,
        ) {
            running++
            maxRunning = maxOf(maxRunning, running)
            delay(60)
            running--
        }
        assertEquals(1, maxRunning)
        assertEquals(0, result.failed)
        assertEquals(4, result.completed)
    }

    private fun feed(id: String) = Feed(id = id, name = id, url = "https://example.com/$id", groupId = "group", accountId = 1)
}
