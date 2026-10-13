package me.ash.reader.infrastructure.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedIconMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AndroidDatabase::class.java)

    @Test
    fun migrationPreservesSavedIconsAndArticles() {
        helper.createDatabase("icon-migration", 7).use { database ->
            database.execSQL("INSERT INTO account (id, name, type) VALUES (1, 'Local', 1)")
            database.execSQL("INSERT INTO `group` (id, name, accountId) VALUES ('group', 'Group', 1)")
            database.execSQL("""
                INSERT INTO feed (id, name, icon, url, groupId, accountId, isNotification, isFullContent)
                VALUES ('feed', 'Feed', 'https://example.com/icon.png', 'https://example.com/rss', 'group', 1, 1, 0)
            """.trimIndent())
            database.execSQL("""
                INSERT INTO article (id, date, title, rawDescription, shortDescription, link, feedId, accountId, isUnread, isStarred, isReadLater)
                VALUES ('article', 1, 'Saved article', '', '', 'https://example.com/article', 'feed', 1, 1, 0, 0)
            """.trimIndent())
        }
        helper.runMigrationsAndValidate("icon-migration", 8, true).use { database ->
            database.query("SELECT icon, iconRetryAfter, isNotification FROM feed WHERE id = 'feed'").use {
                assertTrue(it.moveToFirst())
                assertEquals("https://example.com/icon.png", it.getString(0))
                assertEquals(0L, it.getLong(1))
                assertEquals(1, it.getInt(2))
            }
            database.query("SELECT title FROM article WHERE id = 'article'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Saved article", it.getString(0))
            }
        }
    }
}
