package com.sonuclasses.mitra.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sonuclasses.mitra.core.Assistant
import com.sonuclasses.mitra.core.Prefs
import com.sonuclasses.mitra.service.AssistantService

enum class Screen { ONBOARD, HOME, CHAT, PERMS, SETTINGS, MEMORY }

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTO_LISTEN = "auto_listen"
        /** Incremented on every resume so permission ticks refresh after returning from Settings. */
        val resumeTick = mutableIntStateOf(0)
    }

    private var screen by mutableStateOf(Screen.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = if (Prefs.hasKey) Screen.HOME else Screen.ONBOARD
        setContent {
            MitraTheme { Root() }
        }
        handleIntent(intent)
        if (Prefs.overlayEnabled && Settings.canDrawOverlays(this)) ensureService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue = resumeTick.intValue + 1
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTO_LISTEN, false) == true) {
            i.removeExtra(EXTRA_AUTO_LISTEN)
            if (Prefs.hasKey) { screen = Screen.HOME; startListening() }
        }
    }

    fun ensureService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try { ContextCompat.startForegroundService(this, Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_SYNC)) } catch (_: Exception) {}
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        if (!Assistant.active) Assistant.startVoice(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) Assistant.startVoice(this)
        resumeTick.intValue = resumeTick.intValue + 1
    }

    @Composable
    private fun Root() {
        val go: (Screen) -> Unit = { screen = it }
        BackHandler(enabled = screen != Screen.HOME && screen != Screen.ONBOARD) {
            screen = if (screen == Screen.MEMORY) Screen.SETTINGS else Screen.HOME
        }
        when (screen) {
            Screen.ONBOARD -> OnboardingScreen(onDone = { screen = Screen.PERMS })
            Screen.HOME -> HomeScreen(go = go, onMic = {
                if (Assistant.active) Assistant.stopVoice(this) else startListening()
            })
            Screen.CHAT -> ChatScreen(back = { screen = Screen.HOME })
            Screen.PERMS -> PermissionsScreen(back = { screen = Screen.HOME })
            Screen.SETTINGS -> SettingsScreen(go = go, ensureService = { ensureService() })
            Screen.MEMORY -> MemoryScreen(back = { screen = Screen.SETTINGS })
        }
    }
}
