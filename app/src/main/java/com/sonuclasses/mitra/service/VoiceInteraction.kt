package com.sonuclasses.mitra.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.sonuclasses.mitra.ui.AssistActivity

/** Minimal digital-assistant plumbing: the assist gesture opens Mitra and starts listening. */
class MitraVoiceInteractionService : VoiceInteractionService()

class MitraVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MitraSession(this)
}

class MitraSession(private val svc: VoiceInteractionSessionService) : VoiceInteractionSession(svc) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val i = Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startAssistantActivity(i) } catch (e: Exception) { try { context.startActivity(i) } catch (_: Exception) {} }
        hide()
    }
}

/** Required by the voice-interaction config; Mitra does its own recognition, so this is a no-op. */
class MitraRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        try { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
    }
    override fun onCancel(listener: RecognitionService.Callback?) {}
    override fun onStopListening(listener: RecognitionService.Callback?) {}
}
