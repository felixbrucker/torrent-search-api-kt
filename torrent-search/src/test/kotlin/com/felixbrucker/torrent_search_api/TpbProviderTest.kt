package com.felixbrucker.torrent_search_api

import android.net.Uri
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TpbProviderTest {

    private val provider = TpbProvider()

    @Before
    fun setup() {
        // Mock Uri for JVM tests as it is an Android API
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val uri = mockk<Uri>()
            every { uri.toString() } returns firstArg()
            uri
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `search ubuntu on TPB returns results`() = runTest {
        val result = provider.search("ubuntu")

        assertTrue("Search should be successful. Error: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val searchResult = result.getOrThrow()

        assertTrue("Results should not be empty", searchResult.results.isNotEmpty())

        val firstItem = searchResult.results.first()
        assertTrue("Name should not be blank", firstItem.name.isNotBlank())
        assertTrue("Name should contain 'ubuntu' (case insensitive). Found: ${firstItem.name}",
            firstItem.name.contains("ubuntu", ignoreCase = true))
        assertNotNull("URI should not be null", firstItem.uri)
        assertTrue("URI should be a magnet link. Found: ${firstItem.uri}",
            firstItem.uri.toString().startsWith("magnet:"))
        assertTrue("Size should be positive. Found: ${firstItem.sizeInBytes}", firstItem.sizeInBytes > 0)
        assertTrue("Seeders should be non-negative. Found: ${firstItem.seeder}", firstItem.seeder >= 0)
        assertTrue("Leechers should be non-negative. Found: ${firstItem.leecher}", firstItem.leecher >= 0)
        assertNotNull("UploadedAt should not be null", firstItem.uploadedAt)
    }

    @Test
    fun `search with category and ordering on TPB`() = runTest {
        val result = provider.search("Terminator", category = Category.VIDEO, orderBy = OrderBy.SeederDescending)

        assertTrue("Search with category should be successful. Error: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val searchResult = result.getOrThrow()
        assertTrue("Results should not be empty for category search", searchResult.results.isNotEmpty())
    }
}
