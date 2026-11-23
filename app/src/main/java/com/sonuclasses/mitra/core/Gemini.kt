package com.sonuclasses.mitra.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Plain REST calls to the Gemini API (text chat, function calling, vision, search grounding). */
object Gemini {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    val TEXT_MODELS = listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-3-flash-preview", "gemini-3.6-flash", "gemini-flash-latest")

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    class ApiError(val code: Int, msg: String) : Exception(msg)

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun post(model: String, body: JSONObject, key: String): JSONObject {
        val req = Request.Builder()
            .url("$BASE/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val m = try { JSONObject(txt).getJSONObject("error").optString("message") } catch (e: Exception) { txt.take(200) }
                throw ApiError(r.code, m)
            }
            return JSONObject(txt)
        }
    }

    /** Try each text model in turn; 404/400-model errors move on to the next one. */
    suspend fun generate(body: JSONObject, key: String = Prefs.apiKey): JSONObject = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (m in TEXT_MODELS) {
            try { return@withContext post(m, body, key) } catch (e: ApiError) {
                // keep the most useful error (a 404 for a retired model is the least useful)
                if (last !is ApiError || (last as ApiError).code == 404) last = e
                if (e.code == 401 || e.code == 403) throw e
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("Gemini error")
    }

    /** Returns null when the key works, otherwise a Hindi error message. */
    suspend fun validateKey(key: String): String? {
        if (key.isBlank()) return "कृपया key डालें"
        return try {
            val body = JSONObject().put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "नमस्ते, सिर्फ़ 'ठीक' लिखो।")))))
            generate(body, key.trim()); null
        } catch (e: ApiError) {
            when (e.code) {
                400, 401, 403 -> "यह key सही नहीं लग रही (${e.message})"
                429 -> null // valid key, just rate-limited
                else -> "जाँच नहीं हो पाई: ${e.message}"
            }
        } catch (e: Exception) { "इंटरनेट से जुड़ नहीं पाए: ${e.message}" }
    }

    fun textOf(resp: JSONObject): String {
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought")) continue
            if (p.has("text")) sb.append(p.getString("text"))
        }
        return sb.toString().trim()
    }

    /** Google Search grounded short answer in Hindi. */
    suspend fun searchAnswer(query: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(
                JSONObject().put("text", "इस सवाल का ताज़ा, सही जवाब 2-4 छोटे वाक्यों में हिंदी में दो (बोलकर सुनाने लायक, बिना मार्कडाउन): $query")))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return textOf(generate(body)).ifEmpty { "कोई जानकारी नहीं मिली।" }
    }

    /** Describe a JPEG image in Hindi. */
    suspend fun describeImage(jpeg: ByteArray, question: String): String {
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            .put(JSONObject().put("text", (question.ifBlank { "इस तस्वीर में क्या दिख रहा है?" }) +
                " हिंदी में 2-4 छोटे वाक्यों में बताओ, बिना मार्कडाउन।"))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        return textOf(generate(body)).ifEmpty { "तस्वीर समझ नहीं पाया।" }
    }
}
