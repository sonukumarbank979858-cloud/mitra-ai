package com.sonuclasses.mitra.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.launch
import java.util.Locale

/** Fallback voice loop: Android SpeechRecognizer (hi-IN) → Gemini REST with tools → TextToSpeech (hi-IN). */
class FallbackVoice(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var stopped = false
    private var silentRounds = 0

    fun start() {
        main.post {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("hi", "IN")
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { Assistant.state.value = VState.SPEAKING }
                        override fun onDone(utteranceId: String?) { main.post { if (!stopped) listen() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { main.post { if (!stopped) listen() } }
                    })
                    ttsReady = true
                }
            }
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                Assistant.system("इस फ़ोन में वॉइस पहचान (Google Speech) उपलब्ध नहीं है। टेक्स्ट चैट इस्तेमाल करें।")
                Assistant.stopVoice(ctx); return@post
            }
            sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(rl) }
            listen()
        }
    }

    private fun listen() {
        if (stopped) return
        Assistant.state.value = VState.LISTENING
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        try { sr?.startListening(i) } catch (e: Exception) { Assistant.system("माइक शुरू नहीं हो पाया: ${e.message}") }
    }

    private val rl = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { Assistant.level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Assistant.state.value = VState.THINKING }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            if (stopped) return
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                silentRounds++
                if (silentRounds >= 3) { Assistant.stopVoice(ctx); return }
                main.postDelayed({ listen() }, 300)
            } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                main.postDelayed({ listen() }, 600)
            } else {
                Assistant.system("वॉइस पहचान में दिक्कत (कोड $error)")
                Assistant.stopVoice(ctx)
            }
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) { main.postDelayed({ listen() }, 300); return }
            silentRounds = 0
            Assistant.transcript("user", text, true)
            Assistant.state.value = VState.THINKING
            Assistant.scope.launch {
                val reply = Assistant.chatTurn(ctx, text)
                Assistant.transcript("model", reply, true)
                speak(reply)
            }
        }
    }

    fun speak(text: String) {
        if (stopped) return
        main.post {
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mitra-${System.currentTimeMillis()}")
            else listen()
        }
    }

    fun stop() {
        stopped = true
        main.post {
            try { sr?.cancel(); sr?.destroy() } catch (_: Exception) {}
            try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
            sr = null; tts = null
        }
    }
}
