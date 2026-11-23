package com.sonuclasses.mitra.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sonuclasses.mitra.tools.Safety

/** Reads the screen, taps by text, scrolls, and presses WhatsApp's Send button after Mitra opens a chat. */
class MitraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (waSendUntil > System.currentTimeMillis() && (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")) {
            handler.removeCallbacks(waTry)
            handler.postDelayed(waTry, 400)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val waTry = Runnable { tryWhatsAppSend() }

    private fun tryWhatsAppSend() {
        if (waSendUntil < System.currentTimeMillis()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
        val node = byId ?: findNode(root) { n ->
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            n.isClickable && (d == "send" || d == "भेजें" || d == "भेजें बटन")
        }
        if (node != null && clickNode(node)) waSendUntil = 0L
    }

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        if (Safety.isBlockedPackage(root.packageName?.toString())) return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || sb.length > 4000) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val s = if (t.isNotEmpty()) t else d
            if (s.isNotEmpty() && n.isVisibleToUser) sb.append(s).append(if (n.isClickable) " [बटन]" else "").append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen < 2000) {
            val n = q.removeFirst(); seen++
            if (pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent; hops++
        }
        return false
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (Safety.isBlockedPackage(root.packageName?.toString())) return false
        val found = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser }
            ?: findNode(root) { it.contentDescription?.toString()?.contains(text, true) == true && it.isVisibleToUser }
            ?: return false
        return clickNode(found)
    }

    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { it.isScrollable && it.isVisibleToUser } ?: return false
        return node.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }


    // ---------- numbered screen snapshot for the in-app agent ----------
    private val snapNodes = ArrayList<AccessibilityNodeInfo>()

    /** Lists visible elements as "[id] kind "label" …" lines; ids are valid until the next snapshot. */
    fun snapshot(): String {
        snapNodes.clear()
        val root = rootInActiveWindow ?: return ""
        if (Safety.isBlockedPackage(root.packageName?.toString())) return ""
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val sb = StringBuilder()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 45 || snapNodes.size >= 140) return
            if (n.isVisibleToUser) {
                val t = n.text?.toString()?.trim()?.replace('\n', ' ').orEmpty()
                val d = n.contentDescription?.toString()?.trim()?.replace('\n', ' ').orEmpty()
                val h = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
                val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
                val interactive = n.isClickable || n.isLongClickable || n.isEditable || n.isCheckable || n.isScrollable
                if (interactive || t.isNotEmpty() || d.isNotEmpty()) {
                    n.getBoundsInScreen(r)
                    if (r.width() > 2 && r.height() > 2 && r.right > 0 && r.bottom > 0 && r.left < sw && r.top < sh) {
                        val kind = when {
                            n.isEditable -> "लिखने-का-बॉक्स"
                            n.isCheckable -> if (n.isChecked) "चेकबॉक्स(✓)" else "चेकबॉक्स( )"
                            n.isScrollable && !n.isClickable -> "स्क्रॉल-एरिया"
                            n.isClickable || n.isLongClickable -> "बटन"
                            else -> "टेक्स्ट"
                        }
                        val label = buildString {
                            if (t.isNotEmpty()) append('"').append(t.take(80)).append('"')
                            if (d.isNotEmpty() && d != t) append(" desc=\"").append(d.take(60)).append('"')
                            if (h.isNotEmpty() && h != t) append(" hint=\"").append(h.take(40)).append('"')
                            if (isEmpty()) append(cls)
                        }
                        snapNodes.add(n)
                        sb.append('[').append(snapNodes.size - 1).append("] ").append(kind).append(' ').append(label)
                            .append(" @").append(r.centerX() * 100 / sw).append(',').append(r.centerY() * 100 / sh)
                            .append(if (n.isFocused) " (फ़ोकस)" else "").append('\n')
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun node(id: Int): AccessibilityNodeInfo? = snapNodes.getOrNull(id)

    fun tapId(id: Int, long: Boolean = false): Boolean {
        val n = node(id) ?: return false
        if (!long && clickNode(n)) return true
        if (long) {
            var p: AccessibilityNodeInfo? = n; var hops = 0
            while (p != null && hops < 6) { if (p.isLongClickable) return p.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK); p = p.parent; hops++ }
        }
        val r = Rect(); n.getBoundsInScreen(r)
        return gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), if (long) 700 else 60)
    }

    private fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // fallback: paste from clipboard
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("mitra", text))
            n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) { false }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.isEditable) return f
        return findNode(root) { it.isEditable && it.isVisibleToUser && it.isFocused }
            ?: findNode(root) { it.isEditable && it.isVisibleToUser }
    }

    /**
     * Types into element [id] (or the focused box). Handles fake search boxes (Chrome, YouTube,
     * Play Store) that only become real text fields after a tap.
     */
    suspend fun typeId(id: Int, text: String): Boolean {
        val n = if (id >= 0) node(id) else null
        if (n == null) {
            val f = focusedEditable() ?: return false
            return setText(f, text)
        }
        val inner = if (n.isEditable) n else findNode(n) { it.isEditable }
        if (inner != null) {
            inner.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!inner.isFocused) inner.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (setText(inner, text)) return true
        }
        // not a real text field yet: tap it, wait for the real box to appear, then type there
        if (!clickNode(n)) {
            val r = Rect(); n.getBoundsInScreen(r)
            gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
        }
        repeat(6) {
            kotlinx.coroutines.delay(400)
            focusedEditable()?.let { if (setText(it, text)) return true }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (Safety.isBlockedPackage(root.packageName?.toString())) return false
        val node = focusedEditable() ?: return false
        return setText(node, text)
    }

    /** Presses the keyboard's Enter/Search/Go key on the focused box. */
    fun pressEnter(): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (Build.VERSION.SDK_INT >= 30) {
            return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return false
    }

    fun tapPercent(xp: Int, yp: Int): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * xp.coerceIn(0, 100) / 100f
        val y = dm.heightPixels * yp.coerceIn(0, 100) / 100f
        return gesture(x, y, x, y, 60)
    }

    fun swipe(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return when (dir) {
            "up" -> gesture(w / 2, h * 0.75f, w / 2, h * 0.3f, 350)     // content moves up = scroll down
            "down" -> gesture(w / 2, h * 0.3f, w / 2, h * 0.75f, 350)
            "left" -> gesture(w * 0.85f, h / 2, w * 0.15f, h / 2, 300)
            "right" -> gesture(w * 0.15f, h / 2, w * 0.85f, h / 2, 300)
            else -> false
        }
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply { moveTo(x1.coerceAtLeast(1f), y1.coerceAtLeast(1f)); lineTo(x2.coerceAtLeast(1f), y2.coerceAtLeast(1f)) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }

    /** Small JPEG of the screen (Android 11+), or null. */
    suspend fun screenshotJpeg(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        return withTimeoutOrNull(4000) {
            suspendCancellableCoroutine<ByteArray?> { cont ->
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(res: ScreenshotResult) {
                            val out = try {
                                val hw = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                res.hardwareBuffer.close()
                                if (bmp == null) null else {
                                    val w = 540; val h = (bmp.height * w.toFloat() / bmp.width).toInt()
                                    val sc = Bitmap.createScaledBitmap(bmp, w, h, true)
                                    val bo = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 55, bo); bo.toByteArray()
                                }
                            } catch (e: Exception) { null }
                            if (cont.isActive) cont.resume(out)
                        }
                        override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    })
                } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }

    companion object {
        @Volatile var instance: MitraAccessibilityService? = null
        @Volatile private var waSendUntil = 0L

        /** Next WhatsApp chat that opens within 15 s gets its Send button pressed once. */
        fun armWhatsAppSend() {
            waSendUntil = System.currentTimeMillis() + 15_000
            instance?.let { s -> s.handler.postDelayed(s.waTry, 1500); s.handler.postDelayed(s.waTry, 3000) }
        }
    }
}
