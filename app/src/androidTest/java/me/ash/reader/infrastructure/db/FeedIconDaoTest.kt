package me.ash.reader.infrastructure.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedIconDaoTest {
    private lateinit var database: AndroidDatabase
    private val feed = Feed(id = "feed", name = "Feed", url = "https://example.com/rss", groupId = "group", accountId = 1)

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AndroidDatabase::class.java,
        ).build()
        database.groupDao().insert(Group("group", "Group", 1))
        database.feedDao().insert(feed)
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun iconUpdatesDoNotOverwriteSettingsChangedDuringLookup() = runBlocking {
        val dao = database.feedDao()
        assertEquals(1, dao.claimIconLookup(feed.id, feed.url, 100, 200))
        dao.update(feed.copy(isNotification = true, isFullContent = true, iconRetryAfter = 200))
        dao.saveMissingIcon(feed.id, feed.url, "https://example.com/icon.png")
        val saved = dao.queryById(feed.id)!!
        assertTrue(saved.isNotification)
        assertTrue(saved.isFullContent)
        assertEquals("https://example.com/icon.png", saved.icon)
        assertEquals(0L, saved.iconRetryAfter)
        assertTrue(dao.queryNoIcon(1).isEmpty())
    }

    @Test
    fun expiredOrStaleLookupCannotOverwriteNewIconOrUrl() = runBlocking {
        val dao = database.feedDao()
        dao.update(feed.copy(icon = "https://example.com/manual.png"))
        dao.saveMissingIcon(feed.id, feed.url, "https://example.com/old.png")
        assertEquals("https://example.com/manual.png", dao.queryById(feed.id)!!.icon)
        dao.update(feed.copy(url = "https://other.example/rss"))
        dao.saveMissingIcon(feed.id, feed.url, "https://example.com/old.png")
        assertNull(dao.queryById(feed.id)!!.icon)
    }

    @Test
    fun remoteFeedUpdatesPreserveCooldownAndClaimsAreDeduplicated() = runBlocking {
        val dao = database.feedDao()
        assertEquals(1, dao.claimIconLookup(feed.id, feed.url, 100, 200))
        assertEquals(0, dao.claimIconLookup(feed.id, feed.url, 150, 250))
        dao.insertOrUpdate(listOf(feed.copy(name = "Renamed")))
        assertEquals(200L, dao.queryById(feed.id)!!.iconRetryAfter)
        assertEquals(1, dao.claimIconLookup(feed.id, feed.url, 201, 300))
    }
}
