package me.ash.reader.infrastructure.rss

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class BestIconFinderTest {
    @Test
    fun relativeIconLinksResolveAgainstHomepageAndSmallHtmlIsAccepted() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> MockResponse().setBody("<html><head><link rel='icon' href='/images/icon.png'></head></html>")
                    "/images/icon.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody("png")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val icon = BestIconFinder(OkHttpClient()).findBestIcon(server.url("/feed/rss.xml").toString())
            assertEquals(server.url("/images/icon.png").toString(), icon)
            assertEquals("/", server.takeRequest().path)
        }
    }

    @Test
    fun errorPagesAreNotMistakenForIcons() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setResponseCode(404).setHeader("Content-Type", "image/png").setBody("error")
            }
            assertNull(BestIconFinder(OkHttpClient()).findBestIcon(server.url("/").toString()))
        }
    }

    @Test
    fun candidateCountIsBounded() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/") {
                    MockResponse().setBody((1..20).joinToString("") { "<link rel='icon' href='/icon$it.png'>" })
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
            assertNull(BestIconFinder(OkHttpClient()).findBestIcon(server.url("/").toString()))
            assertEquals(7, server.requestCount)
        }
    }
}
