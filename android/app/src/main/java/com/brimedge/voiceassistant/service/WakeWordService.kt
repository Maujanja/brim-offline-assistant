package com.brimedge.voiceassistant.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.brimedge.voiceassistant.R
import com.brimedge.voiceassistant.commands.CommandExecutor
import java.util.Locale

/**
 * Foreground service that continuously listens for the "Hey Brim" / "Hey Bot" wake word
 * using Android's on-device SpeechRecognizer (offline language pack must be installed
 * on the device: Settings → Google → Voice → Offline speech recognition → Swahili).
 *
 * On wake-word detection it starts a command-recognition pass, dispatches the command
 * to [CommandExecutor], speaks a confirmation via [TextToSpeech], then resumes listening.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "brim_wake"
        private const val NOTIF_ID = 1
        private val WAKE_WORDS = listOf("hey brim", "hey bot", "hebrim", "hey bream")
    }

    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var executor: CommandExecutor
    private lateinit var wakeLock: PowerManager.WakeLock

    private var awaitingCommand = false
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "brim:wake").apply { acquire() }

        executor = CommandExecutor(applicationContext)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("sw", "TZ")
                ttsReady = true
            }
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(this)
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sw-TZ")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            handler.postDelayed({ startListening() }, 1500)
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brim")
    }

    private fun handleTranscript(text: String) {
        val lower = text.lowercase(Locale.getDefault()).trim()
        if (lower.isEmpty()) return

        if (awaitingCommand) {
            awaitingCommand = false
            val response = executor.execute(lower)
            speak(response)
        } else {
            if (WAKE_WORDS.any { lower.contains(it) }) {
                awaitingCommand = true
                speak("Naam, nakusikia")
            }
        }
    }

    // RecognitionListener ------------------------------------------------------
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onError(error: Int) {
        // Common: NO_MATCH, SPEECH_TIMEOUT — just restart.
        handler.postDelayed({ startListening() }, 400)
    }

    override fun onResults(results: Bundle?) {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        list?.firstOrNull()?.let { handleTranscript(it) }
        handler.postDelayed({ startListening() }, 300)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (awaitingCommand) return // wait for final result for commands
        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        list?.firstOrNull()?.let {
            val lower = it.lowercase(Locale.getDefault())
            if (WAKE_WORDS.any { w -> lower.contains(w) }) {
                awaitingCommand = true
                speak("Naam")
                recognizer.stopListening()
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { recognizer.destroy() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
