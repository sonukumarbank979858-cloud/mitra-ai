package com.sonuclasses.mitra.tools

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.sonuclasses.mitra.core.Gemini
import com.sonuclasses.mitra.core.Prefs
import com.sonuclasses.mitra.service.MitraAccessibilityService
import com.sonuclasses.mitra.service.MitraNotificationListener
import com.sonuclasses.mitra.service.ReminderReceiver
import com.sonuclasses.mitra.ui.CameraCaptureActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Executes Gemini function calls on the device. Every result is a small JSON object. */
object ToolExecutor {

    private fun ok(msg: String) = JSONObject().put("status", "ok").put("message", msg)
    private fun err(msg: String) = JSONObject().put("status", "error").put("message", msg)
    private fun needConfirm(msg: String) = JSONObject().put("status", "needs_confirmation")
        .put("message", "अभी कुछ नहीं किया गया। पहले यूज़र से पूछो: $msg — 'हाँ' मिलने पर confirmed=true के साथ दोबारा बुलाओ।")

    private fun has(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun start(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    suspend fun run(ctx: Context, name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "open_app" -> openApp(ctx, args.optString("name"))
            "call_contact" -> call(ctx, args.optString("name"), args.optBoolean("confirmed", false))
            "send_sms" -> sms(ctx, args.optString("name"), args.optString("text"), args.optBoolean("confirmed", false))
            "send_whatsapp" -> whatsapp(ctx, args.optString("name"), args.optString("text"), args.optBoolean("confirmed", false))
            "read_notifications" -> readNotifications(args.optString("app"))
            "reply_notification" -> replyNotification(ctx, args.optInt("index", -1), args.optString("text"), args.optBoolean("confirmed", false))
            "set_alarm" -> setAlarm(ctx, args.optInt("hour"), args.optInt("minute"), args.optString("label"))
            "set_timer" -> setTimer(ctx, args.optInt("seconds"), args.optString("label"))
            "set_reminder" -> setReminder(ctx, args)
            "flashlight" -> flashlight(ctx, args.optBoolean("on", true))
            "set_volume" -> volume(ctx, args.optString("action"), args.optInt("percent", -1))
            "open_settings" -> openSettings(ctx, args.optString("panel"))
            "take_photo" -> takePhoto(ctx)
            "describe_camera" -> describeCamera(ctx, args.optString("question"))
            "play_youtube" -> {
                val q = args.optString("query")
                val opened = youtube(ctx, q)
                if (opened.optString("status") != "ok" || MitraAccessibilityService.instance == null) opened
                else {
                    kotlinx.coroutines.delay(3000)
                    AppAgent.run("YouTube के सर्च नतीजों में '$q' से सबसे ज़्यादा मिलता पहला असली वीडियो (विज्ञापन/Ad या Shorts नहीं, जब तक यूज़र ने Shorts न माँगा हो) टैप करके चलाओ। वीडियो चलने लगे तो done।",
                        true, "") { null }
                }
            }
            "web_search" -> ok(Gemini.searchAnswer(args.optString("query")))
            "screen_action" -> screen(args.optString("action"), args.optString("text"))
            "app_task" -> {
                val app = args.optString("app").trim()
                AppAgent.run(args.optString("goal"), args.optBoolean("confirmed", false), Prefs.facts.joinToString("; ")) {
                    if (app.isEmpty()) null else openApp(ctx, app)
                }
            }
            "remember_fact" -> { Prefs.addFact(args.optString("fact")); ok("याद कर लिया") }
            else -> err("अनजान tool: $name")
        }
    } catch (e: Exception) { err("गड़बड़ी: ${e.message}") }

    // ---------- apps ----------
    private fun openApp(ctx: Context, name: String): JSONObject {
        if (Safety.isBlockedName(name)) return err(Safety.REFUSAL)
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = name.trim().lowercase()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q.replace(" ", "")) }
            ?: return err("'$name' नाम का ऐप नहीं मिला")
        val pkg = match.activityInfo.packageName
        if (Safety.isBlockedPackage(pkg) || Safety.isBlockedName(match.loadLabel(pm).toString())) return err(Safety.REFUSAL)
        val i = pm.getLaunchIntentForPackage(pkg) ?: return err("ऐप खोल नहीं पाए")
        return if (start(ctx, i)) ok("${match.loadLabel(pm)} खोल दिया") else err("ऐप खोल नहीं पाए")
    }

    // ---------- contacts ----------
    data class Contact(val name: String, val number: String)

    fun findContact(ctx: Context, query: String): List<Contact> {
        val digits = query.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 6 && digits.length >= query.replace(" ", "").length - 1) return listOf(Contact(query, digits))
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = LinkedHashMap<String, Contact>()
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${query.trim()}%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (!out.containsKey(n)) out[n] = Contact(n, num)
            }
        }
        val q = query.trim().lowercase()
        return out.values.sortedBy { if (it.name.lowercase() == q) 0 else if (it.name.lowercase().startsWith(q)) 1 else 2 }
    }

    private fun resolve(ctx: Context, name: String): Pair<Contact?, JSONObject?> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS) && name.count { it.isDigit() } < 6)
            return null to err("संपर्क पढ़ने की अनुमति नहीं है — ऐप की 'अनुमतियाँ' स्क्रीन से Contacts की अनुमति दें")
        val list = findContact(ctx, name)
        if (list.isEmpty()) return null to err("'$name' नाम का संपर्क नहीं मिला")
        return list.first() to null
    }

    private fun call(ctx: Context, name: String, confirmed: Boolean): JSONObject {
        val (c, e) = resolve(ctx, name); if (c == null) return e!!
        if (!confirmed) return needConfirm("${c.name} (${c.number}) को कॉल करूँ?")
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        return if (has(ctx, Manifest.permission.CALL_PHONE) && start(ctx, Intent(Intent.ACTION_CALL, uri))) ok("${c.name} को कॉल लगा रहा हूँ")
        else if (start(ctx, Intent(Intent.ACTION_DIAL, uri))) ok("डायलर खोल दिया है, कॉल बटन दबाएँ (कॉल की अनुमति नहीं है)")
        else err("कॉल नहीं लग पाई")
    }

    @Suppress("DEPRECATION")
    private fun sms(ctx: Context, name: String, text: String, confirmed: Boolean): JSONObject {
        val (c, e) = resolve(ctx, name); if (c == null) return e!!
        if (!confirmed) return needConfirm("${c.name} को SMS भेजूँ: \"$text\"?")
        if (!has(ctx, Manifest.permission.SEND_SMS)) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + c.number)).putExtra("sms_body", text)
            return if (start(ctx, i)) ok("मैसेज ऐप खोल दिया है, Send दबाएँ") else err("SMS नहीं भेज पाए")
        }
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = sm.divideMessage(text)
        sm.sendMultipartTextMessage(c.number, null, parts, null, null)
        return ok("${c.name} को SMS भेज दिया")
    }

    private fun waNumber(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("0")) d = d.trimStart('0')
        if (d.length == 10) d = "91$d"
        return d
    }

    private fun whatsapp(ctx: Context, name: String, text: String, confirmed: Boolean): JSONObject {
        val (c, e) = resolve(ctx, name); if (c == null) return e!!
        if (!confirmed) return needConfirm("${c.name} को WhatsApp पर भेजूँ: \"$text\"?")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${waNumber(c.number)}&text=${Uri.encode(text)}")
        val a11y = MitraAccessibilityService.instance
        if (a11y != null) MitraAccessibilityService.armWhatsAppSend()
        val opened = start(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")) ||
            start(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b")) ||
            start(ctx, Intent(Intent.ACTION_VIEW, uri))
        if (!opened) return err("WhatsApp नहीं खुल पाया")
        return if (a11y != null) ok("${c.name} की WhatsApp चैट खोलकर मैसेज भेज रहा हूँ")
        else ok("${c.name} की WhatsApp चैट में मैसेज लिख दिया है, Send दबाएँ (अपने-आप भेजने के लिए Accessibility चालू करें)")
    }

    // ---------- notifications ----------
    private fun readNotifications(app: String): JSONObject {
        if (!MitraNotificationListener.connected) return err("नोटिफ़िकेशन पढ़ने की अनुमति नहीं है — 'अनुमतियाँ' स्क्रीन से Notification access चालू करें")
        val items = MitraNotificationListener.snapshot()
            .filter { app.isBlank() || it.appName.contains(app, true) || it.pkg.contains(app, true) }
            .filterNot { Safety.isBlockedPackage(it.pkg) }
        if (items.isEmpty()) return ok("कोई नई नोटिफ़िकेशन नहीं है")
        val arr = JSONArray()
        items.take(10).forEach { n ->
            arr.put(JSONObject().put("index", n.id).put("app", n.appName).put("from", n.title).put("text", n.text.take(300)).put("can_reply", n.reply != null))
        }
        return JSONObject().put("status", "ok").put("notifications", arr)
    }

    private fun replyNotification(ctx: Context, index: Int, text: String, confirmed: Boolean): JSONObject {
        val n = MitraNotificationListener.snapshot().firstOrNull { it.id == index } ?: return err("यह नोटिफ़िकेशन नहीं मिली, पहले read_notifications चलाओ")
        if (n.reply == null) return err("इस नोटिफ़िकेशन का सीधा जवाब नहीं दिया जा सकता")
        if (Safety.isBlockedPackage(n.pkg)) return err(Safety.REFUSAL)
        if (!confirmed) return needConfirm("${n.title} (${n.appName}) को जवाब भेजूँ: \"$text\"?")
        return if (MitraNotificationListener.reply(ctx, n, text)) ok("${n.title} को जवाब भेज दिया") else err("जवाब नहीं भेज पाए")
    }

    // ---------- alarms ----------
    private fun setAlarm(ctx: Context, h: Int, m: Int, label: String): JSONObject {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return if (start(ctx, i)) ok(String.format("%02d:%02d का अलार्म लगा दिया", h, m)) else err("घड़ी ऐप नहीं मिला")
    }

    private fun setTimer(ctx: Context, sec: Int, label: String): JSONObject {
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return if (start(ctx, i)) ok("$sec सेकंड का टाइमर शुरू") else err("टाइमर नहीं लग पाया")
    }

    private fun setReminder(ctx: Context, a: JSONObject): JSONObject {
        val text = a.optString("text").ifBlank { "रिमाइंडर" }
        val cal = Calendar.getInstance()
        if (a.has("minutes_from_now")) cal.add(Calendar.MINUTE, a.optInt("minutes_from_now", 0).coerceAtLeast(1))
        else if (a.has("hour")) {
            cal.set(Calendar.HOUR_OF_DAY, a.optInt("hour")); cal.set(Calendar.MINUTE, a.optInt("minute", 0)); cal.set(Calendar.SECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        } else return err("समय बताओ: कितने मिनट बाद या किस समय")
        val am = ctx.getSystemService(AlarmManager::class.java)
        val reqCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pi = PendingIntent.getBroadcast(ctx, reqCode,
            Intent(ctx, ReminderReceiver::class.java).putExtra("text", text).putExtra("id", reqCode),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        val t = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return ok("$t पर याद दिला दूँगा: $text")
    }

    // ---------- device ----------
    private fun flashlight(ctx: Context, on: Boolean): JSONObject {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return err("इस फ़ोन में टॉर्च नहीं मिली")
        cm.setTorchMode(id, on)
        return ok(if (on) "टॉर्च चालू" else "टॉर्च बंद")
    }

    private fun volume(ctx: Context, action: String, percent: Int): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val f = AudioManager.FLAG_SHOW_UI
        when (action.lowercase()) {
            "up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f) }
            "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f) }
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, f)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, f)
            "set" -> { val max = am.getStreamMaxVolume(s); am.setStreamVolume(s, (max * percent.coerceIn(0, 100) / 100f).toInt(), f) }
            else -> return err("action: up/down/mute/unmute/set")
        }
        val pct = am.getStreamVolume(s) * 100 / am.getStreamMaxVolume(s).coerceAtLeast(1)
        return ok("आवाज़ अब $pct% है")
    }

    private fun openSettings(ctx: Context, panel: String): JSONObject {
        val p = panel.lowercase()
        val action = when {
            p.contains("wifi") || p.contains("वाई") -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            p.contains("blue") || p.contains("ब्लू") -> Settings.ACTION_BLUETOOTH_SETTINGS
            p.contains("internet") || p.contains("data") -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            p.contains("vol") -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            p.contains("nfc") -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
            p.contains("display") || p.contains("bright") -> Settings.ACTION_DISPLAY_SETTINGS
            p.contains("battery") -> Intent.ACTION_POWER_USAGE_SUMMARY
            p.contains("location") || p.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (start(ctx, Intent(action)) || start(ctx, Intent(Settings.ACTION_SETTINGS))) ok("सेटिंग्स खोल दी") else err("सेटिंग्स नहीं खुलीं")
    }

    private fun takePhoto(ctx: Context): JSONObject =
        if (start(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) || start(ctx, Intent(MediaStore.ACTION_IMAGE_CAPTURE))) ok("कैमरा खोल दिया")
        else err("कैमरा ऐप नहीं मिला")

    private suspend fun describeCamera(ctx: Context, q: String): JSONObject {
        if (!has(ctx, Manifest.permission.CAMERA)) return err("कैमरे की अनुमति नहीं है — 'अनुमतियाँ' स्क्रीन से दें")
        val jpeg = CameraCaptureActivity.capture(ctx) ?: return err("तस्वीर नहीं ले पाया")
        return ok(Gemini.describeImage(jpeg, q))
    }

    private fun youtube(ctx: Context, query: String): JSONObject {
        val search = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query)
        if (start(ctx, search)) return ok("YouTube पर '$query' खोल दिया")
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        return if (start(ctx, web)) ok("YouTube पर '$query' खोल दिया") else err("YouTube नहीं खुला")
    }

    // ---------- screen ----------
    private suspend fun screen(action: String, text: String): JSONObject = withContext(Dispatchers.Main) {
        val svc = MitraAccessibilityService.instance
            ?: return@withContext err("Accessibility चालू नहीं है — 'अनुमतियाँ' स्क्रीन से मित्र AI की Accessibility चालू करें")
        if (Safety.isBlockedPackage(svc.currentPackage())) return@withContext err(Safety.REFUSAL)
        when (action.lowercase()) {
            "read" -> {
                val t = svc.readScreen()
                if (t.isBlank()) err("स्क्रीन पर पढ़ने लायक टेक्स्ट नहीं मिला") else ok(t.take(3000))
            }
            "click" -> if (svc.clickByText(text)) ok("'$text' पर टैप किया") else err("'$text' वाला बटन नहीं मिला")
            "scroll_down" -> if (svc.scroll(true)) ok("नीचे स्क्रॉल किया") else err("स्क्रॉल नहीं हो पाया")
            "scroll_up" -> if (svc.scroll(false)) ok("ऊपर स्क्रॉल किया") else err("स्क्रॉल नहीं हो पाया")
            "back" -> { svc.back(); ok("पीछे गए") }
            "home" -> { svc.home(); ok("होम स्क्रीन") }
            "type" -> if (svc.typeText(text)) ok("लिख दिया") else err("लिखने का बॉक्स नहीं मिला")
            else -> err("action: read/click/scroll_down/scroll_up/back/home/type")
        }
    }
}
