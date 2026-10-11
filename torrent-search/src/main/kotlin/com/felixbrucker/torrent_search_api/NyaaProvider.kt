package com.felixbrucker.torrent_search_api

import androidx.core.net.toUri
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class NyaaProvider : TorrentProvider {
    private val baseUrl = "https://nyaa.si"

    override suspend fun search(
        term: String,
        category: Category?,
        page: Int?,
        orderBy: OrderBy?
    ): Result<PaginatedSearchResult> = runCatching {
        val encodedTerm = URLEncoder.encode(term, "UTF-8")
        val nyaaCategory = mapCategory(category)
        val sort = mapSort(orderBy)
        val order = mapOrder(orderBy)
        val nyaaPage = page ?: 1

        val url = "$baseUrl/?f=0&c=$nyaaCategory&q=$encodedTerm&p=$nyaaPage&s=$sort&o=$order"
        val doc = ScraperUtils.fetchDocument(url)
        val rows = doc.select("table.torrent-list tbody tr")

        val results = rows.mapNotNull { row ->
            try {
                val tds = row.select("td")
                if (tds.size < 8) return@mapNotNull null

                val nameLink = tds[1].select("a").last() ?: return@mapNotNull null
                val name = nameLink.attr("title").takeIf { it.isNotEmpty() } ?: nameLink.text()

                val magnetLink = tds[2].select("a[href^=magnet:]").attr("href")
                val downloadLink = tds[2].select("a[href^=/download/]").attr("href")
                val uri = if (magnetLink.isNotEmpty()) magnetLink.toUri()
                          else if (downloadLink.isNotEmpty()) "$baseUrl$downloadLink".toUri()
                          else return@mapNotNull null

                val size = ScraperUtils.parseSize(tds[3].text())
                val dateStr = tds[4].text()
                val uploadedAt = parseNyaaDate(dateStr)

                val seeders = tds[5].text().toIntOrNull() ?: 0
                val leechers = tds[6].text().toIntOrNull() ?: 0

                SearchResultItem(
                    name = name,
                    uploadedAt = uploadedAt,
                    uri = uri,
                    sizeInBytes = size,
                    seeder = seeders,
                    leecher = leechers,
                    uploadedBy = null // Nyaa doesn't show uploader in the list easily
                )
            } catch (_: Exception) {
                null
            }
        }

        PaginatedSearchResult(
            results = results,
            page = nyaaPage,
            hasNextPage = doc.selectFirst("ul.pagination li.next:not(.disabled)") != null
        )
    }

    private fun mapCategory(category: Category?): String = when (category) {
        Category.VIDEO, Category.MOVIES, Category.TV_SHOWS, null -> "0_0"
        Category.ANIME_ENGLISH_TRANSLATED -> "1_2"
    }

    private fun mapSort(orderBy: OrderBy?): String = when (orderBy) {
        OrderBy.SeederDescending, OrderBy.SeederAscending, null -> "seeders"
        OrderBy.DateDescending, OrderBy.DateAscending -> "id"
        OrderBy.SizeDescending, OrderBy.SizeAscending -> "size"
    }

    private fun mapOrder(orderBy: OrderBy?): String = when (orderBy) {
        OrderBy.SeederDescending, OrderBy.DateDescending, OrderBy.SizeDescending, null -> "desc"
        OrderBy.SeederAscending, OrderBy.DateAscending, OrderBy.SizeAscending -> "asc"
    }

    private fun parseNyaaDate(dateStr: String): Instant {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val localTime = LocalDateTime.parse(dateStr, formatter)

        return localTime.toInstant(ZoneOffset.UTC)
    }
}
