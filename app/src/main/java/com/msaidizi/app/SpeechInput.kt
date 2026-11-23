package com.msaidizi.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Inasikiliza sauti MOJA na kuibadilisha kuwa maandishi. Njia mbili:
 *  - listenBuiltIn: utambuzi wa sauti wa simu. Mlio wa mfumo wa kuanza/kuisha unanyamazishwa wakati wa kusikiliza,
 *    ili kuanza upya kimya kimya kusiwe na mlio unaojirudia.
 *  - listenApi: inarekodi sauti kimya kimya (WAV) na kuituma kwa API yako ya STT (POST /api/stt, multipart "file").
 * Callback inaitwa mara moja tu, kwenye thread kuu: (maandishi, kosa).
 */
class SpeechInput(private val ctx: Context) {

    data class Config(val url: String, val key: String, val forCommands: Boolean) {
        val enabled: Boolean get() = url.isNotBlank() && key.isNotBlank()

        companion object {
            fun load(ctx: Context): Config {
                val p = ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE)
                return Config(
                    url = p.getString(VoiceService.KEY_STT_URL, "")!!.trim(),
                    key = p.getString(VoiceService.KEY_STT_KEY, "")!!.trim(),
                    forCommands = p.getBoolean(VoiceService.KEY_STT_CMD, false)
                )
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mutedStreams = ArrayList<Int>()
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var token = 0

    fun cancel() {
        token++
        recognizer?.destroy()
        recognizer = null
        unmuteBeeps()
    }

    // ------------------------------------------------------------ mlio wa mfumo

    /** Nyamazisha mlio wa kuanza/kuisha wa kitambua sauti (ni zile tu ambazo hazikuwa zimenyamazishwa tayari). */
    private fun muteBeeps() {
        for (s in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!audio.isStreamMute(s)) {
                    audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(s)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun unmuteBeeps() {
        for (s in mutedStreams) {
            try {
                audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {
            }
        }
        mutedStreams.clear()
    }

    // ------------------------------------------------------------ built-in

    /**
     * Utambuzi wa simu. Google inakata haraka ukichelewa kuanza kuongea, kwa hiyo tukipata "kimya/hakuna maneno"
     * tunaanza kusikiliza tena (kimya kimya), hadi [waitMs] zipite tangu mwanzo.
     */
    fun listenBuiltIn(lang: String, waitMs: Long, onResult: (String?, String?) -> Unit) {
        cancel()
        val my = token
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onResult(null, "Huduma ya kutambua sauti haipatikani kwenye simu hii")
            return
        }
        muteBeeps()
        val finish: (String?, String?) -> Unit = { text, err ->
            unmuteBeeps()
            onResult(text, err)
        }
        startBuiltIn(my, lang, SystemClock.elapsedRealtime() + waitMs, finish)
    }

    private fun startBuiltIn(my: Int, lang: String, deadline: Long, finish: (String?, String?) -> Unit) {
        if (my != token) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (my != token) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish(text, null)
            }

            override fun onError(error: Int) {
                if (my != token) return
                val noSpeech = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_NO_MATCH
                if (noSpeech && deadline - SystemClock.elapsedRealtime() > 2000) {
                    handler.postDelayed({ startBuiltIn(my, lang, deadline, finish) }, 100)
                    return
                }
                finish(null, "Sikusikia vizuri (kosa $error)")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        r.startListening(intent)
    }

    // ------------------------------------------------------------ API yako ya STT

    /**
     * Inarekodi (kimya kimya, bila mlio wowote) hadi ukae kimya, au [waitMs] zipite bila kuongea,
     * kisha inatuma WAV kwa API. [lang] mfano "sw-TZ" au "en-US".
     */
    fun listenApi(cfg: Config, lang: String, waitMs: Long, onResult: (String?, String?) -> Unit) {
        cancel()
        val my = token
        Thread {
            var text: String? = null
            var error: String? = null
            try {
                val wav = recordWav(my, waitMs, waitMs + 25_000)
                if (my != token) return@Thread
                if (wav == null) error = "Sikusikia sauti yoyote" else text = upload(wav, cfg, lang)
            } catch (e: Exception) {
                error = e.message ?: "API ya STT imeshindwa"
            }
            val t = text
            val e = error
            handler.post {
                if (my == token) {
                    if (t.isNullOrBlank() && e == null) onResult(null, "API haikurudisha maandishi")
                    else onResult(t, e)
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun recordWav(my: Int, waitMs: Long, maxMs: Long): ByteArray? {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var rec: AudioRecord? = null
        // Maikrofoni inaweza kuchelewa kuachiliwa na Vosk: jaribu mara chache
        for (attempt in 0 until 8) {
            if (my != token) return null
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate)
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r
                break
            }
            r.release()
            Thread.sleep(250)
        }
        val r = rec ?: throw IOException("Maikrofoni haipatikani")

        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(rate / 10)               // vipande vya 100 ms
        val startAt = SystemClock.elapsedRealtime()
        var lastLoud = startAt
        var speech = false
        try {
            r.startRecording()
            while (my == token) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) throw IOException("Imeshindwa kusoma maikrofoni")
                if (n == 0) continue
                var peak = 0
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                    pcm.write(v and 0xff)
                    pcm.write((v shr 8) and 0xff)
                }
                val now = SystemClock.elapsedRealtime()
                if (peak > 1200) {
                    speech = true
                    lastLoud = now
                }
                if ((speech && now - lastLoud > 1800) ||
                    (!speech && now - startAt > waitMs) ||
                    now - startAt > maxMs
                ) break
            }
        } finally {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        if (my != token || !speech) return null
        return wavOf(pcm.toByteArray(), rate)
    }

    private fun wavOf(pcm: ByteArray, rate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())                  // PCM
        header.putShort(1.toShort())                  // mono
        header.putInt(rate)
        header.putInt(rate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        val out = ByteArrayOutputStream(44 + pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    /** Ukiweka anwani ya seva tu (mfano https://host), tunaongeza "/api/stt". */
    private fun endpoint(raw: String): String {
        var u = raw.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        val path = try { URL(u).path } catch (_: Exception) { "" }
        return if (path.isNullOrEmpty() || path == "/") u.trimEnd('/') + "/api/stt" else u
    }

    private fun upload(wav: ByteArray, cfg: Config, lang: String): String {
        val boundary = "----msaidizi" + System.currentTimeMillis()
        val conn = URL(endpoint(cfg.url)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setChunkedStreamingMode(0)
        conn.setRequestProperty("x-api-key", cfg.key)
        conn.setRequestProperty("Authorization", "Bearer ${cfg.key}")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        DataOutputStream(conn.outputStream).use { out ->
            if (lang.isNotBlank()) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n$lang\r\n")
            }
            out.writeBytes(
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n"
            )
            out.write(wav)
            out.writeBytes("\r\n--$boundary--\r\n")
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IOException("API: HTTP $code ${body.take(140)}")
        return parseText(body)
    }

    /** Jibu la API halijatajwa kwenye doc, kwa hiyo tunakubali "text", "transcript", n.k. au maandishi matupu. */
    private fun parseText(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed
        val obj = JSONObject(trimmed)
        findText(obj)?.let { return it }
        throw IOException("API: jibu halina maandishi: ${trimmed.take(120)}")
    }

    private fun findText(obj: JSONObject): String? {
        for (k in listOf("text", "transcript", "transcription", "stt", "result", "data")) {
            when (val v = obj.opt(k)) {
                is String -> if (v.isNotBlank()) return v.trim()
                is JSONObject -> findText(v)?.let { return it }
            }
        }
        return null
    }
}
