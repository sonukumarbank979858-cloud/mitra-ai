package com.sonuclasses.mitra.service

import android.accessibilityservice.AccessibilityService
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

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (Safety.isBlockedPackage(root.packageName?.toString())) return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findNode(root) { it.isEditable && it.isVisibleToUser } ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
