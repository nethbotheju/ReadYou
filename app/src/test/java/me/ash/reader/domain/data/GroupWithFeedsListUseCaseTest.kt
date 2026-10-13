package me.ash.reader.domain.data

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.preference.Settings
import me.ash.reader.infrastructure.preference.SettingsProvider
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class GroupWithFeedsListUseCaseTest {
    @Test
    fun readyAccountCanPopulateUnreadFeedsDuringConstruction(): Unit = runBlocking {
        checkStartup(Filter.Unread)
    }

    @Test
    fun readyAccountCanPopulateAllFeedsDuringConstruction(): Unit = runBlocking {
        checkStartup(Filter.All)
    }

    @Test
    fun readyAccountCanPopulateStarredFeedsDuringConstruction(): Unit = runBlocking {
        checkStartup(Filter.Starred)
    }

    @Test
    fun accountArrivingAfterConstructionStillPopulatesFeeds(): Unit = runBlocking {
        checkStartup(Filter.Unread, initiallyReady = false)
    }

    private suspend fun checkStartup(filter: Filter, initiallyReady: Boolean = true) {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> errors.add(error) }
        )
        try {
            val account = Account(id = 1, name = "Local", type = AccountType.Local)
            val accountFlow = MutableStateFlow<Account?>(if (initiallyReady) account else null)
            val accounts = mock<AccountService>()
            whenever(accounts.currentAccountFlow).thenReturn(accountFlow)
            whenever(accounts.getCurrentAccountId()).thenReturn(1)
            val settings = mock<SettingsProvider>()
            whenever(settings.settings).thenReturn(Settings())
            val filters = mock<FilterStateUseCase>()
            whenever(filters.filterStateFlow).thenReturn(MutableStateFlow(FilterState(filter = filter)))
            val diffs = mock<DiffMapHolder>()
            whenever(diffs.diffMapSnapshotFlow).thenReturn(MutableStateFlow(emptyMap()))
            val feed = Feed(id = "feed", name = "Feed", url = "https://example.com/rss", groupId = "group", accountId = 1)
            val group = GroupWithFeed(Group("group", "Group", 1), mutableListOf(feed))
            val service = mock<LocalRssService>()
            whenever(service.pullFeeds()).thenReturn(MutableStateFlow(mutableListOf(group)))
            whenever(service.pullImportant(any(), any())).thenReturn(MutableStateFlow(mapOf(feed.id to 3)))
            val rss = mock<RssService>()
            whenever(rss.get()).thenReturn(service)
            whenever(rss.get(account.type.id)).thenReturn(service)

            val useCase = GroupWithFeedsListUseCase(scope, Dispatchers.Unconfined, settings, rss, filters, diffs, accounts)
            assertTrue("Collectors accessed uninitialized state: $errors", errors.isEmpty())
            if (!initiallyReady) accountFlow.value = account
            val result = withTimeout(2_000) { useCase.groupWithFeedListFlow.first { it.isNotEmpty() } }
            assertEquals(feed.id, result.single().feeds.single().id)
            assertEquals(3, result.single().feeds.single().important)
            assertTrue("Feed collection failed: $errors", errors.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
