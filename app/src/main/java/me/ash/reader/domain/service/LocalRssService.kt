package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import me.ash.reader.domain.data.SyncProgress
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.RssHelper
import timber.log.Timber

private const val TAG = "LocalRssService"

class LocalRssService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    private val groupDao: GroupDao,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val workManager: WorkManager,
    private val accountService: AccountService,
    private val syncLogger: SyncLogger,
) :
    AbstractRssRepository(
        articleDao,
        groupDao,
        feedDao,
        workManager,
        rssHelper,
        notificationHelper,
        ioDispatcher,
        defaultDispatcher,
        accountService,
    ) {

    override suspend fun sync(
        accountId: Int,
        feedId: String?,
        groupId: String?
    ): ListenableWorker.Result = syncWithProgress(accountId, feedId, groupId)

    suspend fun syncWithProgress(
        accountId: Int,
        feedId: String?,
        groupId: String?,
        onProgress: suspend (SyncProgress) -> Unit = {},
    ): ListenableWorker.Result {
        return try {
            val preTime = System.currentTimeMillis()
            val preDate = Date(preTime)
            val currentAccount = accountService.getAccountById(accountId)!!
            require(currentAccount.type.id == AccountType.Local.id) {
                "Account type is invalid"
            }
            val feedsToSync =
                when {
                    feedId != null -> listOfNotNull(feedDao.queryById(feedId))
                    groupId != null -> feedDao.queryByGroupId(accountId, groupId)
                    else -> feedDao.queryAll(accountId)
                }

            val progress = refreshFeeds(
                feeds = feedsToSync,
                dispatcher = ioDispatcher,
                onProgress = { onProgress(it.copy(accountId = accountId, startedAt = preTime)) },
                onFailure = { feed, error ->
                    Timber.tag(TAG).w(error, "Failed to refresh %s", feed.name)
                    syncLogger.log(error)
                },
            ) { currentFeed ->
                val archivedArticles =
                    feedDao.queryArchivedArticles(currentFeed.id).map { it.link }.toSet()
                val articles = rssHelper.queryRssXml(currentFeed, null, preDate, accountId)
                    .filterNot { archivedArticles.contains(it.link) }
                val saveStartedAt = System.currentTimeMillis()
                val newArticles = articleDao.insertListIfNotExist(articles, currentFeed)
                Timber.tag(TAG).d("Saved %s (%d new articles) in %d ms", currentFeed.name, newArticles.size, System.currentTimeMillis() - saveStartedAt)
                if (currentFeed.isNotification && newArticles.isNotEmpty()) {
                    notificationHelper.notify(FeedWithArticle(currentFeed, newArticles))
                }
            }.copy(accountId = accountId, startedAt = preTime)

            Timber.tag(TAG).i("Refreshed %d feeds (%d failed) in %d ms", progress.total, progress.failed, System.currentTimeMillis() - preTime)
            if (progress.failed < progress.total || progress.total == 0) {
                accountService.update(currentAccount.copy(updateAt = Date()))
                ListenableWorker.Result.success(progress.toData())
            } else {
                ListenableWorker.Result.failure(progress.toData())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLogger.log(e)
            ListenableWorker.Result.failure()
        }
    }

}
