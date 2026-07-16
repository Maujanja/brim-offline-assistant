package com.brimedge.voiceassistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Thin wrapper around Android's offline TextToSpeech engine.
 * Prefers Swahili; falls back to the default engine locale if Swahili voice data
 * is not installed on the device.
 */
class SpeechManager(context: Context) {

    interface Listener {
        fun onReady(available: Boolean)
        fun onMissingVoiceData()
        fun onDone()
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var listener: Listener? = null

    fun initialize(context: Context, listener: Listener) {
        this.listener = listener
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                listener.onReady(false)
                return@TextToSpeech
            }
            val swahili = Locale("sw", "TZ")
            val result = tts?.setLanguage(swahili)
            when (result) {
                TextToSpeech.LANG_MISSING_DATA -> listener.onMissingVoiceData()
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    tts?.language = Locale.getDefault()
                }
                else -> {}
            }
            tts?.setSpeechRate(0.95f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { listener.onDone() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { listener.onDone() }
            })
            ready = true
            listener.onReady(true)
        }
    }

    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brim-utterance")
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
