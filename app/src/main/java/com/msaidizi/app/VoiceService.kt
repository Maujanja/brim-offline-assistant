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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Hatua 2: Kikisikika, Vosk inasimama, mlio unalia, na [SpeechInput] inasikiliza amri MOJA
 *          (utambuzi wa simu, au API yako ya STT ukiiweka).
 * Hatua 3: Amri ikihitaji ujumbe (SMS), Msaidizi anasema "Sema ujumbe wako", mlio, na kusikiliza ujumbe;
 *          kisha CommandHandler inautuma. Mwishoni Vosk inaanza kusikiliza tena.
 *
 * Wake word lazima iwe maneno ya Kiingereza (modeli ndogo ya Vosk ya Kiingereza), mfano "hey phone".
 */
class VoiceService : Service() {

    companion object {
        const val PREFS = "cfg"
        const val KEY_WAKE = "wake"
        const val KEY_LANG = "lang"
        const val KEY_STT_URL = "stt_url"
        const val KEY_STT_KEY = "stt_key"
        const val KEY_STT_MODEL = "stt_model"
        const val KEY_STT_CMD = "stt_cmd"
        const val DEFAULT_WAKE = "hey phone"
        const val ACTION_STOP = "com.msaidizi.app.STOP"
        private const val CHANNEL_ID = "msaidizi"
        private const val NOTIF_ID = 1
        private const val TAG = "Msaidizi"
        private const val COMMAND_TIMEOUT_MS = 50_000L
        private const val MIN_CONF = 0.7
        private const val COMMAND_WAIT_MS = 12_000L     // muda wa kuanza kuongea baada ya mlio
        private const val DICTATION_WAIT_MS = 15_000L
        private const val DICTATION_TIMEOUT_MS = 75_000L
        private const val MODEL_ASSET = "model-en"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var commands: CommandHandler
    private lateinit var input: SpeechInput
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var wakePhrase = DEFAULT_WAKE
    private var language = "sw-TZ"
    private var busy = false          // true wakati tunasubiri/tunatekeleza amri
    private val guard = Runnable { if (busy) resumeWakeWord() }

    /** Ulinzi: kama hatua ikikwama, rudi kusikiliza wake word baada ya [ms]. */
    private fun armGuard(ms: Long) {
        handler.removeCallbacks(guard)
        handler.postDelayed(guard, ms)
    }
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        commands = CommandHandler(this)
        input = SpeechInput(this)
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
        input.cancel()
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
            // Utambuzi huru (bila grammar): maneno mengine kama "hey pen" yanatambuliwa jinsi yalivyo,
            // hayalazimishwi kuwa wake word. Kila neno linakuja na alama ya uhakika (conf).
            val rec = Recognizer(m, 16000.0f)
            rec.setWords(true)
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
        // Tunatumia matokeo ya mwisho tu (yana alama ya uhakika); ya muda mfupi (partial) yanakosea mara nyingi.
        override fun onPartialResult(hypothesis: String?) {}
        override fun onFinalResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) {
            val obj = try {
                JSONObject(hypothesis ?: return)
            } catch (_: Exception) {
                return
            }
            val text = obj.optString("text", "")
            if (text.isNotBlank() && containsWake(text, obj.optJSONArray("result"))) {
                handler.post { onWakeWord() }
            }
        }

        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk error", exception)
            handler.post { if (!busy && !destroyed) startWakeListening() }
        }

        override fun onTimeout() {}
    }

    /** Wake word inakubalika tu ikiwa maneno yote yamesikika kwa mfuatano, kwa uhakika wa kutosha. */
    private fun containsWake(text: String, words: JSONArray?): Boolean {
        val target = wakePhrase.split(" ").filter { it.isNotBlank() }
        if (target.isEmpty()) return false
        val heard = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        outer@ for (start in 0..(heard.size - target.size)) {
            for (i in target.indices) {
                if (!sameWord(heard[start + i], target[i])) continue@outer
            }
            if (words != null) {
                for (i in target.indices) {
                    val conf = words.optJSONObject(start + i)?.optDouble("conf", 1.0) ?: 1.0
                    if (conf < MIN_CONF) continue@outer
                }
            }
            return true
        }
        return false
    }

    /** Sawa kabisa; "hay" kwa "hey"; na makosa ya herufi moja kwa maneno marefu (6+) kama "computer". */
    private fun sameWord(heard: String, target: String): Boolean {
        if (heard == target) return true
        if (target == "hey" && heard == "hay") return true
        return target.length >= 6 && editDistance(heard, target) <= 1
    }

    private fun editDistance(a: String, b: String): Int {
        if (Math.abs(a.length - b.length) > 1) return 2
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            }
        }
        return d[a.length][b.length]
    }

    private fun onWakeWord() {
        if (busy || destroyed) return
        busy = true
        stopWakeListening()           // achia maikrofoni kwa hatua inayofuata
        commands.music.pauseForCommand()
        beep()
        handler.postDelayed({ listenForCommand() }, 250)
        armGuard(COMMAND_TIMEOUT_MS)
    }

    private fun resumeWakeWord() {
        handler.removeCallbacksAndMessages(null)
        input.cancel()
        busy = false
        commands.music.resumeAfterCommand()
        if (!destroyed) startWakeListening()
    }

    // ------------------------------------------------------------ amri

    private fun apiLang() = if (language.startsWith("sw")) "sw" else "en"

    /** [waitMs] = muda wa kusubiri uanze kuongea kabla ya kukata tamaa. */
    private fun listen(forCommand: Boolean, waitMs: Long, cb: (String?, String?) -> Unit) {
        val cfg = SpeechInput.Config.load(this)
        val useApi = cfg.enabled && (!forCommand || cfg.forCommands)
        if (useApi) input.listenApi(cfg, apiLang(), waitMs, cb) else input.listenBuiltIn(language, waitMs, cb)
    }

    private fun listenForCommand() {
        armGuard(COMMAND_TIMEOUT_MS)
        listen(forCommand = true, waitMs = COMMAND_WAIT_MS) { text, err -> handleCommand(text, err) }
    }

    private fun handleCommand(text: String?, err: String?) {
        handler.removeCallbacksAndMessages(null)
        if (text.isNullOrBlank()) {
            if (err != null) Log.w(TAG, "command stt: $err")
            if (err != null && err.startsWith("API")) commands.speak("API ya STT imeshindwa")
            handler.postDelayed({ resumeWakeWord() }, 1500)
            return
        }
        commands.execute(text)
        val dictation = commands.takeDictation()
        if (dictation == null) {
            // subiri TTS imalize ili isijisikie yenyewe
            handler.postDelayed({ resumeWakeWord() }, 2200)
            return
        }
        armGuard(DICTATION_TIMEOUT_MS)
        commands.afterSpeech {
            beep()
            handler.postDelayed({ dictate(dictation) }, 300)
        }
    }

    /** Inasikiliza ujumbe mzima (API ya STT ukiiweka, vinginevyo utambuzi wa simu) kisha inaupa [d]. */
    private fun dictate(d: CommandHandler.Dictation) {
        listen(forCommand = false, waitMs = DICTATION_WAIT_MS) { text, err ->
            handler.removeCallbacksAndMessages(null)
            if (text.isNullOrBlank()) {
                Log.w(TAG, "dictation stt: $err")
                commands.speak(if (err != null && err.startsWith("API")) "API ya STT imeshindwa" else "Sikusikia ujumbe wowote")
            } else {
                commands.speak(d.onText(text))
            }
            handler.postDelayed({ resumeWakeWord() }, 4000)
        }
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
