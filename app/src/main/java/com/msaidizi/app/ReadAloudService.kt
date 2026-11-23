package com.msaidizi.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Inasoma kwa sauti maandishi uliyoyagusa kwenye arifa, kwa sauti ile ile ya Msaidizi (TTS ya simu), kisha inajifunga. */
class ReadAloudService : Service() {

    companion object {
        const val EXTRA_TEXT = "text"
    }

    private var tts: TextToSpeech? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        tts?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) { status ->
            val e = engine
            if (status != TextToSpeech.SUCCESS || e == null) {
                stopSelf()
                return@TextToSpeech
            }
            val r = e.setLanguage(Locale("sw", "TZ"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                e.setLanguage(Locale.US)
            }
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = stopSelf()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = stopSelf()
            })
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msaidizi-read")
        }
        tts = engine
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
