package com.felixbrucker.torrent_search_api

/**
 * Interface defining the contract for a torrent provider search service.
 */
interface TorrentProvider {
    /**
     * Searches for torrents based on the given criteria.
     *
     * @param term The search term.
     * @param category The category to search in, or null for all.
     * @param page The page number to retrieve, or null for the first page.
     * @param orderBy The ordering of the results, defaults to [OrderBy.SeederDescending].
     * @return A [Result] containing [PaginatedSearchResult] on success.
     */
    suspend fun search(
        term: String,
        category: Category? = null,
        page: Int? = null,
        orderBy: OrderBy? = OrderBy.SeederDescending
    ): Result<PaginatedSearchResult>
}
