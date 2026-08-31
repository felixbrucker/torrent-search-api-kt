package com.felixbrucker.torrent_search_api

import android.net.Uri
import java.time.Instant

/**
 * Represents a single search result item from a torrent provider.
 */
data class SearchResultItem(
    val name: String,
    val uploadedAt: Instant,
    val uri: Uri,
    val sizeInBytes: Long,
    val seeder: Int,
    val leecher: Int,
    val uploadedBy: String?
)

/**
 * Represents a paginated list of search results.
 */
data class PaginatedSearchResult(
    val results: List<SearchResultItem>,
    val page: Int,
    val hasNextPage: Boolean
)

/**
 * Categories for torrent search.
 */
enum class Category {
    VIDEO,
    ANIME_ENGLISH_TRANSLATED,
}

/**
 * Ordering options for search results.
 */
enum class OrderBy {
    SeederDescending,
    SeederAscending,
    DateDescending,
    DateAscending,
    SizeDescending,
    SizeAscending
}
