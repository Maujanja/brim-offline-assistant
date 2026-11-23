package com.msaidizi.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Inasikiliza sauti MOJA na kuibadilisha kuwa maandishi. Njia mbili:
 *  - listenBuiltIn: utambuzi wa sauti wa simu (bila mipangilio).
 *  - listenApi: inarekodi sauti na kuituma kwa API ya STT inayolingana na OpenAI ("/audio/transcriptions",
 *    mfano OpenAI Whisper au Groq). Inafaa zaidi kwa Kiswahili.
 * Callback inaitwa mara moja tu, kwenye thread kuu: (maandishi, kosa).
 */
class SpeechInput(private val ctx: Context) {

    data class Config(val url: String, val key: String, val model: String, val forCommands: Boolean) {
        val enabled: Boolean get() = url.isNotBlank() && key.isNotBlank()

        companion object {
            fun load(ctx: Context): Config {
                val p = ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE)
                return Config(
                    url = p.getString(VoiceService.KEY_STT_URL, "")!!.trim(),
                    key = p.getString(VoiceService.KEY_STT_KEY, "")!!.trim(),
                    model = p.getString(VoiceService.KEY_STT_MODEL, "whisper-1")!!.trim().ifBlank { "whisper-1" },
                    forCommands = p.getBoolean(VoiceService.KEY_STT_CMD, false)
                )
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null
    private var token = 0

    fun cancel() {
        token++
        recognizer?.destroy()
        recognizer = null
        stopRecorder()
    }

    // ------------------------------------------------------------ built-in

    fun listenBuiltIn(lang: String, onResult: (String?, String?) -> Unit) {
        cancel()
        val my = token
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onResult(null, "Huduma ya kutambua sauti haipatikani kwenye simu hii")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (my != token) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onResult(text, null)
            }

            override fun onError(error: Int) {
                if (my != token) return
                onResult(null, "Sikusikia vizuri (kosa $error)")
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
        }
        r.startListening(intent)
    }

    // ------------------------------------------------------------ API

    fun listenApi(cfg: Config, lang: String, onResult: (String?, String?) -> Unit) {
        cancel()
        val my = token
        recordNote(my) { file, err ->
            if (my != token) return@recordNote
            if (file == null) {
                onResult(null, err)
                return@recordNote
            }
            Thread {
                var text: String? = null
                var error: String? = null
                try {
                    text = upload(file, cfg, lang)
                } catch (e: Exception) {
                    error = e.message ?: "API ya STT imeshindwa"
                } finally {
                    file.delete()
                }
                handler.post {
                    if (my == token) {
                        if (text.isNullOrBlank() && error == null) onResult(null, "API haikurudisha maandishi")
                        else onResult(text, error)
                    }
                }
            }.start()
        }
    }

    private fun recordNote(my: Int, done: (File?, String?) -> Unit) {
        val file = File(ctx.cacheDir, "note-$my.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(48000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            try { rec.release() } catch (_: Exception) {}
            done(null, "Imeshindwa kurekodi: ${e.message}")
            return
        }
        recorder = rec

        val startAt = SystemClock.elapsedRealtime()
        var speechStarted = false
        var lastLoud = startAt
        val poll = object : Runnable {
            override fun run() {
                if (my != token || recorder !== rec) return          // imesitishwa
                val now = SystemClock.elapsedRealtime()
                val amp = try { rec.maxAmplitude } catch (_: Exception) { 0 }
                if (amp > 1200) {
                    speechStarted = true
                    lastLoud = now
                }
                val finished = (speechStarted && now - lastLoud > 1800) ||
                    (!speechStarted && now - startAt > 7000) ||
                    now - startAt > 30000
                if (finished) {
                    stopRecorder()
                    if (speechStarted) done(file, null) else done(null, "Sikusikia sauti yoyote")
                } else {
                    handler.postDelayed(this, 150)
                }
            }
        }
        handler.postDelayed(poll, 400)
    }

    private fun stopRecorder() {
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        recorder = null
    }

    private fun upload(file: File, cfg: Config, lang: String): String {
        val boundary = "----msaidizi" + System.currentTimeMillis()
        val conn = URL(cfg.url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setChunkedStreamingMode(0)
        conn.setRequestProperty("Authorization", "Bearer ${cfg.key}")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        DataOutputStream(conn.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("model", cfg.model)
            if (lang.isNotBlank()) field("language", lang)
            field("response_format", "json")
            out.writeBytes(
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.m4a\"\r\n" +
                    "Content-Type: audio/mp4\r\n\r\n"
            )
            file.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IOException("API: HTTP $code ${body.take(140)}")
        return JSONObject(body).optString("text").trim()
    }
}
