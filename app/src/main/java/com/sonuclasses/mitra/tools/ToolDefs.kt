package com.sonuclasses.mitra.tools

import org.json.JSONArray
import org.json.JSONObject

/** Function declarations shared by the Live session and the REST fallback. */
object ToolDefs {
    private fun s(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun b(desc: String) = JSONObject().put("type", "BOOLEAN").put("description", desc)
    private fun i(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)

    private fun fn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
            val params = JSONObject().put("type", "OBJECT").put("properties", p)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            o.put("parameters", params)
        }
        return o
    }

    private val CONFIRM = b("यूज़र ने साफ़ 'हाँ' कहकर पुष्टि कर दी हो तभी true; पहली बार false भेजो")

    fun declarations(): JSONArray = JSONArray()
        .put(fn("open_app", "फ़ोन में कोई ऐप खोलो (नाम से)।", mapOf("name" to s("ऐप का नाम, जैसे YouTube, Camera, Instagram")), listOf("name")))
        .put(fn("call_contact", "किसी संपर्क या नंबर पर फ़ोन कॉल करो। पहले पुष्टि लो।",
            mapOf("name" to s("संपर्क का नाम (रोमन अक्षरों में) या फ़ोन नंबर"), "confirmed" to CONFIRM), listOf("name")))
        .put(fn("send_sms", "किसी संपर्क को SMS भेजो। पहले पुष्टि लो।",
            mapOf("name" to s("संपर्क का नाम या नंबर"), "text" to s("मैसेज"), "confirmed" to CONFIRM), listOf("name", "text")))
        .put(fn("send_whatsapp", "किसी संपर्क को WhatsApp मैसेज भेजो। पहले पुष्टि लो।",
            mapOf("name" to s("संपर्क का नाम या नंबर"), "text" to s("मैसेज"), "confirmed" to CONFIRM), listOf("name", "text")))
        .put(fn("read_notifications", "हाल की नोटिफ़िकेशन पढ़ो (नंबर, ऐप, भेजने वाला, मैसेज)।",
            mapOf("app" to s("वैकल्पिक: सिर्फ़ इस ऐप की, जैसे WhatsApp"))))
        .put(fn("reply_notification", "किसी नोटिफ़िकेशन (जैसे WhatsApp मैसेज) का सीधे जवाब भेजो। पहले पुष्टि लो।",
            mapOf("index" to i("read_notifications में मिला नंबर"), "text" to s("जवाब"), "confirmed" to CONFIRM), listOf("index", "text")))
        .put(fn("set_alarm", "अलार्म लगाओ।",
            mapOf("hour" to i("घंटा 0-23"), "minute" to i("मिनट 0-59"), "label" to s("वैकल्पिक लेबल")), listOf("hour", "minute")))
        .put(fn("set_timer", "टाइमर लगाओ।", mapOf("seconds" to i("कुल सेकंड"), "label" to s("वैकल्पिक लेबल")), listOf("seconds")))
        .put(fn("set_reminder", "रिमाइंडर लगाओ जो तय समय पर नोटिफ़िकेशन देगा। minutes_from_now या (hour, minute) में से एक दो।",
            mapOf("text" to s("किस बात की याद दिलानी है"), "minutes_from_now" to i("कितने मिनट बाद"),
                "hour" to i("घंटा 0-23"), "minute" to i("मिनट 0-59")), listOf("text")))
        .put(fn("flashlight", "टॉर्च चालू या बंद करो।", mapOf("on" to b("true = चालू")), listOf("on")))
        .put(fn("set_volume", "आवाज़ (मीडिया वॉल्यूम) बदलो।",
            mapOf("action" to s("up, down, mute, unmute या set"), "percent" to i("set के लिए 0-100")), listOf("action")))
        .put(fn("open_settings", "सेटिंग्स पैनल खोलो।",
            mapOf("panel" to s("wifi, bluetooth, internet, volume, display, battery, location, nfc, general")), listOf("panel")))
        .put(fn("take_photo", "कैमरा ऐप खोलकर फ़ोटो लेने दो।"))
        .put(fn("describe_camera", "पीछे वाले कैमरे से तस्वीर लेकर बताओ कि सामने क्या दिख रहा है।",
            mapOf("question" to s("वैकल्पिक: तस्वीर के बारे में खास सवाल"))))
        .put(fn("play_youtube", "YouTube पर गाना या वीडियो खोजकर चलाओ।", mapOf("query" to s("क्या चलाना है")), listOf("query")))
        .put(fn("web_search", "इंटरनेट (Google Search) से ताज़ा जानकारी लाओ: खबर, मौसम, स्कोर, कीमत आदि।",
            mapOf("query" to s("खोज")), listOf("query")))
        .put(fn("screen_action", "Accessibility से अभी खुली स्क्रीन पर काम करो: read (दिख रहा टेक्स्ट पढ़ो), click (टेक्स्ट वाले बटन पर टैप), scroll_down, scroll_up, back, home, type (फ़ोकस वाले बॉक्स में टेक्स्ट लिखो)।",
            mapOf("action" to s("read, click, scroll_down, scroll_up, back, home, type"), "text" to s("click के लिए बटन का टेक्स्ट, type के लिए लिखने वाला टेक्स्ट")), listOf("action")))
        .put(fn("app_task", "किसी भी ऐप के अंदर कई कदमों वाला काम खुद स्क्रीन देखकर टैप/टाइप/स्क्रॉल करते हुए पूरा करो: जैसे YouTube पर वीडियो खोजकर चलाना, WhatsApp में किसी को मैसेज लिखकर भेजना, फ़ॉर्म भरना, ऐप की सेटिंग बदलना।",
            mapOf("app" to s("कौन सा ऐप खोलना है (नाम), जैसे YouTube, WhatsApp, Chrome। पहले से खुले ऐप में आगे बढ़ना हो तो खाली छोड़ो"),
                "goal" to s("पूरा काम साफ़ शब्दों में, जो लिखना/भरना है उसके साथ, जैसे: 'Rahul की चैट खोलकर लिखो: मैं 5 बजे आऊँगा, और भेजो'"),
                "confirmed" to CONFIRM), listOf("goal")))
        .put(fn("remember_fact", "यूज़र के बारे में कोई स्थायी बात याद रखो।", mapOf("fact" to s("याद रखने वाली बात, छोटे वाक्य में")), listOf("fact")))
}
