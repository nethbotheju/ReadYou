package me.ash.reader.domain.service

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.rss.RssHelper
import timber.log.Timber

class FeedIconUpdater @Inject constructor(
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
) {
    suspend fun update(accountId: Int, now: () -> Long = System::currentTimeMillis) = supervisorScope {
        val feeds = feedDao.queryNoIcon(accountId).filter { it.iconRetryAfter <= now() }
        val semaphore = Semaphore(2)
        feeds.map { feed ->
            async {
                semaphore.withPermit {
                    val attemptAt = now()
                    if (feedDao.claimIconLookup(feed.id, feed.url, attemptAt, attemptAt + RETRY_DELAY) == 0) {
                        return@withPermit
                    }
                    try {
                        val icon = rssHelper.queryRssIconLink(feed.url)
                        if (!icon.isNullOrBlank()) feedDao.saveMissingIcon(feed.id, feed.url, icon)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag("FeedIcon").w(e, "Icon lookup failed for %s", feed.name)
                    }
                }
            }
        }.awaitAll()
        Unit
    }

    private companion object {
        val RETRY_DELAY = TimeUnit.DAYS.toMillis(1)
    }
}
