package me.ash.reader.infrastructure.rss

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.ash.reader.infrastructure.net.awaitBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class BestIconFinder(client: OkHttpClient) {

    private val client = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val defaultFormats = listOf("apple-touch-icon", "svg", "png", "ico", "gif", "jpg")

    suspend fun findBestIcon(siteUrl: String): String? = withTimeoutOrNull(10_000) {
        val parsedUrl = siteUrl.toHttpUrlOrNull()
            ?: "https://$siteUrl".toHttpUrlOrNull()
            ?: return@withTimeoutOrNull null
        val homeUrl = parsedUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val links = try {
            val html = client.newCall(Request.Builder().url(homeUrl).build()).awaitBody {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                val source = it.body.source()
                source.request(MAX_HTML_BYTES)
                source.buffer.readUtf8(minOf(source.buffer.size, MAX_HTML_BYTES))
            }
            findIconLinks(homeUrl.toString(), html)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf("/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/favicon.ico")
                .mapNotNull { homeUrl.resolve(it)?.toString() }
        }

        links.take(MAX_CANDIDATES).mapNotNull { fetchIconDetails(it) }
            .minWithOrNull(compareBy({ defaultFormats.indexOf(it.format) }, { -it.size }))?.url
    }

    private fun findIconLinks(baseUrl: String, html: String): List<String> {
        val document = Jsoup.parse(html, baseUrl)
        return buildList {
            addAll(document.select("link[rel~=apple-touch-icon]").map { it.absUrl("href") })
            addAll(document.select("link[rel~=icon]").map { it.absUrl("href") })
            document.selectFirst("meta[property=og:image]")?.absUrl("content")?.let { add(it) }
            baseUrl.toHttpUrlOrNull()?.resolve("/favicon.ico")?.let { add(it.toString()) }
        }.filter { it.toHttpUrlOrNull() != null }.distinct()
    }

    private suspend fun fetchIconDetails(url: String): Icon? = try {
        client.newCall(Request.Builder().url(url).build()).awaitBody { response ->
            if (!response.isSuccessful) return@awaitBody null
            val contentType = response.header("Content-Type").orEmpty()
            val format = when {
                url.contains("apple-touch-icon") && contentType.startsWith("image/") -> "apple-touch-icon"
                contentType.contains("svg") -> "svg"
                contentType.contains("png") -> "png"
                contentType.contains("ico") -> "ico"
                contentType.contains("gif") -> "gif"
                contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
                else -> return@awaitBody null
            }
            val body = response.body.source()
            var size = 0L
            val buffer = okio.Buffer()
            while (size <= MAX_ICON_BYTES) {
                val read = body.read(buffer, 8192)
                if (read == -1L) break
                size += read
                buffer.clear()
            }
            if (size == 0L || size > MAX_ICON_BYTES) null else Icon(url, format, size.toInt())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    data class Icon(val url: String, val format: String, val size: Int)

    private companion object {
        const val MAX_CANDIDATES = 6
        const val MAX_HTML_BYTES = 512L * 1024
        const val MAX_ICON_BYTES = 512L * 1024
    }
}
