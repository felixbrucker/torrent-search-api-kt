package com.felixbrucker.torrent_search_api

import android.net.Uri
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class ExtToProviderTest {

    private val provider = ExtToProvider()

    companion object {
        private var liveSession: CloudflareSession? = null

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            try {
                val script = """
                    from playwright.sync_api import sync_playwright
                    import time, json

                    with sync_playwright() as p:
                        browser = p.chromium.launch(
                            executable_path='/usr/bin/google-chrome',
                            headless=False,
                            args=['--no-sandbox', '--disable-blink-features=AutomationControlled']
                        )
                        context = browser.new_context()
                        page = context.new_page()
                        page.goto('https://ext.to/browse/?page_size=100&sort=seeds&order=desc&user_sort=1&cat=2&q=regular+show')
                        time.sleep(5)
                        for iframe in page.frames:
                            if 'turnstile' in iframe.url or 'challenge' in iframe.url:
                                elem = iframe.frame_element()
                                box = elem.bounding_box()
                                if box:
                                    page.mouse.click(box['x'] + 30, box['y'] + box['height'] / 2)
                        for _ in range(15):
                            time.sleep(1)
                            try:
                                if 'Just a moment' not in page.title():
                                    break
                            except:
                                pass
                        cookies = context.cookies('https://ext.to')
                        ua = page.evaluate('navigator.userAgent')
                        cookie_str = '; '.join([f"{c['name']}={c['value']}" for c in cookies])
                        print(json.dumps({'cookies': cookie_str, 'ua': ua}))
                        browser.close()
                """.trimIndent()

                val process = ProcessBuilder("xvfb-run", "-a", "python3", "-c", script).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()

                val cookies = Regex(""""cookies":\s*"([^"]+)"""").find(output)?.groupValues?.get(1)
                val ua = Regex(""""ua":\s*"([^"]+)"""").find(output)?.groupValues?.get(1)

                if (!cookies.isNullOrEmpty() && !ua.isNullOrEmpty() && cookies.contains("cf_clearance")) {
                    liveSession = CloudflareSession(clearanceCookie = cookies, userAgent = ua)
                }
            } catch (_: Exception) {
            }
        }
    }

    @Before
    fun setup() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val uri = mockk<Uri>()
            every { uri.toString() } returns firstArg()
            uri
        }

        if (liveSession != null) {
            provider.currentSession = liveSession
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `search regular show on ExtTo returns results`() = runTest {
        val result = provider.search("regular show")

        assertTrue("Search should be successful. Error: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val searchResult = result.getOrThrow()
        assertTrue("Results should not be empty", searchResult.results.isNotEmpty())

        val firstItem = searchResult.results.first()
        assertTrue("Name should not be blank", firstItem.name.isNotBlank())
        assertTrue("Name should contain 'regular show' (case insensitive). Found: ${firstItem.name}",
            firstItem.name.contains("regular show", ignoreCase = true))
        assertNotNull("URI should not be null", firstItem.uri)
        assertTrue("URI should be an ext.to link. Found: ${firstItem.uri}",
            firstItem.uri.toString().startsWith("https://ext.to"))
        assertTrue("Size should be positive. Found: ${firstItem.sizeInBytes}", firstItem.sizeInBytes > 0)
        assertTrue("Seeders should be non-negative. Found: ${firstItem.seeder}", firstItem.seeder >= 0)
        assertTrue("Leechers should be non-negative. Found: ${firstItem.leecher}", firstItem.leecher >= 0)
        assertNotNull("UploadedAt should not be null", firstItem.uploadedAt)
    }

    @Test
    fun `search with category and ordering on ExtTo`() = runTest {
        val result = provider.search("regular show", category = Category.TV_SHOWS, orderBy = OrderBy.SeederDescending)

        assertTrue("Search with category should be successful. Error: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val searchResult = result.getOrThrow()
        assertTrue("Results should not be empty for category search", searchResult.results.isNotEmpty())
    }
}
