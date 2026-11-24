package com.msaidizi.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sauti ya Msaidizi. Ukiweka TTS API (Sauti TTS: POST /api/tts) inatumika; usipoweka, au API ikishindwa,
 * inatumia sauti ya TTS ya simu (Kiswahili, au Kiingereza kama Kiswahili hakipo).
 * Maandishi marefu yanagawanywa vipande; kipande kinachofuata kinaombwa wakati kilichopo kinachezwa.
 */
class Speaker(private val ctx: Context) {

    class Config(val url: String, val key: String, val voice: String) {
        val enabled get() = url.isNotBlank() && key.isNotBlank()

        companion object {
            const val KEY_URL = "tts_url"
            const val KEY_KEY = "tts_key"
            const val KEY_VOICE = "tts_voice"
            const val KEY_LAST_ERROR = "tts_last_error"

            fun load(ctx: Context): Config {
                val p = ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE)
                return Config(
                    p.getString(KEY_URL, "")!!.trim(),
                    p.getString(KEY_KEY, "")!!.trim(),
                    p.getString(KEY_VOICE, "")!!.trim()
                )
            }
        }
    }

    companion object {
        private const val TAG = "Msaidizi"
        private const val API_CHUNK = 450
        private const val SYS_CHUNK = 3000
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile private var gen = 0                 // kila usemi mpya unapata namba mpya; za zamani zinaachwa
    @Volatile var speaking = false
        private set

    private var afterBlock: (() -> Unit)? = null
    private val afterTimeout = Runnable { runAfter() }

    // ---- TTS ya simu
    private lateinit var sys: TextToSpeech
    private var sysReady = false
    private var sysFailed = false
    private var sysPendingText: String? = null
    private var sysPendingGen = 0
    private var sysLastId = ""

    // ---- uchezaji wa API
    private val playlist = ArrayDeque<File>()
    private var player: MediaPlayer? = null
    private var playing = false
    private var fetchDone = false

    init {
        sys = TextToSpeech(ctx) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    val r = sys.setLanguage(Locale("sw", "TZ"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        sys.setLanguage(Locale.US)
                    }
                    sys.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == sysLastId) main.post { finish(utteranceId?.substringBefore('-')?.toIntOrNull() ?: -1) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == sysLastId) main.post { finish(utteranceId?.substringBefore('-')?.toIntOrNull() ?: -1) }
                        }
                    })
                    sysReady = true
                    val t = sysPendingText
                    sysPendingText = null
                    if (t != null && sysPendingGen == gen) speakSystem(t, sysPendingGen)
                } else {
                    sysFailed = true
                    if (sysPendingText != null) {
                        sysPendingText = null
                        finish(gen)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ API ya umma

    /** Inasema [text] (inakatiza kinachosemwa sasa). */
    fun speak(text: String) = onMain {
        cancel()
        val g = ++gen
        if (text.isBlank()) return@onMain
        speaking = true
        val cfg = Config.load(ctx)
        if (cfg.enabled) speakApi(text, cfg, g) else speakSystem(text, g)
    }

    /** Inakatiza usemi na kuita [afterSpeech] iliyosubiri, kama ipo. */
    fun stop() = onMain {
        val wasSpeaking = speaking
        cancel()
        gen++
        if (wasSpeaking) runAfter()
    }

    /** Inaita [block] pale usemi wa sasa ukiisha (au mara moja kama hakuna usemi, au baada ya [maxWaitMs]). */
    fun afterSpeech(maxWaitMs: Long = 10_000, block: () -> Unit) = onMain {
        afterBlock = block
        main.removeCallbacks(afterTimeout)
        if (!speaking) runAfter() else main.postDelayed(afterTimeout, maxWaitMs)
    }

    /** Inajaribu API: inaomba sauti ya "Karibu Tanzania" na kuicheza. [onResult] = null kama imefanikiwa, au ujumbe wa kosa. */
    fun test(onResult: (String?) -> Unit) {
        val cfg = Config.load(ctx)
        if (!cfg.enabled) {
            main.post { onResult("Weka URL na API key kwanza") }
            return
        }
        onMain { cancel(); gen++ }
        exec.execute {
            try {
                val f = fetch("Karibu Tanzania. Sauti ya API inafanya kazi.", cfg)
                main.post {
                    val g = ++gen
                    speaking = true
                    fetchDone = true
                    playlist.add(f)
                    onResult(null)
                    if (!playing) playNext(g)
                }
            } catch (e: Exception) {
                main.post { onResult(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        afterBlock = null
        cancel()
        gen++
        try { sys.stop() } catch (_: Exception) {}
        try { sys.shutdown() } catch (_: Exception) {}
        exec.shutdownNow()
    }

    // ------------------------------------------------------------------ ndani

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun cancel() {
        try { sys.stop() } catch (_: Exception) {}
        sysPendingText = null
        releasePlayer()
        for (f in playlist) f.delete()
        playlist.clear()
        playing = false
        fetchDone = false
        fallbackText = null
        speaking = false
        main.removeCallbacks(afterTimeout)
    }

    private fun releasePlayer() {
        player?.let {
            try { it.setOnCompletionListener(null); it.setOnErrorListener(null); it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }

    private fun finish(g: Int) {
        if (g != gen) return
        speaking = false
        runAfter()
    }

    private fun runAfter() {
        main.removeCallbacks(afterTimeout)
        val b = afterBlock
        afterBlock = null
        b?.invoke()
    }

    // ---- TTS ya simu

    private fun speakSystem(text: String, g: Int) {
        if (g != gen) return
        if (sysFailed) { finish(g); return }
        if (!sysReady) {
            sysPendingText = text
            sysPendingGen = g
            return
        }
        val chunks = split(text, SYS_CHUNK)
        sysLastId = "$g-${chunks.size - 1}"
        chunks.forEachIndexed { i, c ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            sys.speak(c, mode, null, "$g-$i")
        }
    }

    // ---- API

    private fun speakApi(text: String, cfg: Config, g: Int) {
        fetchDone = false
        val chunks = split(text, API_CHUNK)
        exec.execute {
            for ((i, c) in chunks.withIndex()) {
                if (g != gen) return@execute
                try {
                    val f = fetch(c, cfg)
                    main.post {
                        if (g != gen) { f.delete(); return@post }
                        playlist.add(f)
                        if (!playing) playNext(g)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TTS API: ${e.message}")
                    setLastError(e.message ?: e.javaClass.simpleName)
                    // Rudi kwenye sauti ya simu kwa sehemu iliyobaki
                    val rest = chunks.drop(i).joinToString(" ")
                    main.post {
                        if (g != gen) return@post
                        fetchDone = true
                        fallbackText = rest
                        if (!playing && playlist.isEmpty()) {
                            fallbackText = null
                            speakSystem(rest, g)
                        }
                    }
                    return@execute
                }
            }
            main.post { if (g == gen) { fetchDone = true; if (!playing && playlist.isEmpty()) finish(g) } }
        }
    }

    /** Kama API ilishindwa katikati, vipande vilivyopo vinaisha kisha sehemu iliyobaki inasemwa kwa sauti ya simu. */
    private var fallbackText: String? = null

    private fun playNext(g: Int) {
        if (g != gen) return
        val f = playlist.poll()
        if (f == null) {
            playing = false
            val fb = fallbackText
            if (fb != null) {
                fallbackText = null
                speakSystem(fb, g)
            } else if (fetchDone) {
                finish(g)
            }
            return
        }
        playing = true
        try {
            releasePlayer()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { f.delete(); main.post { playNext(g) } }
            mp.setOnErrorListener { _, what, extra ->
                setLastError("Sauti ya API haichezeki (MediaPlayer $what/$extra)")
                f.delete(); main.post { playNext(g) }; true
            }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "play failed", e)
            f.delete()
            main.post { playNext(g) }
        }
    }

    /** POST /api/tts -> faili la sauti kwenye cache. Inatupa Exception yenye ujumbe wa kueleweka ikishindwa. */
    private fun fetch(text: String, cfg: Config): File {
        val body = JSONObject().put("text", text)
        if (cfg.voice.isNotBlank()) body.put("voice", cfg.voice)
        val conn = URL(endpoint(cfg.url)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("X-API-Key", cfg.key)
            conn.setRequestProperty("Authorization", "Bearer ${cfg.key}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "audio/*, application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(160) } catch (_: Exception) { null }
                throw RuntimeException("API $code ${err ?: ""}".trim())
            }
            val type = (conn.contentType ?: "").lowercase()
            var bytes = conn.inputStream.use { it.readBytes() }
            if (type.contains("json")) {
                // Baadhi ya seva zinarudisha JSON yenye audio_base64
                val j = JSONObject(String(bytes, Charsets.UTF_8))
                val b64 = j.optString("audio_base64", j.optString("audio", ""))
                if (b64.isBlank()) throw RuntimeException("API haikurudisha sauti")
                bytes = Base64.decode(b64, Base64.DEFAULT)
            } else if (type.startsWith("text/")) {
                throw RuntimeException("API haikurudisha sauti")
            }
            if (bytes.isEmpty()) throw RuntimeException("API imerudisha faili tupu")
            val f = File.createTempFile("tts", ".audio", ctx.cacheDir)
            f.writeBytes(bytes)
            setLastError("")
            return f
        } finally {
            conn.disconnect()
        }
    }

    private fun setLastError(msg: String) {
        ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(Config.KEY_LAST_ERROR, msg).apply()
    }

    private fun endpoint(raw: String): String {
        var u = raw.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        val path = try { URL(u).path } catch (_: Exception) { "" }
        return if (path.isNullOrEmpty() || path == "/") u.trimEnd('/') + "/api/tts" else u
    }

    /** Inagawanya maandishi vipande visivyozidi [max], kwa kupendelea mwisho wa sentensi au nafasi. */
    private fun split(text: String, max: Int): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val out = ArrayList<String>()
        var s = clean
        while (s.length > max) {
            var cut = s.lastIndexOfAny(charArrayOf('.', '!', '?', ';', '\n'), max - 1)
            if (cut < max / 3) cut = s.lastIndexOf(' ', max - 1)
            if (cut < max / 3) cut = max - 1
            out.add(s.substring(0, cut + 1).trim())
            s = s.substring(cut + 1).trim()
        }
        if (s.isNotEmpty()) out.add(s)
        return out
    }
}
