package com.msaidizi.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Inasoma kwa sauti maandishi uliyoyagusa kwenye arifa, kwa sauti ile ile ya Msaidizi (API ya TTS au TTS ya simu), kisha inajifunga. */
class ReadAloudService : Service() {

    companion object {
        const val EXTRA_TEXT = "text"
    }

    private var speaker: Speaker? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (text.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val sp = speaker ?: Speaker(this).also { speaker = it }
        sp.speak(text)
        sp.afterSpeech(maxWaitMs = 300_000) { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }
}
