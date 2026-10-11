package com.felixbrucker.torrent_search_api

import android.net.Uri
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExtToProviderTest {

    private val provider = ExtToProvider()

    @Before
    fun setup() {
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
    fun `search regular show on ExtTo returns results or cloudflare challenge`() = runTest {
        val result = provider.search("regular show")

        if (result.isSuccess) {
            val searchResult = result.getOrThrow()
            assertTrue("Results should not be empty", searchResult.results.isNotEmpty())
            val firstItem = searchResult.results.first()
            assertTrue("Name should not be blank", firstItem.name.isNotBlank())
            assertNotNull("URI should not be null", firstItem.uri)
            assertTrue("Size should be positive", firstItem.sizeInBytes > 0)
            assertTrue("Seeders should be non-negative", firstItem.seeder >= 0)
            assertTrue("Leechers should be non-negative", firstItem.leecher >= 0)
            assertNotNull("UploadedAt should not be null", firstItem.uploadedAt)
        } else {
            val error = result.exceptionOrNull()
            assertNotNull("Error should not be null", error)
            val msg = error?.message ?: ""
            assertTrue(
                "Failure should be due to Cloudflare protection. Message: $msg",
                msg.contains("Cloudflare", ignoreCase = true) || msg.contains("403", ignoreCase = true)
            )
        }
    }

    @Test
    fun `search with category and ordering on ExtTo`() = runTest {
        val result = provider.search("regular show", category = Category.TV_SHOWS, orderBy = OrderBy.SeederDescending)

        if (result.isSuccess) {
            val searchResult = result.getOrThrow()
            assertTrue("Results should not be empty for category search", searchResult.results.isNotEmpty())
        } else {
            val error = result.exceptionOrNull()
            assertNotNull("Error should not be null", error)
            val msg = error?.message ?: ""
            assertTrue(
                "Failure should be due to Cloudflare protection. Message: $msg",
                msg.contains("Cloudflare", ignoreCase = true) || msg.contains("403", ignoreCase = true)
            )
        }
    }
}
