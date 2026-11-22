package com.msaidizi.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener as AndroidRecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Hatua 1: Vosk (kwenye simu yenyewe, bila intaneti, bila AccessKey) inasikiliza kifungu cha wake word.
 * Hatua 2: Kikisikika, Vosk inasimama, mlio unalia, na SpeechRecognizer ya simu inasikiliza amri MOJA.
 *          Kisha CommandHandler inaitekeleza na Vosk inaanza kusikiliza tena.
 *
 * Wake word lazima iwe maneno ya Kiingereza (modeli ndogo ya Vosk ya Kiingereza), mfano "hey phone".
 */
class VoiceService : Service() {

    companion object {
        const val PREFS = "cfg"
        const val KEY_WAKE = "wake"
        const val KEY_LANG = "lang"
        const val DEFAULT_WAKE = "hey phone"
        const val ACTION_STOP = "com.msaidizi.app.STOP"
        private const val CHANNEL_ID = "msaidizi"
        private const val NOTIF_ID = 1
        private const val TAG = "Msaidizi"
        private const val COMMAND_TIMEOUT_MS = 9000L
        private const val MODEL_ASSET = "model-en"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var commands: CommandHandler
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: SpeechRecognizer? = null
    private var wakePhrase = DEFAULT_WAKE
    private var language = "sw-TZ"
    private var busy = false          // true wakati tunasubiri/tunatekeleza amri
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        commands = CommandHandler(this)
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        wakePhrase = prefs.getString(KEY_WAKE, DEFAULT_WAKE)!!.lowercase().trim().ifBlank { DEFAULT_WAKE }
        language = prefs.getString(KEY_LANG, "sw-TZ")!!
        if (model == null) loadModel() else if (speech == null && !busy) startWakeListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        stopWakeListening()
        model?.close()
        model = null
        commands.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Vosk (wake word)

    private fun loadModel() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        StorageService.unpack(
            this, MODEL_ASSET, MODEL_ASSET,
            { m ->
                if (destroyed) { m.close(); return@unpack }
                model = m
                startWakeListening()
            },
            { e -> fail("Modeli ya sauti haikupatikana: ${e.message}") }
        )
    }

    private fun startWakeListening() {
        val m = model ?: return
        stopWakeListening()
        try {
            // Grammar ndogo: kifungu cha wake word tu + "[unk]" kwa kila kingine. Haraka na sahihi zaidi.
            val grammar = JSONArray().put(wakePhrase).put("[unk]").toString()
            val rec = Recognizer(m, 16000.0f, grammar)
            val svc = SpeechService(rec, 16000.0f)
            svc.startListening(voskListener)
            speech = svc
        } catch (e: Exception) {
            Log.e(TAG, "startWakeListening", e)
            fail("Imeshindwa kuanza kusikiliza: ${e.message}")
        }
    }

    private fun stopWakeListening() {
        speech?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.shutdown() } catch (_: Exception) {}
        }
        speech = null
    }

    private val voskListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk error", exception)
            handler.post { if (!busy && !destroyed) startWakeListening() }
        }
        override fun onTimeout() {}

        private fun check(json: String?, key: String) {
            val text = try {
                JSONObject(json ?: return).optString(key, "")
            } catch (_: Exception) {
                ""
            }
            if (text.contains(wakePhrase)) handler.post { onWakeWord() }
        }
    }

    private fun onWakeWord() {
        if (busy || destroyed) return
        busy = true
        stopWakeListening()           // achia maikrofoni kwa SpeechRecognizer
        beep()
        handler.postDelayed({ listenForCommand() }, 250)
        // Ulinzi: kama hakuna kinachotokea, rudi kusikiliza wake word.
        handler.postDelayed({ if (busy) resumeWakeWord() }, COMMAND_TIMEOUT_MS)
    }

    private fun resumeWakeWord() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        busy = false
        if (!destroyed) startWakeListening()
    }

    // ------------------------------------------------------------ amri

    private fun listenForCommand() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            commands.speak("Huduma ya kutambua sauti haipatikani kwenye simu hii")
            resumeWakeWord()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(object : AndroidRecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) commands.execute(text)
                    // subiri TTS imalize ili isijisikie yenyewe
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ resumeWakeWord() }, 2200)
                }

                override fun onError(error: Int) = resumeWakeWord()
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        recognizer?.startListening(intent)
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            handler.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        commands.speak(message)
        // toa muda TTS isome ujumbe kabla ya kusimama
        handler.postDelayed({ stopSelf() }, 3500)
    }

    // ------------------------------------------------------------ arifa

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Msaidizi", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Msaidizi anasikiliza")
            .setContentText("Sema \"$wakePhrase\" kisha amri")
            .setContentIntent(open)
            .addAction(0, "Acha", stop)
            .setOngoing(true)
            .build()
    }
}
