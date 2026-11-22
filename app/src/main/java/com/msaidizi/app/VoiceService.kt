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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineManagerErrorCallback

/**
 * Hatua 1: Porcupine inasikiliza wake word kwenye simu yenyewe (bila intaneti, betri kidogo).
 * Hatua 2: Wake word ikisikika, Porcupine inasimama, mlio unalia, na SpeechRecognizer
 *          inasikiliza amri MOJA. Kisha CommandHandler inaitekeleza na Porcupine inaanza tena.
 */
class VoiceService : Service() {

    companion object {
        const val PREFS = "cfg"
        const val KEY_ACCESS = "access_key"
        const val KEY_KEYWORD = "keyword"     // jina la BuiltInKeyword, mfano JARVIS
        const val KEY_PPN = "ppn_path"        // njia ya faili la .ppn maalum (hiari)
        const val KEY_LANG = "lang"
        const val ACTION_STOP = "com.msaidizi.app.STOP"
        private const val CHANNEL_ID = "msaidizi"
        private const val NOTIF_ID = 1
        private const val TAG = "Msaidizi"
        private const val COMMAND_TIMEOUT_MS = 9000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var commands: CommandHandler
    private var porcupine: PorcupineManager? = null
    private var recognizer: SpeechRecognizer? = null
    private var language = "sw-TZ"
    private var busy = false   // true wakati tunasubiri amri

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
        language = prefs.getString(KEY_LANG, "sw-TZ")!!
        if (porcupine == null) initPorcupine(prefs.getString(KEY_ACCESS, "")!!,
            prefs.getString(KEY_KEYWORD, "JARVIS")!!,
            prefs.getString(KEY_PPN, "")!!)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        try {
            porcupine?.stop()
            porcupine?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "porcupine cleanup", e)
        }
        porcupine = null
        commands.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Porcupine

    private fun initPorcupine(accessKey: String, keyword: String, ppnPath: String) {
        if (accessKey.isBlank()) {
            fail("Weka AccessKey ya Picovoice kwenye app kwanza")
            return
        }
        try {
            val builder = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setSensitivity(0.7f)   // 0..1; juu = hukosa kidogo lakini makosa ya uongo zaidi
            if (ppnPath.isNotBlank()) builder.setKeywordPath(ppnPath)
            else builder.setKeyword(Porcupine.BuiltInKeyword.valueOf(keyword))

            porcupine = builder
                .setErrorCallback(PorcupineManagerErrorCallback { e ->
                    Log.e(TAG, "Porcupine error", e)
                })
                .build(applicationContext, PorcupineManagerCallback { onWakeWord() })
            porcupine?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Porcupine init failed", e)
            fail("Imeshindwa kuanza wake word: ${e.message}")
        }
    }

    /** Inaitwa kutoka thread ya Porcupine. */
    private fun onWakeWord() {
        handler.post {
            if (busy) return@post
            busy = true
            try {
                porcupine?.stop()          // achia maikrofoni kwa SpeechRecognizer
            } catch (e: Exception) {
                Log.w(TAG, "stop", e)
            }
            beep()
            handler.postDelayed({ listenForCommand() }, 250)
            // Ulinzi: kama hakuna kinachotokea, rudi kusikiliza wake word.
            handler.postDelayed({ if (busy) resumeWakeWord() }, COMMAND_TIMEOUT_MS)
        }
    }

    private fun resumeWakeWord() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        busy = false
        try {
            porcupine?.start()
        } catch (e: Exception) {
            Log.e(TAG, "resume", e)
        }
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
            it.setRecognitionListener(object : RecognitionListener {
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
        commands.speak(message)
        Log.e(TAG, message)
        stopSelf()
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
            .setContentText("Sema neno la kuamsha kisha amri")
            .setContentIntent(open)
            .addAction(0, "Acha", stop)
            .setOngoing(true)
            .build()
    }
}
