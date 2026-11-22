package com.sonuclasses.mitra.ui

import android.Manifest
import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sonuclasses.mitra.BuildConfig
import com.sonuclasses.mitra.core.Assistant
import com.sonuclasses.mitra.core.Gemini
import com.sonuclasses.mitra.core.LiveSession
import com.sonuclasses.mitra.core.Prefs
import com.sonuclasses.mitra.service.MitraAccessibilityService
import kotlinx.coroutines.launch

private fun open(ctx: Context, i: Intent): Boolean = try {
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (e: Exception) { false }

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = MC.Violet, unfocusedBorderColor = Color(0xFF2A2A40))

// ------------------------------------------------------------ Onboarding (key)
@Composable
fun KeyEditor(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        GradientButton("🔑 मुफ़्त key पाएं", Modifier.fillMaxWidth()) {
            open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim(); error = null },
            label = { Text("Gemini API key यहाँ पेस्ट करें") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), colors = fieldColors()
        )
        if (error != null) Text(error!!, color = MC.Pink, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MC.Cyan)
            Text("  key जाँच रहा हूँ…", color = MC.Sub)
        } else GradientButton("जाँचें और सेव करें", Modifier.fillMaxWidth(), enabled = key.length > 10) {
            busy = true
            scope.launch {
                val e = Gemini.validateKey(key)
                busy = false
                if (e == null) {
                    Prefs.userKey = key
                    Toast.makeText(ctx, "Key सेव हो गई ✅", Toast.LENGTH_SHORT).show()
                    onSaved()
                } else error = e
            }
        }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF15102E), MC.Bg)))
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Orb(com.sonuclasses.mitra.core.VState.IDLE, 0f, Modifier.size(180.dp))
        }
        Text("मित्र AI में आपका स्वागत है", color = MC.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("मित्र Google Gemini की मदद से आपकी आवाज़ समझता और जवाब देता है। इसके लिए एक मुफ़्त Gemini API key चाहिए:\n\n" +
            "1. नीचे \"मुफ़्त key पाएं\" दबाएँ और Google खाते से साइन इन करें।\n" +
            "2. \"Create API key\" दबाकर key कॉपी करें।\n3. यहाँ पेस्ट करके सेव करें।\n\n" +
            "आपकी key सिर्फ़ इसी फ़ोन में सुरक्षित (एन्क्रिप्टेड) रहती है।", color = MC.Sub, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        KeyEditor(onSaved = onDone)
    }
}

// ------------------------------------------------------------ Permissions
private data class PermRow(val icon: String, val title: String, val why: String, val granted: Boolean, val onGrant: () -> Unit)

private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

fun isAccessibilityOn(ctx: Context): Boolean {
    if (MitraAccessibilityService.instance != null) return true
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(ctx, MitraAccessibilityService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(me, true) }
}

fun isAssistantRole(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 29) {
        val rm = ctx.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }
    val cur = Settings.Secure.getString(ctx.contentResolver, "assistant") ?: return false
    return cur.startsWith(ctx.packageName)
}

@Composable
fun PermissionsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val tick by MainActivity.resumeTick
    var localTick by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        localTick++
        if (!ok) {
            Toast.makeText(ctx, "अनुमति नहीं मिली — ऐप सेटिंग्स में जाकर दें", Toast.LENGTH_LONG).show()
            open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
    }
    @Suppress("UNUSED_VARIABLE") val refresh = tick + localTick
    val rows = buildList {
        add(PermRow("🎤", "माइक्रोफ़ोन", "आपकी आवाज़ सुनने के लिए — सबसे ज़रूरी।", has(ctx, Manifest.permission.RECORD_AUDIO)) { launcher.launch(Manifest.permission.RECORD_AUDIO) })
        add(PermRow("👥", "संपर्क (Contacts)", "नाम बोलकर कॉल, SMS या WhatsApp करने के लिए।", has(ctx, Manifest.permission.READ_CONTACTS)) { launcher.launch(Manifest.permission.READ_CONTACTS) })
        add(PermRow("📞", "फ़ोन कॉल", "आपके कहने पर सीधे कॉल लगाने के लिए।", has(ctx, Manifest.permission.CALL_PHONE)) { launcher.launch(Manifest.permission.CALL_PHONE) })
        add(PermRow("✉️", "SMS", "आपकी पुष्टि के बाद SMS भेजने के लिए।", has(ctx, Manifest.permission.SEND_SMS)) { launcher.launch(Manifest.permission.SEND_SMS) })
        add(PermRow("📷", "कैमरा", "\"सामने क्या दिख रहा है\" बताने के लिए।", has(ctx, Manifest.permission.CAMERA)) { launcher.launch(Manifest.permission.CAMERA) })
        if (Build.VERSION.SDK_INT >= 33)
            add(PermRow("🔔", "नोटिफ़िकेशन दिखाना", "रिमाइंडर और चालू सत्र की सूचना दिखाने के लिए।", has(ctx, Manifest.permission.POST_NOTIFICATIONS)) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) })
        add(PermRow("📬", "नोटिफ़िकेशन पढ़ना", "WhatsApp/मैसेज की सूचनाएँ पढ़कर सुनाने और उनका जवाब देने के लिए।",
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) {
            open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        add(PermRow("♿", "Accessibility (स्क्रीन कंट्रोल)", "स्क्रीन पढ़ने, बटन दबाने और WhatsApp पर Send दबाने के लिए। सेटिंग्स में \"मित्र AI\" चुनकर चालू करें। बैंकिंग/UPI ऐप्स में यह कभी काम नहीं करता।",
            isAccessibilityOn(ctx)) { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        add(PermRow("🫧", "दूसरे ऐप्स के ऊपर दिखाना", "फ़्लोटिंग बबल और सुनते समय चमकती किनारी के लिए।", Settings.canDrawOverlays(ctx)) {
            open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
        })
        if (Build.VERSION.SDK_INT >= 31) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            add(PermRow("⏰", "सटीक रिमाइंडर", "रिमाइंडर ठीक समय पर बजाने के लिए।", am.canScheduleExactAlarms()) {
                open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
            })
        }
        add(PermRow("🏠", "डिफ़ॉल्ट असिस्टेंट", "होम बटन देर तक दबाने पर मित्र खुले, इसके लिए \"Digital assistant app\" में मित्र AI चुनें।", isAssistantRole(ctx)) {
            open(ctx, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) ||
                open(ctx, Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")) ||
                open(ctx, Intent(Settings.ACTION_SETTINGS))
        })
    }
    val done = rows.count { it.granted }
    Column(Modifier.fillMaxSize().background(MC.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar("अनुमतियाँ", back = back)
        Text("$done / ${rows.size} चालू। जो काम आप चाहते हैं, उसी की अनुमति दें।", color = MC.Sub, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(rows) { _, r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).clip(RoundedCornerShape(18.dp))
                    .background(MC.Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.icon, fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = MC.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(r.why, color = MC.Sub, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (r.granted) Box(Modifier.size(34.dp).clip(CircleShape).background(MC.Green), contentAlignment = Alignment.Center) {
                        Text("✓", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    } else Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MC.grad).clickable { r.onGrant() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)) { Text("दें", color = Color.White, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

// ------------------------------------------------------------ Settings
@Composable
fun SettingsScreen(go: (Screen) -> Unit, ensureService: () -> Unit) {
    val ctx = LocalContext.current
    val ver by Prefs.version.collectAsState()
    var name by remember(ver) { mutableStateOf(Prefs.personaName) }
    var voiceMenu by remember { mutableStateOf(false) }
    var showKeyEditor by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val v = ver

    Column(Modifier.fillMaxSize().background(MC.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar("सेटिंग्स", back = { go(Screen.HOME) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionCard {
                Text("🔑 Gemini API key", color = MC.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                val status = when {
                    Prefs.userKey.isNotEmpty() -> "आपकी अपनी key इस्तेमाल हो रही है (…${Prefs.userKey.takeLast(4)})"
                    Prefs.hasBuiltInKey -> "ऐप में पहले से लगी key इस्तेमाल हो रही है। चाहें तो अपनी key डाल सकते हैं।"
                    else -> "कोई key नहीं है — वॉइस और चैट के लिए key ज़रूरी है।"
                }
                Text(status, color = MC.Sub, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                if (showKeyEditor) KeyEditor(onSaved = { showKeyEditor = false })
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton(if (Prefs.userKey.isEmpty()) "अपनी key डालें" else "key बदलें") { showKeyEditor = true }
                    if (Prefs.userKey.isNotEmpty() && Prefs.hasBuiltInKey) GradientButton("अपनी key हटाएँ") { Prefs.userKey = "" }
                }
            }
            SectionCard {
                Text("🧑 पर्सोना", color = MC.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("असिस्टेंट का नाम") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                Spacer(Modifier.height(6.dp))
                GradientButton("नाम सेव करें") { Prefs.personaName = name.trim(); Toast.makeText(ctx, "सेव हो गया", Toast.LENGTH_SHORT).show() }
                Spacer(Modifier.height(14.dp))
                Text("अंदाज़", color = MC.Sub, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Prefs.STYLES.forEach { (id, label) ->
                        val sel = Prefs.style == id
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) MC.grad else Brush.linearGradient(listOf(MC.Surface, MC.Surface)))
                            .clickable { Prefs.style = id }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Text(label, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("आवाज़ (Live वॉइस)", color = MC.Sub, fontSize = 14.sp)
                Box(Modifier.padding(top = 6.dp)) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(MC.Surface).clickable { voiceMenu = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp)) { Text("🔊 ${Prefs.voice}  ▾", color = MC.Text) }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        Prefs.VOICES.forEach { vn ->
                            DropdownMenuItem(text = { Text(vn) }, onClick = { Prefs.voice = vn; voiceMenu = false })
                        }
                    }
                }
                Text("नई आवाज़ अगली बातचीत से लागू होगी।", color = MC.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            SectionCard {
                ToggleRow("⚡ Live वॉइस (Gemini Live)", "तेज़, प्राकृतिक बातचीत और बीच में टोकने की सुविधा। बंद करने पर सामान्य वॉइस मोड चलेगा।", Prefs.useLive) { Prefs.useLive = it }
                Spacer(Modifier.height(10.dp))
                ToggleRow("🫧 फ़्लोटिंग बबल", "हर ऐप के ऊपर छोटा बबल; दबाकर बोलें। सुनते समय स्क्रीन की किनारी चमकेगी।", Prefs.overlayEnabled) { on ->
                    Prefs.overlayEnabled = on
                    if (on && !Settings.canDrawOverlays(ctx)) {
                        open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                    }
                    ensureService()
                }
            }
            SectionCard {
                NavRow("🧠 स्मृति (याद रखी बातें)") { go(Screen.MEMORY) }
                NavRow("🛡 अनुमतियाँ") { go(Screen.PERMS) }
                NavRow("🗑 बातचीत साफ़ करें") { Assistant.clearChat(); Toast.makeText(ctx, "साफ़ हो गया", Toast.LENGTH_SHORT).show() }
            }
            SectionCard {
                Text("ℹ️ ऐप के बारे में", color = MC.Text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("मित्र AI v${BuildConfig.VERSION_NAME}\nLive मॉडल: ${Prefs.lastLiveModel.ifEmpty { LiveSession.MODELS.first() + " (पहली कोशिश)" }}\n" +
                    "चैट मॉडल: ${Gemini.TEXT_MODELS.first()}\n\nमित्र आपकी पुष्टि के बिना कॉल या मैसेज नहीं भेजता, और पेमेंट/बैंकिंग ऐप्स को कभी नहीं छूता। " +
                    "आपकी बातें Google Gemini API पर प्रोसेस होती हैं।", color = MC.Sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MC.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(sub, color = MC.Sub, fontSize = 13.sp)
        }
        Switch(checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MC.Violet, checkedThumbColor = Color.White))
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MC.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("›", color = MC.Sub, fontSize = 22.sp)
    }
}

// ------------------------------------------------------------ Memory
@Composable
fun MemoryScreen(back: () -> Unit) {
    val ver by Prefs.version.collectAsState()
    val facts = remember(ver) { Prefs.facts }
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(MC.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar("स्मृति", back = back)
        Text("ये बातें मित्र को हर बातचीत में याद रहती हैं। बातचीत में \"याद रखना कि…\" कहकर भी जोड़ सकते हैं।",
            color = MC.Sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("नई बात जोड़ें…") },
                singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors())
            Spacer(Modifier.width(8.dp))
            GradientButton("जोड़ें") { Prefs.addFact(input); input = "" }
        }
        if (facts.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("अभी कुछ याद नहीं रखा है।", color = MC.Sub)
        } else LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(facts) { i, f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                    .background(MC.Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f, color = MC.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("🗑", fontSize = 18.sp, modifier = Modifier.clickable { Prefs.removeFact(i) }.padding(6.dp))
                }
            }
        }
    }
}
