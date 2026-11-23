package com.sonuclasses.mitra.tools

import android.util.Base64
import com.sonuclasses.mitra.core.Gemini
import com.sonuclasses.mitra.service.MitraAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Does a multi-step job inside any app (search & play a YouTube video, type and send a WhatsApp
 * message, fill a form…) by looking at the screen and tapping / typing step by step.
 */
object AppAgent {

    private const val MAX_STEPS = 28

    private val RULES = """
तुम एक Android फ़ोन चलाने वाले सहायक हो। तुम्हें एक काम (goal) दिया गया है जो अभी खुले ऐप के अंदर पूरा करना है।
हर कदम पर तुम्हें स्क्रीन के तत्वों की सूची मिलेगी: [id] प्रकार "टेक्स्ट" desc=.. hint=.. @x,y (x,y स्क्रीन पर प्रतिशत में), और कभी-कभी स्क्रीन की तस्वीर भी।
सिर्फ़ एक JSON ऑब्जेक्ट लौटाओ (कोई और टेक्स्ट नहीं):
{"thought":"छोटा कारण","action":"<action>","id":<number या -1>,"text":"<टेक्स्ट>","x":<0-100>,"y":<0-100>,"say":"यूज़र को बताने/पूछने वाली बात"}
action इनमें से एक:
- tap: id वाले तत्व पर टैप
- long_press: id पर देर तक दबाना
- tap_xy: x,y (प्रतिशत) पर टैप — जब सूची में तत्व न हो पर तस्वीर में दिखे
- type: id वाले लिखने-के-बॉक्स में text लिखो (पुराना टेक्स्ट बदल जाएगा)। id=-1 हो तो फ़ोकस वाले बॉक्स में।
- enter: कीबोर्ड का Enter/Search दबाओ (सर्च बॉक्स में लिखने के बाद)
- scroll_down / scroll_up / swipe_left / swipe_right
- back: पीछे जाओ
- wait: स्क्रीन लोड होने दो
- done: काम पूरा हुआ — say में हिंदी में 1 वाक्य बताओ कि क्या किया
- need_user: यूज़र से कुछ पूछना है (जानकारी नहीं है, या पुष्टि चाहिए) — say में सवाल
नियम:
1. कभी कुछ ख़रीदना, पेमेंट, UPI, बैंक, OTP डालना, पासवर्ड डालना, अकाउंट डिलीट करना नहीं — ऐसा आए तो need_user।
2. जो जानकारी goal में नहीं है (जैसे फ़ॉर्म में नाम, पता, उम्र) उसे गढ़ो मत; पहले goal और "यूज़र के बारे में" देखो, न मिले तो need_user से पूछो।
3. अंतिम Send/भेजें, Submit/जमा करें, Post/पोस्ट, Upload, Delete जैसा बटन दबाने से पहले, अगर confirmed=false है तो need_user से साफ़ पूछो (क्या लिखा/भरा है वह भी बताओ)। confirmed=true हो तो दबा दो।
4. वीडियो चलाना, सर्च करना, ऐप में घूमना, टेक्स्ट लिखना — इनमें पुष्टि की ज़रूरत नहीं।
5. अगर वही कदम बार-बार काम न करे तो दूसरा तरीका आज़माओ (tap_xy, scroll, back)। विज्ञापन (Skip/Ad/✕) आए तो बंद करो।
6. सर्च करने के लिए (Chrome, YouTube, Play Store आदि) सर्च बॉक्स/सर्च बार/"Search or type URL" वाले तत्व के id पर सीधे type करो — ज़रूरत हो तो वह अपने आप टैप होकर लिखेगा; फिर enter। enter काम न करे तो सुझाव-सूची का पहला नतीजा tap करो।
7. वेबसाइट के फ़ॉर्म में हर खाने (नाम, मोबाइल, ईमेल…) के लिखने-के-बॉक्स के id पर अलग-अलग type करो; बॉक्स न दिखे तो scroll_down।
8. काम पूरा होते ही done।
""".trimIndent()

    suspend fun run(goal: String, confirmed: Boolean, facts: String, openApp: suspend () -> JSONObject?): JSONObject {
        val svc = MitraAccessibilityService.instance
            ?: return err("Accessibility चालू नहीं है — 'अनुमतियाँ' स्क्रीन से मित्र AI की Accessibility चालू करें")
        openApp()?.let { if (it.optString("status") == "error") return it; delay(2500) }
        MitraAccessibilityService.busy = true
        withContext(Dispatchers.Main) { svc.rich(true) }
        try { return loop(svc, goal, confirmed, facts) } finally {
            MitraAccessibilityService.busy = false
            withContext(Dispatchers.Main) { svc.rich(false) }
        }
    }

    private suspend fun loop(svc: MitraAccessibilityService, goal: String, confirmed: Boolean, facts: String): JSONObject {

        val history = ArrayList<String>()
        var lastScreen = ""
        var same = 0
        for (step in 1..MAX_STEPS) {
            val screen = withContext(Dispatchers.Main) { svc.snapshot() }
            val pkg = withContext(Dispatchers.Main) { svc.currentPackage() }
            if (Safety.isBlockedPackage(pkg)) return err(Safety.REFUSAL)
            if (screen == lastScreen) same++ else same = 0
            lastScreen = screen
            val shot = if (step == 1 || same >= 1 || screen.lines().size < 6) svc.screenshotJpeg() else null

            val prompt = buildString {
                append("goal: ").append(goal).append('\n')
                append("confirmed: ").append(confirmed).append('\n')
                if (facts.isNotBlank()) append("यूज़र के बारे में: ").append(facts).append('\n')
                append("ऐप: ").append(pkg ?: "?").append(", कदम ").append(step).append('/').append(MAX_STEPS).append('\n')
                if (history.isNotEmpty()) append("अब तक के कदम:\n").append(history.takeLast(8).joinToString("\n")).append('\n')
                if (same >= 2) append("ध्यान दो: पिछले कदमों से स्क्रीन नहीं बदली, दूसरा तरीका आज़माओ।\n")
                append("स्क्रीन के तत्व:\n").append(screen.ifBlank { "(कुछ नहीं पढ़ा गया — तस्वीर देखो)" })
            }
            val parts = JSONArray()
            if (shot != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(shot, Base64.NO_WRAP))))
            parts.put(JSONObject().put("text", prompt))
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", RULES))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))

            val a = try {
                parse(Gemini.textOf(Gemini.generate(body)))
            } catch (e: Exception) {
                return err("AI से जुड़ नहीं पाए: ${e.message}")
            } ?: run { history.add("$step. (AI का जवाब समझ नहीं आया)"); null } ?: continue

            val action = a.optString("action")
            val id = a.optInt("id", -1)
            val text = a.optString("text")
            val say = a.optString("say")
            when (action) {
                "done" -> return JSONObject().put("status", "ok").put("message", say.ifBlank { "काम हो गया" })
                "need_user" -> return JSONObject().put("status", "needs_confirmation")
                    .put("message", "अभी रुका हूँ। यूज़र से पूछो: $say — जवाब मिलने पर app_task को वही goal (जवाब जोड़कर) और ज़रूरत हो तो confirmed=true के साथ दोबारा बुलाओ; ऐप वहीं से आगे चलेगा।")
            }
            val okDone = withContext(Dispatchers.Main) {
                when (action) {
                    "tap" -> svc.tapId(id)
                    "long_press" -> svc.tapId(id, long = true)
                    "tap_xy" -> svc.tapPercent(a.optInt("x", 50), a.optInt("y", 50))
                    "type" -> svc.typeId(id, text)
                    "enter" -> svc.pressEnter()
                    "scroll_down" -> svc.scroll(true) || svc.swipe("up")
                    "scroll_up" -> svc.scroll(false) || svc.swipe("down")
                    "swipe_left" -> svc.swipe("left")
                    "swipe_right" -> svc.swipe("right")
                    "back" -> { svc.back(); true }
                    "wait" -> true
                    else -> false
                }
            }
            history.add("$step. $action${if (id >= 0) " [$id]" else ""}${if (text.isNotBlank()) " \"${text.take(40)}\"" else ""} → ${if (okDone) "हुआ" else "नहीं हुआ"} (${a.optString("thought").take(60)})")
            delay(if (action == "wait") 2500 else if (action == "type") 900 else 1600)
        }
        return err("काफ़ी कोशिश के बाद भी काम पूरा नहीं हुआ। स्क्रीन पर जहाँ रुका है वहाँ से आप खुद देख लें।")
    }

    private fun parse(t: String): JSONObject? {
        val s = t.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try { JSONObject(s) } catch (e: Exception) {
            val i = s.indexOf('{'); val j = s.lastIndexOf('}')
            if (i >= 0 && j > i) try { JSONObject(s.substring(i, j + 1)) } catch (e2: Exception) { null } else null
        }
    }

    private fun err(msg: String) = JSONObject().put("status", "error").put("message", msg)
}
