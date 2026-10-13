package me.ash.reader.domain.service

import androidx.work.ListenableWorker
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.rss.RssHelper
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class LocalRssServiceTest {
    private val articles = mock<ArticleDao>()
    private val feeds = mock<FeedDao>()
    private val rss = mock<RssHelper>()
    private val accounts = mock<AccountService>()
    private val logger = mock<SyncLogger>()
    private val service = LocalRssService(
        mock(), articles, feeds, rss, mock(), mock(), Dispatchers.Unconfined,
        Dispatchers.Unconfined, mock(), accounts, logger,
    )
    private val feed = Feed(id = "feed", name = "Feed", url = "https://example.com/rss", groupId = "group", accountId = 1)

    @Test
    fun missingIconDoesNotDelayOrTriggerDiscoveryDuringArticleRefresh() = runBlocking {
        whenever(accounts.getAccountById(1)).thenReturn(Account(id = 1, name = "Local", type = AccountType.Local))
        whenever(feeds.queryAll(1)).thenReturn(listOf(feed))
        whenever(feeds.queryArchivedArticles(feed.id)).thenReturn(emptyList())
        whenever(rss.queryRssXml(eq(feed), isNull(), any(), eq(1))).thenReturn(emptyList())
        whenever(articles.insertListIfNotExist(emptyList(), feed)).thenReturn(emptyList())
        val result = service.sync(1, null, null)
        assertTrue(result is ListenableWorker.Result.Success)
        verify(articles).insertListIfNotExist(emptyList(), feed)
        verify(rss, never()).queryRssIconLink(any())
        verify(feeds, never()).updateIcon(any(), any(), any())
    }

    @Test
    fun feedFailuresAreReportedWithoutRetryingWholeRefresh(): Unit = runBlocking {
        whenever(accounts.getAccountById(1)).thenReturn(Account(id = 1, name = "Local", type = AccountType.Local))
        whenever(feeds.queryAll(1)).thenReturn(listOf(feed))
        whenever(feeds.queryArchivedArticles(feed.id)).thenReturn(emptyList())
        whenever(rss.queryRssXml(eq(feed), isNull(), any(), eq(1))).thenAnswer { throw IOException("Feed offline") }
        val result = service.sync(1, null, null)
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(1, (result as ListenableWorker.Result.Failure).outputData.getInt("sync_failed", 0))
        verify(logger).log(any())
        verify(rss, never()).queryRssIconLink(any())
    }
}
