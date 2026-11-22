package com.sonuclasses.mitra.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MitraNotificationListener : NotificationListenerService() {

    data class Item(
        val id: Int, val key: String, val pkg: String, val appName: String,
        val title: String, val text: String, val reply: Notification.Action?, val time: Long
    )

    override fun onListenerConnected() { connected = true; try { activeNotifications?.forEach { add(it) } } catch (_: Exception) {} }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { sbn?.let { add(it) } }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val k = sbn?.key ?: return
        synchronized(items) { items.removeAll { it.key == k } }
    }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (text.isBlank() && title.isBlank()) return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        synchronized(items) {
            items.removeAll { it.key == sbn.key }
            items.add(0, Item(nextId++, sbn.key, sbn.packageName, app, title, text, reply, sbn.postTime))
            while (items.size > 30) items.removeAt(items.size - 1)
        }
    }

    companion object {
        @Volatile var connected = false
        private val items = ArrayList<Item>()
        private var nextId = 1

        fun snapshot(): List<Item> = synchronized(items) { items.toList() }

        fun reply(ctx: Context, item: Item, text: String): Boolean {
            val action = item.reply ?: return false
            val inputs = action.remoteInputs ?: return false
            return try {
                val intent = Intent()
                val bundle = Bundle()
                inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(inputs, intent, bundle)
                action.actionIntent.send(ctx, 0, intent)
                true
            } catch (e: PendingIntent.CanceledException) { false } catch (e: Exception) { false }
        }
    }
}
