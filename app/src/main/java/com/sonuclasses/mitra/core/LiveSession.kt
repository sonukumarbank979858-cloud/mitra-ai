package com.sonuclasses.mitra.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.sonuclasses.mitra.tools.ToolDefs
import com.sonuclasses.mitra.tools.ToolExecutor
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Gemini Live API session over a raw WebSocket (BidiGenerateContent):
 * 16 kHz PCM mic in, 24 kHz PCM audio out, server VAD with barge-in, function calling.
 */
class LiveSession(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onReady(model: String)
        fun onFailed(reason: String)
        fun onClosed(reason: String)
    }

    companion object {
        val MODELS = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")
        private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000
    }

    private val models: List<String> = (listOf(Prefs.lastLiveModel) + MODELS).filter { it.isNotBlank() }.distinct()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile var isReady = false
        private set
    private var modelIdx = 0
    private var lastError = ""

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var lastAudioAt = 0L
    @Volatile private var turnDone = true

    fun start() { connect(0) }

    private fun connect(idx: Int) {
        if (stopped) return
        if (idx >= models.size) { listener.onFailed(lastError.ifBlank { "कोई Live मॉडल उपलब्ध नहीं" }); return }
        modelIdx = idx
        val req = Request.Builder().url("$URL?key=${Prefs.apiKey}").build()
        val myIdx = idx
        ws = Gemini.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupMessage(models[myIdx]).toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                ended(myIdx, "$code ${reason.take(120)}")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended(myIdx, (response?.message ?: "") + " " + (t.message ?: t.javaClass.simpleName))
            }
        })
    }

    @Synchronized
    private fun ended(idx: Int, reason: String) {
        if (idx != modelIdx) return
        if (stopped) return
        lastError = reason.trim()
        if (!isReady) {
            ws = null
            connect(idx + 1)   // setup refused → try the next model
        } else {
            isReady = false
            releaseAudio()
            modelIdx = -1
            Assistant.scope.launch { listener.onClosed(lastError) }
        }
    }

    private fun setupMessage(model: String): JSONObject {
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.voice))))
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Persona.systemPrompt()))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", ToolDefs.declarations())))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        return JSONObject().put("setup", setup)
    }

    private fun handle(raw: String) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }
        if (msg.has("setupComplete")) {
            isReady = true
            val m = models[modelIdx.coerceAtLeast(0)]
            Prefs.lastLiveModel = m
            startAudio()
            Assistant.scope.launch { listener.onReady(m) }
            return
        }
        msg.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted")) {
                flushPlayback()
                Assistant.finalizeTurn()
                Assistant.state.value = VState.LISTENING
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) Assistant.transcript("user", it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) Assistant.transcript("model", it) }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    val inline = p.optJSONObject("inlineData")
                    if (inline != null && inline.optString("mimeType").startsWith("audio")) {
                        val pcm = Base64.decode(inline.optString("data"), Base64.DEFAULT)
                        playQueue.offer(pcm)
                        turnDone = false
                        Assistant.state.value = VState.SPEAKING
                    }
                }
            }
            if (sc.optBoolean("turnComplete")) { turnDone = true; Assistant.finalizeTurn() }
        }
        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls -> runTools(calls) }
        if (msg.has("goAway")) { /* server will close soon; Assistant reconnects on close */ }
    }

    private fun runTools(calls: JSONArray) {
        Assistant.state.value = VState.THINKING
        Assistant.scope.launch {
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val fc = calls.getJSONObject(i)
                val name = fc.optString("name")
                val result = ToolExecutor.run(ctx, name, fc.optJSONObject("args") ?: JSONObject())
                val r = JSONObject().put("name", name).put("response", JSONObject().put("result", result))
                if (fc.has("id")) r.put("id", fc.getString("id"))
                responses.put(r)
            }
            ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            if (Assistant.state.value == VState.THINKING) Assistant.state.value = VState.LISTENING
        }
    }

    fun sendText(text: String) {
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    // ---------- audio ----------
    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (micThread != null) return
        val minIn = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 6400))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Assistant.system("माइक शुरू नहीं हो पाया — माइक की अनुमति जाँचें।")
            return
        }
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true } catch (_: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true } catch (_: Exception) {}
        recorder = rec

        val minOut = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minOut, OUT_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = tr
        tr.play()

        rec.startRecording()
        micThread = Thread {
            val buf = ByteArray(3200) // 100 ms
            while (!stopped && isReady) {
                val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n < 0) break
                if (n == 0) continue
                var sum = 0.0
                var i = 0
                while (i + 1 < n) {
                    val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                    sum += s * s; i += 2
                }
                val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
                val lvl = (rms / 4000.0).coerceIn(0.0, 1.0).toFloat()
                if (Assistant.state.value != VState.SPEAKING) Assistant.level.value = lvl
                val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
            }
        }.apply { name = "mitra-mic"; start() }

        playThread = Thread {
            while (!stopped && isReady) {
                val chunk = playQueue.poll(80, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    // level for the orb while Mitra speaks
                    var peak = 0
                    var j = 0
                    while (j + 1 < chunk.size) { val s = kotlin.math.abs(((chunk[j + 1].toInt() shl 8) or (chunk[j].toInt() and 0xff)).toShort().toInt()); if (s > peak) peak = s; j += 64 }
                    Assistant.level.value = (peak / 12000f).coerceIn(0f, 1f)
                    try { track?.write(chunk, 0, chunk.size) } catch (_: Exception) {}
                    lastAudioAt = System.currentTimeMillis()
                } else if (turnDone && Assistant.state.value == VState.SPEAKING && System.currentTimeMillis() - lastAudioAt > 350) {
                    Assistant.state.value = VState.LISTENING
                }
            }
        }.apply { name = "mitra-play"; start() }
    }

    private fun flushPlayback() {
        playQueue.clear()
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    }

    private fun releaseAudio() {
        val r = recorder; recorder = null
        val t = track; track = null
        micThread = null; playThread = null
        playQueue.clear()
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
        try { t?.pause(); t?.flush(); t?.release() } catch (_: Exception) {}
    }

    fun stop() {
        stopped = true
        isReady = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        // give threads a moment to leave their loops before releasing
        Thread { Thread.sleep(150); releaseAudio() }.start()
    }
}
