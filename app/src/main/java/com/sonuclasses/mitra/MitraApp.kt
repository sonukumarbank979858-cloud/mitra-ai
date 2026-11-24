package com.sonuclasses.mitra

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.sonuclasses.mitra.core.PasswordVault
import com.sonuclasses.mitra.core.Prefs

class MitraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        PasswordVault.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SESSION, "मित्र सत्र", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "रिमाइंडर", NotificationManager.IMPORTANCE_HIGH))
    }

    companion object {
        const val CH_SESSION = "session"
        const val CH_REMINDER = "reminders"
        lateinit var instance: MitraApp
            private set
    }
}
