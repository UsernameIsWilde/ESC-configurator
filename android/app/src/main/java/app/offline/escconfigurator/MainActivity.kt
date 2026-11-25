package app.offline.escconfigurator

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Hosts the ESC Configurator web app in a WebView.
 *
 * The web app itself is served from the APK under a fake https origin (which
 * makes it a "secure context", like on the real website). Requests to
 * firmware sources go through [OfflineStore], everything else is blocked.
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var usbSerial: UsbSerialBridge
    private lateinit var offlineStore: OfflineStore

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: ByteArray? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileChooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileChooserCallback = null
    }

    private val fileSaver = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val data = pendingSave
        pendingSave = null

        if (uri != null && data != null) {
            val message = try {
                contentResolver.openOutputStream(uri)?.use { it.write(data) }
                    ?: throw IOException("No output stream for $uri")
                R.string.file_saved
            } catch (e: Exception) {
                Log.w(TAG, "Saving file failed", e)
                R.string.file_save_failed
            }

            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val barStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)

        offlineStore = OfflineStore(applicationContext)

        webView = WebView(this)
        root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.chrome))
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)

        // Keep the page clear of system bars, display cutouts and the keyboard
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)

            WindowInsetsCompat.CONSUMED
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        usbSerial = UsbSerialBridge(this, webView)
        webView.addJavascriptInterface(usbSerial, UsbSerialBridge.JS_NAME)
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppWebChromeClient()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // Requests made by a service worker bypass the WebViewClient
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest) = intercept(request)
        })

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                // Keep the page (and an open connection) alive
                moveTaskToBack(true)
            }
        }

        webView.loadUrl(START_URL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // Started again because a USB device was plugged in
        usbSerial.scanDevices()
    }

    override fun onResume() {
        super.onResume()
        usbSerial.onResume()
    }

    override fun onPause() {
        // The WebView is deliberately not paused: a flash in progress has to
        // survive the file picker or a short trip to another app.
        usbSerial.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        usbSerial.destroy()
        webView.destroy()
        super.onDestroy()
    }

    /* ---------------------------------------------------------------------
     * Called by UsbSerialBridge (main thread)
     * ------------------------------------------------------------------- */

    /** Do not let the screen turn off in the middle of talking to an ESC. */
    fun onSerialActive(active: Boolean) {
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Color of the area behind the system bars, it has to match the header of
     * whatever theme the page is showing. Anything that is not a color
     * restores the default.
     */
    fun setChromeColor(color: String) {
        val parsed = try {
            Color.parseColor(color)
        } catch (e: Exception) {
            ContextCompat.getColor(this, R.color.chrome)
        }

        root.setBackgroundColor(parsed)
    }

    fun saveFile(name: String, data: ByteArray) {
        pendingSave = data
        try {
            fileSaver.launch(name)
        } catch (e: ActivityNotFoundException) {
            pendingSave = null
            Toast.makeText(this, R.string.file_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http" && uri.scheme != "mailto") {
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /* ---------------------------------------------------------------------
     * Request handling
     * ------------------------------------------------------------------- */

    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val scheme = uri.scheme?.lowercase()

        return when {
            scheme != "https" && scheme != "http" -> null
            scheme == "https" && uri.host == APP_HOST -> serveAppFile(uri)
            request.method.equals("GET", ignoreCase = true) -> offlineStore.handle(uri) ?: blocked()
            else -> blocked()
        }
    }

    private fun serveAppFile(uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty().trimStart('/')
        val isFile = path.substringAfterLast('/').contains('.')

        // Anything that does not look like a file is a route of the web app
        val asset = if (isFile) path else "index.html"

        return try {
            WebResourceResponse(mimeFor(asset), null, assets.open("$WWW_ROOT/$asset"))
        } catch (e: IOException) {
            WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"
        "js" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    /** No analytics, no third parties: only firmware sources get through. */
    private fun blocked() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private inner class AppWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = intercept(request)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.host == APP_HOST) {
                return false
            }

            // Links to GitHub, Discord and the like open in the browser
            openExternal(request.url.toString())

            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            usbSerial.onPageStarted()

            // A fresh page starts out in the default theme
            setChromeColor("")
        }

        override fun onPageFinished(view: WebView, url: String?) {
            usbSerial.onPageReady()
        }
    }

    private inner class AppWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback

            return try {
                // Hex files have no registered type, offer everything
                fileChooser.launch(arrayOf("*/*"))
                true
            } catch (e: ActivityNotFoundException) {
                fileChooserCallback = null
                false
            }
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            }

            return true
        }
    }

    companion object {
        private const val TAG = "EscConfigurator"

        // Never resolves on the internet, reserved by Google for exactly this
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$APP_HOST/"
        private const val WWW_ROOT = "www"
    }
}
