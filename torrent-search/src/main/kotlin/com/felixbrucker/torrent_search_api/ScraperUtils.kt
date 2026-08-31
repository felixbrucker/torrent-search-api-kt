package com.felixbrucker.torrent_search_api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object ScraperUtils {
    var client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchDocument(url: String): Document = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        val call = client.newCall(request)
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

    fun parseSize(sizeStr: String): Long {
        val regex = Regex("""([\d.]+)\s*([KMGTPE]i?B|Bytes?)""", RegexOption.IGNORE_CASE)
        val match = regex.find(sizeStr) ?: return 0L
        val value = match.groupValues[1].toDouble()
        val unit = match.groupValues[2].uppercase()

        val factor = when {
            unit.startsWith("E") -> 1024L * 1024L * 1024L * 1024L * 1024L * 1024L
            unit.startsWith("P") -> 1024L * 1024L * 1024L * 1024L * 1024L
            unit.startsWith("T") -> 1024L * 1024L * 1024L * 1024L
            unit.startsWith("G") -> 1024L * 1024L * 1024L
            unit.startsWith("M") -> 1024L * 1024L
            unit.startsWith("K") -> 1024L
            else -> 1L
        }
        return (value * factor).toLong()
    }
}
