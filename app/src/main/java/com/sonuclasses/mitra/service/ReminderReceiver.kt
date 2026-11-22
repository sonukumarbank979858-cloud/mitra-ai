package com.sonuclasses.mitra.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sonuclasses.mitra.MitraApp
import com.sonuclasses.mitra.R
import com.sonuclasses.mitra.ui.MainActivity

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "रिमाइंडर"
        val id = intent.getIntExtra("id", 1)
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MitraApp.CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("⏰ मित्र रिमाइंडर")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) {}
    }
}
