package com.sonuclasses.mitra.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.sonuclasses.mitra.core.Gemini
import com.sonuclasses.mitra.core.PasswordVault
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
        dismissSecureForm()
        stopSmsConsent()
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

    // ================================================================ सुरक्षित फ़ॉर्म
    // Password / OTP / captcha typed here go straight into the page's input box through the
    // native value setter. They are never sent to Gemini, never logged, never put in history or Prefs.
    // (Only a password the user ticks "याद रखें" for is kept, in the Keystore-encrypted PasswordVault.)

    private var secureDialog: AlertDialog? = null
    private var secureDone: ((String) -> Unit)? = null
    private var smsReceiver: BroadcastReceiver? = null
    private var smsTarget: EditText? = null

    private val smsConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            val msg = r.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE) ?: ""
            val otp = Regex("(?<!\\d)\\d{4,8}(?!\\d)").find(msg)?.value
            if (otp != null) smsTarget?.setText(otp)
            else Toast.makeText(this, "SMS में OTP नहीं मिला, खुद लिख दें", Toast.LENGTH_SHORT).show()
        }
        smsTarget = null
    }

    /** Listen for ONE incoming SMS via Google's SMS User Consent API (no SMS permission; user approves it). */
    private fun startSmsConsent(target: EditText) {
        stopSmsConsent()
        smsTarget = target
        try {
            SmsRetriever.getClient(this).startSmsUserConsent(null)
            val rcv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                    val ex = i.extras ?: return
                    @Suppress("DEPRECATION") val st = ex.get(SmsRetriever.EXTRA_STATUS) as? Status
                    if (st?.statusCode == CommonStatusCodes.SUCCESS) {
                        @Suppress("DEPRECATION") val consent = ex.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                        val pkg = consent?.resolveActivity(packageManager)?.packageName
                        if (consent != null && pkg == "com.google.android.gms") {
                            try { smsConsent.launch(consent) } catch (_: Exception) {}
                        }
                    }
                    stopSmsConsent(keepTarget = true)
                }
            }
            ContextCompat.registerReceiver(this, rcv, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
            smsReceiver = rcv
            Toast.makeText(this, "OTP वाला SMS आने पर 'अनुमति दें' दबाएँ", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            smsTarget = null
            Toast.makeText(this, "इस फ़ोन पर SMS से OTP पढ़ना नहीं चल रहा, खुद लिख दें", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSmsConsent(keepTarget: Boolean = false) {
        smsReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        smsReceiver = null
        if (!keepTarget) smsTarget = null
    }

    fun dismissSecureForm() {
        val cb = secureDone; secureDone = null
        try { secureDialog?.dismiss() } catch (_: Exception) {}
        secureDialog = null
        cb?.invoke("cancelled")
    }

    /** JS that fills the box [id] with [value] (also handles OTP split into one-digit boxes). Value is never returned. */
    private fun fillJs(id: Int, kind: String, value: String): String {
        val v = JSONObject.quote(value); val k = JSONObject.quote(kind)
        return """(function(){try{
 var V=$v, K=$k; var e=document.querySelector('[data-mitra="$id"]'); if(!e) return 'missing';
 function setv(x,val){ try{x.focus();}catch(z){}
   if(x.isContentEditable){x.innerText=val;}
   else{var pr=x.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype; Object.getOwnPropertyDescriptor(pr,'value').set.call(x,val);}
   x.setAttribute('data-mitra-secure','1'); if(K!='password'){try{x.style.webkitTextSecurity='disc';}catch(z){}}
   x.dispatchEvent(new Event('input',{bubbles:true})); x.dispatchEvent(new Event('change',{bubbles:true})); }
 e.scrollIntoView({block:'center'});
 if(K=='otp' && e.maxLength==1 && V.length>1){
   var box=e.parentElement, boxes=[];
   for(var up=0; up<4 && box; up++){ boxes=[].slice.call(box.querySelectorAll('input')).filter(function(x){return x.maxLength==1;}); if(boxes.length>=V.length) break; box=box.parentElement; }
   if(boxes.length>=V.length){ for(var i=0;i<V.length;i++) setv(boxes[i],V.charAt(i)); return 'ok'; }
 }
 setv(e,V); return 'ok';
}catch(err){return 'error';}})()"""
    }

    fun fillSecure(id: Int, kind: String, value: String, cb: (Boolean) -> Unit) {
        try { web.evaluateJavascript(fillJs(id, kind, value)) { r -> cb(r?.contains("ok") == true) } }
        catch (e: Exception) { cb(false) }
    }

    /** Crop a page area (viewport px already scaled to view px) out of the WebView as a bitmap. */
    fun cropView(x: Int, y: Int, w: Int, h: Int): Bitmap? = try {
        val cx = x.coerceIn(0, maxOf(0, web.width - 1)); val cy = y.coerceIn(0, maxOf(0, web.height - 1))
        val cw = w.coerceAtMost(web.width - cx); val ch = h.coerceAtMost(web.height - cy)
        if (cw < 8 || ch < 8) null else {
            val bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.translate(-(web.scrollX + cx).toFloat(), -(web.scrollY + cy).toFloat()); web.draw(c); bmp
        }
    } catch (e: Throwable) { null }

    /**
     * Shows the secure form. [done] gets: "filled", "filled_saved", "missing", or "cancelled".
     * kind = password | otp | captcha.
     */
    fun showSecureForm(id: Int, kind: String, label: String, host: String, captcha: Bitmap?, done: (String) -> Unit) {
        dismissSecureForm()
        secureDone = done
        val pad = dp(18)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        val what = when (kind) { "otp" -> "OTP"; "captcha" -> "कैप्चा"; else -> "पासवर्ड" }
        box.addView(TextView(this).apply {
            text = (if (label.isNotBlank()) "$label\n" else "") + (if (host.isNotBlank()) "साइट: $host\n" else "") +
                "यह $what सिर्फ़ इसी फ़ोन पर पेज के बॉक्स में भरा जाएगा — मित्र AI / Gemini को नहीं भेजा जाता।"
            textSize = 14f; setTextColor(Color.parseColor("#333333"))
        })
        if (captcha != null) box.addView(ImageView(this).apply {
            setImageBitmap(captcha); adjustViewBounds = true; setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val input = EditText(this).apply {
            isSingleLine = true; textSize = 20f
            hint = when (kind) { "otp" -> "OTP लिखें"; "captcha" -> "तस्वीर में लिखे अक्षर"; else -> "पासवर्ड लिखें" }
            inputType = when (kind) {
                "otp" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                "captcha" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            if (kind != "captcha") transformationMethod = PasswordTransformationMethod.getInstance()
        }
        box.addView(input, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        if (kind != "captcha") box.addView(CheckBox(this).apply {
            text = "दिखाएँ"
            setOnCheckedChangeListener { _, on ->
                input.transformationMethod = if (on) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                input.setSelection(input.text.length)
            }
        })
        val remember = if (kind == "password" && host.isNotBlank() && PasswordVault.available) CheckBox(this).apply {
            text = "इस साइट के लिए याद रखें"; isChecked = PasswordVault.get(host) != null
        }.also { box.addView(it) } else null
        if (kind == "otp") box.addView(Button(this).apply {
            text = "📩 SMS से OTP अपने-आप पढ़ें"; isAllCaps = false
            setOnClickListener { startSmsConsent(input) }
        })
        if (kind == "captcha" && captcha != null) box.addView(Button(this).apply {
            text = "🤖 मित्र पढ़े (सिर्फ़ यह तस्वीर Gemini को जाएगी)"; isAllCaps = false
            setOnClickListener {
                isEnabled = false; text = "पढ़ रहा हूँ…"
                lifecycleScope.launch {
                    val res = readCaptcha(captcha)
                    isEnabled = true; text = "🤖 मित्र पढ़े (सिर्फ़ यह तस्वीर Gemini को जाएगी)"
                    if (res.isNullOrBlank()) Toast.makeText(this@BrowserActivity, "पढ़ नहीं पाया, खुद लिख दें", Toast.LENGTH_SHORT).show()
                    else { input.setText(res); input.setSelection(input.text.length) }
                }
            }
        })
        val dlg = AlertDialog.Builder(this)
            .setTitle("🔒 सुरक्षित फ़ॉर्म")
            .setView(box)
            .setPositiveButton("भरें", null)
            .setNegativeButton("रद्द") { _, _ -> dismissSecureForm() }
            .setOnCancelListener { dismissSecureForm() }
            .create()
        dlg.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.setOnShowListener {
            input.requestFocus()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.isEmpty()) { input.error = "पहले लिखें"; return@setOnClickListener }
                val save = remember?.isChecked == true
                input.setText("")
                fillSecure(id, kind, value) { ok ->
                    var code = if (ok) "filled" else "missing"
                    if (ok && save && PasswordVault.save(host, value)) code = "filled_saved"
                    if (ok && remember != null && !save) PasswordVault.remove(host)
                    stopSmsConsent()
                    val cb = secureDone; secureDone = null
                    try { dlg.dismiss() } catch (_: Exception) {}
                    secureDialog = null
                    cb?.invoke(code)
                }
            }
        }
        secureDialog = dlg
        try { dlg.show() } catch (e: Exception) { dismissSecureForm() }
    }

    /** Only on the user's tap: send just the small captcha crop to Gemini vision. */
    private suspend fun readCaptcha(bmp: Bitmap): String? = try {
        val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/png").put("data", Base64.encodeToString(jpg, Base64.NO_WRAP))))
            .put(JSONObject().put("text", "This is a website captcha image. Reply with ONLY the exact characters shown (keep upper/lower case), no spaces, no other words. If it is a math question, reply with only the answer number."))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0))
        Gemini.textOf(Gemini.generate(body)).trim().replace(Regex("\\s+"), "").take(20)
    } catch (e: Exception) { null }

    companion object {
        const val EXTRA_URL = "url"
        @Volatile var current: BrowserActivity? = null
        @Volatile var loading = false
    }
}
