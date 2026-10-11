package com.felixbrucker.torrent_search_api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Data class representing a resolved Cloudflare clearance session.
 */
data class CloudflareSession(
    val clearanceCookie: String,
    val userAgent: String
)

/**
 * Resolver that loads a target URL in a WebView to complete Cloudflare challenges
 * and retrieve session credentials (cookies and user agent).
 */
class CloudflareWebViewResolver(
    private val context: Context,
    var onPromoteToBottomSheet: ((WebView) -> Unit)? = null,
    var onDismissBottomSheet: (() -> Unit)? = null,
    private val timeoutSeconds: Long = 60
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun resolve(targetUrl: String): CloudflareSession = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            var scheduledCheck: ScheduledFuture<*>? = null
            var timeoutFuture: ScheduledFuture<*>? = null
            var promoted = false
            var isResolved = false

            val executor = Executors.newSingleThreadScheduledExecutor()

            fun cleanup() {
                scheduledCheck?.cancel(true)
                timeoutFuture?.cancel(true)
                executor.shutdown()
                mainHandler.post {
                    if (promoted) {
                        onDismissBottomSheet?.invoke()
                    }
                    webView?.apply {
                        stopLoading()
                        webViewClient = WebViewClient()
                        destroy()
                    }
                    webView = null
                }
            }

            continuation.invokeOnCancellation {
                cleanup()
            }

            try {
                val wv = WebView(context.applicationContext)
                webView = wv

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = WebSettings.getDefaultUserAgent(context)
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        injectTurnstileBypass(view)
                    }
                }

                wv.loadUrl(targetUrl)

                val startTime = System.currentTimeMillis()
                scheduledCheck = executor.scheduleWithFixedDelay({
                    mainHandler.post {
                        if (isResolved || !continuation.isActive) return@post

                        val cookies = CookieManager.getInstance().getCookie(targetUrl) ?: ""
                        if (cookies.contains("cf_clearance")) {
                            isResolved = true
                            val userAgent = wv.settings.userAgentString ?: WebSettings.getDefaultUserAgent(context)
                            val session = CloudflareSession(
                                clearanceCookie = cookies,
                                userAgent = userAgent
                            )
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(session)
                            }
                            return@post
                        }

                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > 6000 && !promoted) {
                            val title = wv.title ?: ""
                            if (title.contains("Just a moment", ignoreCase = true) ||
                                title.contains("Verification", ignoreCase = true) ||
                                title.contains("Cloudflare", ignoreCase = true) ||
                                cookies.isEmpty()
                            ) {
                                promoted = true
                                onPromoteToBottomSheet?.invoke(wv)
                            }
                        }

                        injectTurnstileBypass(wv)
                    }
                }, 500, 500, TimeUnit.MILLISECONDS)

                timeoutFuture = executor.schedule({
                    mainHandler.post {
                        if (!isResolved && continuation.isActive) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("Cloudflare resolution timed out after $timeoutSeconds seconds")
                                )
                            }
                        }
                    }
                }, timeoutSeconds, TimeUnit.SECONDS)

            } catch (e: Exception) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun injectTurnstileBypass(webView: WebView?) {
        webView?.evaluateJavascript(
            """
            (function() {
                function clickTurnstile() {
                    const iframes = document.querySelectorAll('iframe');
                    for (const iframe of iframes) {
                        const src = iframe.src || '';
                        if (src.includes('turnstile') || src.includes('challenge')) {
                            const rect = iframe.getBoundingClientRect();
                            const x = rect.left + 30;
                            const y = rect.top + rect.height / 2;
                            const el = document.elementFromPoint(x, y);
                            if (el) {
                                el.click();
                            } else {
                                iframe.click();
                            }
                        }
                    }
                }
                clickTurnstile();
            })();
            """.trimIndent(), null
        )
    }
}
