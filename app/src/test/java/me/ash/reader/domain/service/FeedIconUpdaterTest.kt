package me.ash.reader.domain.service

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.rss.RssHelper
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class FeedIconUpdaterTest {
    private val dao = mock<FeedDao>()
    private val rss = mock<RssHelper>()
    private val updater = FeedIconUpdater(dao, rss)
    private val now = 1_000L
    private val retryAt = now + TimeUnit.DAYS.toMillis(1)
    private val feed = Feed(id = "feed", name = "Feed", url = "https://example.com/rss", groupId = "group", accountId = 1)

    @Test
    fun savedIconsAreNotLookedUpAgain() = runBlocking {
        whenever(dao.queryNoIcon(1)).thenReturn(emptyList())
        updater.update(1) { now }
        verifyNoInteractions(rss)
    }

    @Test
    fun failedLookupIsNotRepeatedUntilCooldownExpires() = runBlocking {
        var retryAfter = 0L
        whenever(dao.queryNoIcon(1)).thenAnswer { listOf(feed.copy(iconRetryAfter = retryAfter)) }
        whenever(dao.claimIconLookup(feed.id, feed.url, now, retryAt)).thenAnswer {
            retryAfter = retryAt
            1
        }
        whenever(rss.queryRssIconLink(feed.url)).thenReturn(null)
        updater.update(1) { now }
        updater.update(1) { now + 1 }
        verify(rss, times(1)).queryRssIconLink(feed.url)
        verify(dao, never()).saveMissingIcon(any(), any(), any())
    }

    @Test
    fun eligibleMissingIconIsSavedWithoutReplacingFeedSettings() = runBlocking {
        whenever(dao.queryNoIcon(1)).thenReturn(listOf(feed))
        whenever(dao.claimIconLookup(feed.id, feed.url, now, retryAt)).thenReturn(1)
        whenever(rss.queryRssIconLink(feed.url)).thenReturn("https://example.com/icon.png")
        updater.update(1) { now }
        verify(dao).saveMissingIcon(feed.id, feed.url, "https://example.com/icon.png")
        verify(dao, never()).update(any())
    }

    @Test
    fun feedUpdatedOrDeletedDuringLookupIsSkipped() = runBlocking {
        whenever(dao.queryNoIcon(1)).thenReturn(listOf(feed))
        whenever(dao.claimIconLookup(feed.id, feed.url, now, retryAt)).thenReturn(0)
        updater.update(1) { now }
        verifyNoInteractions(rss)
    }

    @Test
    fun cancellationIsPropagated() = runBlocking {
        whenever(dao.queryNoIcon(1)).thenReturn(listOf(feed))
        whenever(dao.claimIconLookup(feed.id, feed.url, now, retryAt)).thenReturn(1)
        whenever(rss.queryRssIconLink(feed.url)).thenThrow(CancellationException("Stopped"))
        try {
            updater.update(1) { now }
            fail("Cancellation should propagate")
        } catch (_: CancellationException) {
            verify(dao, never()).saveMissingIcon(any(), any(), any())
        }
    }
}
