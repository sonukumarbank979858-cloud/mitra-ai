package com.sonuclasses.mitra.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/** Mitra's own in-app browser. The web agent drives this WebView directly — no Accessibility needed. */
class BrowserActivity : ComponentActivity() {

    lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var status: TextView
    private lateinit var progressBar: ProgressBar
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); fileCb = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; setTextColor(Color.parseColor("#3A2E8C")); gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6)); setOnClickListener { onClick() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); fitsSystemWindows = true }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EEEAFB")); setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        urlBar = EditText(this).apply {
            isSingleLine = true; hint = "वेबसाइट या खोज लिखें"; textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_GO; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, _, _ -> load(text.toString()); web.requestFocus(); true }
        }
        bar.addView(btn("←") { if (web.canGoBack()) web.goBack() })
        bar.addView(urlBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn("⟳") { web.reload() })
        bar.addView(btn("✕") { finish() })
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        status = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#FFF4C2")); setTextColor(Color.parseColor("#4A3B00")); textSize = 14f
            setPadding(dp(12), dp(6), dp(12), dp(6)); visibility = View.GONE
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val s = req.url.scheme ?: return false
                    if (s == "http" || s == "https" || s == "about" || s == "data" || s == "blob") return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                    return true
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true; if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false; if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, p: Int) {
                    this@BrowserActivity.progressBar.progress = p; this@BrowserActivity.progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                }
                override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCb?.onReceiveValue(null); fileCb = cb
                    return try { pick.launch(params.createIntent()); true } catch (e: Exception) { fileCb = null; false }
                }
            }
            setDownloadListener { url, ua, cd, mime, _ ->
                try {
                    val name = URLUtil.guessFileName(url, cd, mime)
                    val req = DownloadManager.Request(Uri.parse(url))
                        .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        .addRequestHeader("User-Agent", ua)
                        .setMimeType(mime)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this@BrowserActivity, "डाउनलोड हो रहा है: $name", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        root.addView(bar); root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(3))); root.addView(status)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        current = this
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else load(intent?.getStringExtra(EXTRA_URL) ?: "https://www.google.com")
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let { load(it) }
    }

    override fun onDestroy() {
        if (current === this) current = null
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    fun load(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            !s.contains(' ') && s.contains('.') -> "https://$s"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
        }
        loading = true
        web.loadUrl(url)
    }

    fun setStatus(t: String?) {
        if (t.isNullOrBlank()) status.visibility = View.GONE
        else { status.text = t; status.visibility = View.VISIBLE }
    }

    /** Small JPEG of what the WebView shows, for the agent's eyes. */
    fun screenshot(): ByteArray? = try {
        val w = web.width; val h = web.height
        if (w <= 0 || h <= 0) null else {
            val scale = minOf(1f, 720f / w)
            val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(bmp); c.scale(scale, scale); c.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat()); web.draw(c)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 60, it); bmp.recycle() }.toByteArray()
        }
    } catch (e: Throwable) { null }

    companion object {
        const val EXTRA_URL = "url"
        @Volatile var current: BrowserActivity? = null
        @Volatile var loading = false
    }
}
