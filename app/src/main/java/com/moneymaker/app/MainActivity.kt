package com.moneymaker.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private val httpExecutor = Executors.newCachedThreadPool()

    @Volatile private var lastRedirectedUrl: String? = null

    private val externalDomains = listOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "myaccount.google.com"
    )

    private fun isExternalHost(host: String?): Boolean {
        val h = host ?: return false
        return externalDomains.any { h == it || h.endsWith(".$it") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        swipeRefreshLayout = SwipeRefreshLayout(this)
        swipeRefreshLayout.addView(webView)
        swipeRefreshLayout.setColorSchemeColors(0xFF22C55E.toInt())
        setContentView(swipeRefreshLayout)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(true)
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        swipeRefreshLayout.setOnRefreshListener {
            webView.evaluateJavascript(
                "window.onPullToRefresh && window.onPullToRefresh();",
                null
            )
            webView.reload()
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                return if (isExternalHost(url.host)) {
                    redirectToChromeOnce(url)
                    true
                } else {
                    false
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                if (isExternalHost(url.host)) {
                    runOnUiThread { redirectToChromeOnce(url) }
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                swipeRefreshLayout.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val popupCatcher = WebView(this@MainActivity)
                popupCatcher.settings.javaScriptEnabled = true
                popupCatcher.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        redirectToChromeOnce(request.url)
                        return true
                    }

                    override fun shouldInterceptRequest(
                        v: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        runOnUiThread { redirectToChromeOnce(request.url) }
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        redirectToChromeOnce(Uri.parse(url))
                    }
                }

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popupCatcher
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        val escaped = JSONObject.quote(data.toString())
        webView.post {
            webView.evaluateJavascript(
                "window.onAuthCallback && window.onAuthCallback($escaped);",
                null
            )
        }
    }

    private fun redirectToChromeOnce(url: Uri) {
        val urlString = url.toString()
        if (lastRedirectedUrl == urlString) return
        lastRedirectedUrl = urlString
        openInExternalChrome(url)
    }

    private fun openInExternalChrome(url: Uri) {
        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(this@MainActivity, url)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        httpExecutor.shutdownNow()
        super.onDestroy()
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun httpPost(id: String, url: String, headersJson: String, bodyJson: String) {
            httpExecutor.execute {
                val result = try {
                    doPost(url, headersJson, bodyJson)
                } catch (e: Exception) {
                    val err = JSONObject()
                    err.put("ok", false)
                    err.put("error", e.message ?: e.toString())
                    err.toString()
                }
                deliverResult(id, result)
            }
        }

        private fun doPost(url: String, headersJson: String, bodyJson: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.setRequestProperty("Content-Type", "application/json")

            val headers = JSONObject(headersJson)
            val keys = headers.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                connection.setRequestProperty(k, headers.getString(k))
            }

            val output: OutputStream = connection.outputStream
            output.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            val result = JSONObject()
            result.put("ok", status in 200..299)
            result.put("status", status)
            result.put("body", text)
            return result.toString()
        }

        private fun deliverResult(id: String, resultJson: String) {
            runOnUiThread {
                val escaped = JSONObject.quote(resultJson)
                webView.evaluateJavascript(
                    "window.__nativeHttpCallback && window.__nativeHttpCallback('$id', $escaped);",
                    null
                )
            }
        }
    }
}
