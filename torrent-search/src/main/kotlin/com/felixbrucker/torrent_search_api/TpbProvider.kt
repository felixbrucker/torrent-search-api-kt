package com.felixbrucker.torrent_search_api

import androidx.core.net.toUri
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TpbProvider : TorrentProvider {
    private val baseUrl = "https://tpb.party"

    override suspend fun search(
        term: String,
        category: Category?,
        page: Int?,
        orderBy: OrderBy?
    ): Result<PaginatedSearchResult> = runCatching {
        val tpbCategory = mapCategory(category)
        val tpbOrder = mapOrder(orderBy)
        val tpbPage = (page ?: 1)

        val url = "$baseUrl/search/$term/$tpbPage/$tpbOrder/$tpbCategory"
        val doc = ScraperUtils.fetchDocument(url)
        val rows = doc.select("table#searchResult tr").filter { it.select("td").isNotEmpty() }

        val results = rows.mapNotNull { row ->
            try {
                val detNameLink = row.selectFirst("div.detName a")
                val name: String
                val magnetLink: String
                val uploadedAt: Instant
                val size: Long
                val uploadedBy: String?
                val seeders: Int
                val leechers: Int

                if (detNameLink != null) {
                    // Double View
                    name = detNameLink.text()
                    magnetLink = row.selectFirst("a[href^=magnet:]")?.attr("href") ?: return@mapNotNull null
                    val descText = row.selectFirst("font.detDesc")?.text() ?: ""
                    uploadedAt = parseTpbDate(descText.substringAfter("Uploaded ").substringBefore(","))
                    size = parseTpbSize(descText)
                    uploadedBy = parseTpbUploader(descText)

                    val alignRightTds = row.select("td[align=right]")
                    seeders = alignRightTds.getOrNull(0)?.text()?.toIntOrNull() ?: 0
                    leechers = alignRightTds.getOrNull(1)?.text()?.toIntOrNull() ?: 0
                } else {
                    // Single View
                    val tds = row.select("td")
                    if (tds.size < 7) return@mapNotNull null

                    name = tds[1].selectFirst("a")?.text() ?: return@mapNotNull null
                    magnetLink = tds[3].selectFirst("a[href^=magnet:]")?.attr("href") ?: return@mapNotNull null
                    uploadedAt = parseTpbDate(tds[2].text())
                    size = ScraperUtils.parseSize(tds[4].text())
                    seeders = tds[5].text().toIntOrNull() ?: 0
                    leechers = tds[6].text().toIntOrNull() ?: 0
                    uploadedBy = tds.getOrNull(7)?.selectFirst("a")?.text()
                }

                SearchResultItem(
                    name = name,
                    uploadedAt = uploadedAt,
                    uri = magnetLink.toUri(),
                    sizeInBytes = size,
                    seeder = seeders,
                    leecher = leechers,
                    uploadedBy = uploadedBy
                )
            } catch (_: Exception) {
                null
            }
        }

        PaginatedSearchResult(
            results = results,
            page = tpbPage,
            hasNextPage = results.isNotEmpty()
        )
    }

    private fun mapCategory(category: Category?): Int = when (category) {
        Category.ANIME_ENGLISH_TRANSLATED, null -> 0
        Category.VIDEO, Category.MOVIES, Category.TV_SHOWS -> 200
    }

    private fun mapOrder(orderBy: OrderBy?): Int = when (orderBy) {
        OrderBy.SeederDescending, null -> 7
        OrderBy.SeederAscending -> 8
        OrderBy.DateDescending -> 3
        OrderBy.DateAscending -> 4
        OrderBy.SizeDescending -> 5
        OrderBy.SizeAscending -> 6
    }

    private fun parseTpbDate(datePart: String): Instant {
        val zoneId = ZoneId.of("UTC+02:00")
        val now = LocalDateTime.now(zoneId)

        val dateStr = datePart.replace("\u00a0", " ").replace("&nbsp;", " ").trim()
        return when {
            dateStr.contains("min ago") || dateStr.contains("mins ago") -> {
                val mins = dateStr.substringBefore(" min").trim().toLongOrNull() ?: 0L
                now.minusMinutes(mins).atZone(zoneId).toInstant()
            }
            dateStr.contains("Today") -> {
                val time = dateStr.substringAfter("Today ").trim()
                val localTime = LocalDateTime.parse("${now.toLocalDate()} $time", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                localTime.atZone(zoneId).toInstant()
            }
            dateStr.contains("Y-day") -> {
                val time = dateStr.substringAfter("Y-day ").trim()
                val localTime = LocalDateTime.parse("${now.toLocalDate().minusDays(1)} $time", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                localTime.atZone(zoneId).toInstant()
            }
            dateStr.contains(":") && !dateStr.contains("-") -> {
                val localTime = LocalDateTime.parse("${now.toLocalDate()} $dateStr", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                localTime.atZone(zoneId).toInstant()
            }
            dateStr.contains(":") -> {
                // MM-dd HH:mm
                val localTime = LocalDateTime.parse("${now.year}-$dateStr", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                localTime.atZone(zoneId).toInstant()
            }
            else -> {
                // MM-dd yyyy
                val localTime = LocalDateTime.parse("$dateStr 00:00", DateTimeFormatter.ofPattern("MM-dd yyyy HH:mm"))
                localTime.atZone(zoneId).toInstant()
            }
        }
    }

    private fun parseTpbSize(desc: String): Long {
        val sizeStr = desc.substringAfter("Size ").substringBefore(",")
        return ScraperUtils.parseSize(sizeStr)
    }

    private fun parseTpbUploader(desc: String): String? {
        return desc.substringAfter("ULed by ").trim().takeIf { it.isNotEmpty() }
    }
}
