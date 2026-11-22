package com.sonuclasses.mitra.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sonuclasses.mitra.service.AssistantService
import com.sonuclasses.mitra.tools.ToolDefs
import com.sonuclasses.mitra.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class VState { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING }

data class Msg(val id: Long, val role: String, val text: String, val final: Boolean)

/** Central controller: voice session (Live or fallback), transcript, and the REST chat loop. */
object Assistant {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val state = MutableStateFlow(VState.IDLE)
    val level = MutableStateFlow(0f)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val modeLabel = MutableStateFlow("")
    val busyText = MutableStateFlow(false)

    val active: Boolean get() = state.value != VState.IDLE

    private var live: LiveSession? = null
    private var fallback: FallbackVoice? = null
    private var liveRestarts = 0
    private var idSeq = 1L
    private val lock = Any()

    // ---------- transcript ----------
    fun transcript(role: String, text: String, final: Boolean = false) {
        if (text.isEmpty()) return
        synchronized(lock) {
            val list = messages.value.toMutableList()
            val last = list.lastOrNull()
            if (last != null && last.role == role && !last.final) {
                list[list.size - 1] = last.copy(text = last.text + text, final = final)
            } else {
                // a new speaker closes the previous bubble
                if (last != null && !last.final) list[list.size - 1] = last.copy(final = true)
                list.add(Msg(idSeq++, role, text.trimStart(), final))
            }
            messages.value = list.takeLast(200)
        }
    }

    fun finalizeTurn() {
        synchronized(lock) {
            val list = messages.value.toMutableList()
            val last = list.lastOrNull() ?: return
            if (!last.final) { list[list.size - 1] = last.copy(final = true); messages.value = list }
        }
    }

    fun system(text: String) = transcript("system", text, true)

    fun clearChat() {
        synchronized(lock) { messages.value = emptyList() }
        synchronized(history) { history.clear() }
    }

    // ---------- voice ----------
    fun toggleVoice(ctx: Context) { if (active) stopVoice(ctx) else startVoice(ctx) }

    fun startVoice(ctx: Context) {
        val app = ctx.applicationContext
        if (active) return
        if (!Prefs.hasKey) { system("पहले सेटिंग्स में Gemini API key डालें।"); return }
        state.value = VState.CONNECTING
        liveRestarts = 0
        try { ContextCompat.startForegroundService(app, Intent(app, AssistantService::class.java).setAction(AssistantService.ACTION_START)) }
        catch (e: Exception) { /* service may be blocked in background; the session still runs while app is open */ }
        if (Prefs.useLive) startLive(app) else startFallback(app)
    }

    private fun startLive(app: Context) {
        modeLabel.value = "Live"
        live = LiveSession(app, object : LiveSession.Listener {
            override fun onReady(model: String) {
                modeLabel.value = "Live • $model"
                state.value = VState.LISTENING
            }
            override fun onFailed(reason: String) {
                live = null
                if (state.value == VState.IDLE) return
                system("Live वॉइस शुरू नहीं हो पाया ($reason)। सामान्य वॉइस मोड चालू कर रहा हूँ।")
                startFallback(app)
            }
            override fun onClosed(reason: String) {
                live = null
                if (state.value == VState.IDLE) return
                if (liveRestarts < 3) {
                    liveRestarts++
                    state.value = VState.CONNECTING
                    startLive(app)
                } else {
                    system("कनेक्शन टूट गया: $reason")
                    stopVoice(app)
                }
            }
        }).also { it.start() }
    }

    private fun startFallback(app: Context) {
        modeLabel.value = "सामान्य वॉइस"
        fallback = FallbackVoice(app).also { it.start() }
    }

    fun stopVoice(ctx: Context) {
        state.value = VState.IDLE
        level.value = 0f
        live?.stop(); live = null
        fallback?.stop(); fallback = null
        finalizeTurn()
        val app = ctx.applicationContext
        try { app.startService(Intent(app, AssistantService::class.java).setAction(AssistantService.ACTION_SYNC)) } catch (_: Exception) {}
    }

    // ---------- REST chat with function calling ----------
    private val history = mutableListOf<JSONObject>()
    private val chatMutex = Mutex()

    private fun userText(t: String) = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", t)))

    /** One full user turn: model ↔ tools until it answers in text. Returns the spoken/text reply. */
    suspend fun chatTurn(ctx: Context, text: String): String = chatMutex.withLock {
        val app = ctx.applicationContext
        synchronized(history) {
            history.add(userText(text))
            // keep history bounded, cutting only at a plain user text turn
            while (history.size > 40) {
                history.removeAt(0)
                while (history.isNotEmpty() && !(history[0].optString("role") == "user" &&
                            history[0].getJSONArray("parts").optJSONObject(0)?.has("text") == true)) history.removeAt(0)
            }
        }
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", ToolDefs.declarations()))
        repeat(6) {
            val contents = JSONArray(); synchronized(history) { history.forEach { contents.put(it) } }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Persona.systemPrompt()))))
                .put("contents", contents)
                .put("tools", tools)
            val resp = try { Gemini.generate(body) } catch (e: Gemini.ApiError) {
                return@withLock when (e.code) {
                    429 -> "अभी बहुत सारे अनुरोध हो गए हैं (रेट लिमिट)। थोड़ी देर बाद फिर कोशिश करें।"
                    400, 401, 403 -> "API key में दिक्कत है: ${e.message}"
                    else -> "माफ़ कीजिए, जवाब नहीं मिल पाया: ${e.message}"
                }
            } catch (e: Exception) { return@withLock "इंटरनेट से जुड़ नहीं पाए। कनेक्शन जाँचें।" }

            val content = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: return@withLock "माफ़ कीजिए, इस बात का जवाब नहीं दे सकता।"
            if (!content.has("role")) content.put("role", "model")
            val parts = content.optJSONArray("parts") ?: JSONArray()
            synchronized(history) { history.add(content) }

            val calls = (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.has("functionCall") }
            if (calls.isEmpty()) return@withLock Gemini.textOf(resp).ifEmpty { "ठीक है।" }

            val respParts = JSONArray()
            for (p in calls) {
                val fc = p.getJSONObject("functionCall")
                val name = fc.optString("name")
                val result = ToolExecutor.run(app, name, fc.optJSONObject("args") ?: JSONObject())
                val fr = JSONObject().put("name", name).put("response", result)
                if (fc.has("id")) fr.put("id", fc.getString("id"))
                respParts.put(JSONObject().put("functionResponse", fr))
            }
            synchronized(history) { history.add(JSONObject().put("role", "user").put("parts", respParts)) }
        }
        "काम पूरा करने में ज़्यादा कदम लग गए, कृपया फिर से साफ़ बताएं।"
    }

    /** Text chat entry point (chat screen and quick chips when no voice session is running). */
    fun sendText(ctx: Context, text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val l = live
        if (l != null && l.isReady) { transcript("user", t, true); l.sendText(t); return }
        if (!Prefs.hasKey) { system("पहले सेटिंग्स में Gemini API key डालें।"); return }
        transcript("user", t, true)
        busyText.value = true
        scope.launch {
            val reply = chatTurn(ctx, t)
            transcript("model", reply, true)
            busyText.value = false
            fallback?.speak(reply)
        }
    }
}
