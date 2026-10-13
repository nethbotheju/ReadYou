package me.ash.reader.infrastructure.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AwaitBodyTest {
    @Test
    fun cancellationCancelsCallWhileResponseBodyIsStillDownloading() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("article").setBodyDelay(5, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/rss")).build())
            val result = async(Dispatchers.IO) { call.awaitBody { it.body.string() } }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            delay(50)
            withTimeout(2_000) { result.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test
    fun totalCallDeadlineIncludesBodyDownload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("article").setBodyDelay(5, TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().callTimeout(200, TimeUnit.MILLISECONDS).build()
            val call = client.newCall(Request.Builder().url(server.url("/rss")).build())
            withTimeout(2_000) {
                try {
                    call.awaitBody { it.body.string() }
                    fail("Slow response should time out")
                } catch (_: IOException) {
                    assertTrue(call.isCanceled())
                }
            }
        }
    }

    @Test
    fun successfulResponseIsReadAndClosed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("article"))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/rss")).build())
            assertEquals("article", call.awaitBody { it.body.string() })
        }
    }
}
