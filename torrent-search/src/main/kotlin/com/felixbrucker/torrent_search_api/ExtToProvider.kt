package com.felixbrucker.torrent_search_api

import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExtToProvider(
    private val resolver: CloudflareWebViewResolver? = null
) : TorrentProvider {
    private val baseUrl = "https://ext.to"

    @Volatile
    var currentSession: CloudflareSession? = null

    override suspend fun search(
        term: String,
        category: Category?,
        page: Int?,
        orderBy: OrderBy?
    ): Result<PaginatedSearchResult> = runCatching {
        val currentPage = page ?: 1
        val encodedTerm = URLEncoder.encode(term, "UTF-8")
        val (sort, order) = mapOrderBy(orderBy)
        val categoryParams = mapCategory(category)

        val urlBuilder = StringBuilder("$baseUrl/browse/?page_size=100&sort=$sort&order=$order&user_sort=1")
        if (categoryParams.isNotEmpty()) {
            urlBuilder.append("&$categoryParams")
        }
        urlBuilder.append("&q=$encodedTerm")
        if (currentPage > 1) {
            urlBuilder.append("&page=$currentPage")
        }

        val url = urlBuilder.toString()
        val doc = fetchDocumentWithCloudflare(url)
        parseSearchResults(doc, currentPage)
    }

    private suspend fun fetchDocumentWithCloudflare(url: String): Document {
        return try {
            val doc = fetchDocumentInternal(url)
            if (isCloudflareChallenge(doc)) {
                if (resolver != null) {
                    val session = resolver.resolve(url)
                    currentSession = session
                    val retryDoc = fetchDocumentInternal(url)
                    if (isCloudflareChallenge(retryDoc)) {
                        throw IOException("Cloudflare challenge could not be resolved.")
                    }
                    retryDoc
                } else {
                    throw IOException("Cloudflare challenge encountered but no resolver provided.")
                }
            } else {
                doc
            }
        } catch (e: IOException) {
            if (resolver != null && currentSession == null) {
                val session = resolver.resolve(url)
                currentSession = session
                val retryDoc = fetchDocumentInternal(url)
                if (isCloudflareChallenge(retryDoc)) {
                    throw IOException("Cloudflare challenge could not be resolved.")
                }
                retryDoc
            } else {
                throw e
            }
        }
    }

    private suspend fun fetchDocumentInternal(url: String): Document = suspendCancellableCoroutine { continuation ->
        val session = currentSession
        val userAgent = session?.userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)

        if (session != null) {
            requestBuilder.header("Cookie", session.clearanceCookie)
        }

        val call = ScraperUtils.client.newCall(requestBuilder.build())
        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (it.code == 403) {
                        continuation.resumeWithException(IOException("HTTP 403 Forbidden - Cloudflare challenge active"))
                        return
                    }
                    if (!it.isSuccessful) {
                        continuation.resumeWithException(IOException("Unexpected code $it"))
                        return
                    }
                    val body = it.body.string()
                    continuation.resume(Jsoup.parse(body, url))
                }
            }
        })
    }

    private fun isCloudflareChallenge(doc: Document): Boolean {
        val title = doc.title()
        return title.contains("Just a moment", ignoreCase = true) ||
                title.contains("Verification", ignoreCase = true) ||
                doc.selectFirst("div.main-content noscript") != null ||
                doc.selectFirst("script[src*=\"challenge-platform\"]") != null
    }

    internal fun parseSearchResults(doc: Document, page: Int): PaginatedSearchResult {
        if (isCloudflareChallenge(doc)) {
            throw IOException("Cloudflare challenge page detected in parseSearchResults")
        }

        val rows = doc.select("table.search-table tbody tr").ifEmpty {
            doc.select("table.search-table tr")
        }.filter { it.select("td").size >= 6 }

        val results = rows.mapNotNull { row ->
            try {
                val titleLink = row.selectFirst("a.torrent-title-link") ?: return@mapNotNull null
                val name = titleLink.text().trim()
                if (name.isBlank()) return@mapNotNull null

                val href = titleLink.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null
                val fullUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                val uri = fullUrl.toUri()

                val tds = row.select("td")
                val sizeText = tds[1].text().replace("Size", "").trim()
                val size = ScraperUtils.parseSize(sizeText)

                val dateSpan = tds[3].selectFirst("span[title]")
                val dateStr = dateSpan?.attr("title") ?: tds[3].text().replace("Age", "").trim()
                val uploadedAt = parseExtToDate(dateStr)

                val seeders = tds[4].text().replace("Seeds", "").replace(",", "").trim().toIntOrNull() ?: 0
                val leechers = tds[5].text().replace("Leechs", "").replace(",", "").trim().toIntOrNull() ?: 0

                val uploaderA = row.selectFirst("a.external-user")
                val uploadedBy = uploaderA?.text()?.trim()?.takeIf { it.isNotEmpty() }

                SearchResultItem(
                    name = name,
                    uploadedAt = uploadedAt,
                    uri = uri,
                    sizeInBytes = size,
                    seeder = seeders,
                    leecher = leechers,
                    uploadedBy = uploadedBy
                )
            } catch (_: Exception) {
                null
            }
        }

        val hasNextPage = doc.select("ul.pages li.active + li").isNotEmpty() ||
                doc.select("a[href*=\"page=${page + 1}\"]").isNotEmpty() ||
                results.size >= 100

        return PaginatedSearchResult(
            results = results,
            page = page,
            hasNextPage = hasNextPage
        )
    }

    private fun mapCategory(category: Category?): String = when (category) {
        Category.MOVIES -> "cat=1"
        Category.TV_SHOWS -> "cat=2"
        Category.ANIME_ENGLISH_TRANSLATED -> "cat=7&sub_cat=7"
        Category.VIDEO, null -> ""
    }

    private fun mapOrderBy(orderBy: OrderBy?): Pair<String, String> = when (orderBy) {
        OrderBy.SeederDescending, null -> Pair("seeds", "desc")
        OrderBy.SeederAscending -> Pair("seeds", "asc")
        OrderBy.DateDescending -> Pair("age", "desc")
        OrderBy.DateAscending -> Pair("age", "asc")
        OrderBy.SizeDescending -> Pair("size", "desc")
        OrderBy.SizeAscending -> Pair("size", "asc")
    }

    private fun parseExtToDate(dateStr: String): Instant {
        return try {
            val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
            val localDate = LocalDate.parse(dateStr.trim(), formatter)
            localDate.atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: Exception) {
            Instant.EPOCH
        }
    }
}
