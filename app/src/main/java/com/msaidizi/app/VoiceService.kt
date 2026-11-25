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
import android.os.PowerManager
import android.os.SystemClock
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
        const val KEY_STT_CMD = "stt_cmd"
        const val KEY_MSG_ALERTS = "msg_alerts"
        @Volatile var instance: VoiceService? = null
        const val DEFAULT_WAKE = "hey phone"
        const val ACTION_STOP = "com.msaidizi.app.STOP"
        const val ACTION_SILENCE = "com.msaidizi.app.SILENCE"
        private const val CHANNEL_ID = "msaidizi"
        private const val NOTIF_ID = 1
        private const val TAG = "Msaidizi"
        private const val COMMAND_TIMEOUT_MS = 50_000L
        private const val REPLY_WAIT_MS = 15_000L        // muda wa kujibu "Nikusomee?"
        private val YES_WORDS = setOf("ndiyo", "ndio", "soma", "nisomee", "sawa", "yes", "yeah", "read", "ok", "okay", "naam", "tafadhali", "please", "ee", "eeh")
        private val NO_WORDS = setOf("hapana", "no", "nope", "usisome", "baadaye", "sitaki", "acha")
        private const val COMMAND_WAIT_MS = 12_000L     // muda wa kuanza kuongea baada ya mlio
        private const val DICTATION_WAIT_MS = 15_000L
        private const val DICTATION_TIMEOUT_MS = 75_000L
        private const val MODEL_ASSET = "model-en"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayList<IncomingMessage>()   // ujumbe uliofika wakati Msaidizi ana shughuli
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
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastHeard = 0L
    private val watchHandler = Handler(Looper.getMainLooper())

    /** Mlinzi: Vosk ikinyamaza (maikrofoni ikichukuliwa na app nyingine, au mfumo ukaikatisha), anza upya. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (!busy && model != null && SystemClock.elapsedRealtime() - lastHeard > 20_000) {
                Log.w(TAG, "wake listening ilikwama, naanza upya")
                startWakeListening()
            }
            watchHandler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        commands = CommandHandler(this)
        try {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "msaidizi:listen").apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "wakelock", e)
        }
        watchHandler.postDelayed(watchdog, 15_000)
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
        if (intent?.action == ACTION_SILENCE) {
            commands.stopSpeaking()          // inakatiza usomaji; afterSpeech inarudisha wake word
            return START_STICKY
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        wakePhrase = prefs.getString(KEY_WAKE, DEFAULT_WAKE)!!.lowercase().trim().ifBlank { DEFAULT_WAKE }
        language = prefs.getString(KEY_LANG, "sw-TZ")!!
        if (model == null) loadModel() else if (speech == null && !busy) startWakeListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        watchHandler.removeCallbacksAndMessages(null)
        try { wakeLock?.release() } catch (_: Exception) {}
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
            // Grammar ndogo: kifungu cha wake word tu + "[unk]" kwa kila kingine. Haraka na nyeti.
            val grammar = JSONArray().put(wakePhrase).put("[unk]").toString()
            val rec = Recognizer(m, 16000.0f, grammar)
            val svc = SpeechService(rec, 16000.0f)
            svc.startListening(voskListener)
            lastHeard = SystemClock.elapsedRealtime()
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
        override fun onPartialResult(hypothesis: String?) { lastHeard = SystemClock.elapsedRealtime(); check(hypothesis, "partial") }
        override fun onResult(hypothesis: String?) { lastHeard = SystemClock.elapsedRealtime(); check(hypothesis, "text") }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk error", exception)
            handler.post { if (!busy && !destroyed) startWakeListening() }
        }
        override fun onTimeout() {
            handler.post { if (!busy && !destroyed) startWakeListening() }
        }

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
        Trace.set(this, "wake word imesikika")
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
        if (pending.isNotEmpty()) {
            handler.postDelayed({
                if (!busy && !destroyed && pending.isNotEmpty()) startAnnouncement(pending.removeAt(0))
            }, 1500)
        }
    }

    // ------------------------------------------------------------ amri

    private fun apiLang() = language

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
            Trace.set(this, "sikusikia amri (${err ?: "hakuna sauti"})")
            handler.postDelayed({ resumeWakeWord() }, 1500)
            return
        }
        Trace.set(this, "nimesikia: \"$text\"")
        armGuard(COMMAND_TIMEOUT_MS)       // AI inaweza kuchukua sekunde chache
        commands.execute(text) { afterCommand() }
    }

    private fun afterCommand() {
        if (destroyed || !busy) return
        handler.removeCallbacksAndMessages(null)
        val dictation = commands.takeDictation()
        if (dictation == null) {
            // subiri usemi uishe (unaweza kuwa mrefu, mfano kusoma skrini) ili wake word isijisikie yenyewe
            val wait = 15_000L + commands.lastReplyChars * 100L
            armGuard(wait + 5_000)
            commands.afterSpeech(maxWaitMs = wait) {
                handler.postDelayed({ resumeWakeWord() }, 500)
            }
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
                // kimya: hakuna ujumbe uliosikika
            } else {
                Log.i(TAG, "dictation: ${d.onText(text)}")
            }
            handler.postDelayed({ resumeWakeWord() }, 4000)
        }
    }

    // ------------------------------------------------------------ ujumbe mpya (SMS / WhatsApp)

    /** Inaitwa na MessageListener. Inauliza kama usomewe ujumbe, kisha inasubiri jibu lako sekunde 15. */
    fun onIncomingMessage(m: IncomingMessage) {
        handler.post {
            if (destroyed) return@post
            if (busy) {
                pending.removeAll { it.key == m.key }
                pending.add(m)
            } else {
                startAnnouncement(m)
            }
        }
    }

    private fun startAnnouncement(m: IncomingMessage) {
        busy = true
        stopWakeListening()
        commands.music.pauseForCommand()
        armGuard(60_000)
        commands.speak("Kuna ujumbe wa ${m.app} kutoka kwa ${m.sender}. Nikusomee?")
        commands.afterSpeech {
            beep()
            handler.postDelayed({ listenForReply(m) }, 250)
        }
    }

    private fun listenForReply(m: IncomingMessage) {
        armGuard(REPLY_WAIT_MS + 30_000)
        listen(forCommand = true, waitMs = REPLY_WAIT_MS) { text, _ -> handleReply(m, text) }
    }

    private fun handleReply(m: IncomingMessage, text: String?) {
        handler.removeCallbacksAndMessages(null)
        val words = (text ?: "").lowercase().split(Regex("[^\\p{L}0-9]+")).filter { it.isNotBlank() }
        when {
            words.any { it in NO_WORDS } -> {
                resumeWakeWord()
            }
            words.any { it in YES_WORDS } -> {
                commands.speak(MessageNotifier.spokenText(m))
                armGuard(90_000)
                commands.afterSpeech(maxWaitMs = 80_000) { resumeWakeWord() }
            }
            else -> {
                // Hakuna jibu ndani ya sekunde 15: weka arifa ya kugusa-kusoma
                MessageNotifier.post(this, m)
                resumeWakeWord()
            }
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
        val silence = PendingIntent.getService(
            this, 2,
            Intent(this, VoiceService::class.java).setAction(ACTION_SILENCE),
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
            .addAction(0, "Nyamaza", silence)
            .addAction(0, "Acha", stop)
            .setOngoing(true)
            .build()
    }
}
