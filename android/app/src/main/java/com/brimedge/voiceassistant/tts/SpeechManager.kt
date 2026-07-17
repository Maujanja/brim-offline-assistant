package com.brimedge.voiceassistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.brimedge.voiceassistant.voice.LanguageManager
import java.util.Locale

/**
 * Thin wrapper around Android's offline TextToSpeech engine. Locale is taken
 * from the active [LanguageManager] profile so switching language switches
 * both recognition and voice output.
 */
class SpeechManager(context: Context) {

    interface Listener {
        fun onReady(available: Boolean)
        fun onMissingVoiceData()
        fun onDone()
    }

    private var tts: TextToSpeech? = null
    private var ready = false

    fun initialize(context: Context, listener: Listener) {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                listener.onReady(false); return@TextToSpeech
            }
            val preferred = LanguageManager.current().locale
            val result = tts?.setLanguage(preferred)
            when (result) {
                TextToSpeech.LANG_MISSING_DATA -> listener.onMissingVoiceData()
                TextToSpeech.LANG_NOT_SUPPORTED -> { tts?.language = Locale.US }
                else -> {}
            }
            tts?.setSpeechRate(1.0f)
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
