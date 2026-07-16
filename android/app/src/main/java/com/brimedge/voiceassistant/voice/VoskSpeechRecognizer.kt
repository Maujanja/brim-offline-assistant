package com.brimedge.voiceassistant.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Offline speech recognition powered by Vosk.
 *
 * The caller must ship (or download) a Vosk model into the app's assets under
 * `assets/model-sw/` (or `assets/model-en-us/` as a fallback). Grammar mode is
 * used so only the Phase 1 phrases are recognized — this dramatically improves
 * accuracy on small models.
 */
class VoskSpeechRecognizer(private val context: Context) {

    interface Callback {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onTimeout()
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var callback: Callback? = null

    fun initialize(callback: Callback) {
        this.callback = callback
        LibVosk.setLogLevel(LogLevel.WARNINGS)

        // Try Swahili first, then fall back to English small model.
        StorageService.unpack(context, "model-sw", "model",
            { m -> onModelReady(m) },
            { _ ->
                StorageService.unpack(context, "model-en-us", "model",
                    { m -> onModelReady(m) },
                    { e ->
                        callback.onError("Model haijapatikana. Weka Vosk model kwenye assets/model-sw au model-en-us. (${e.message})")
                    }
                )
            }
        )
    }

    private fun onModelReady(m: Model) {
        model = m
        callback?.onReady()
    }

    fun startListening() {
        val m = model ?: run {
            callback?.onError("Model bado haijawa tayari")
            return
        }
        stopInternal()
        try {
            // Vosk grammar-mode recognizer: pass phrase list as JSON array string.
            val phraseArray = org.json.JSONArray(CommandParser.GRAMMAR + listOf("[unk]"))
            val recognizer = Recognizer(m, 16000.0f, phraseArray.toString())
            val service = SpeechService(recognizer, 16000.0f)
            service.startListening(voskListener)
            speechService = service
        } catch (e: IOException) {
            callback?.onError("Imeshindikana kuanza kusikiliza: ${e.message}")
        }
    }

    fun stopListening() {
        stopInternal()
    }

    private fun stopInternal() {
        speechService?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.shutdown() } catch (_: Exception) {}
        }
        speechService = null
    }

    fun destroy() {
        stopInternal()
        model?.close()
        model = null
    }

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = extract(hypothesis, "partial")
            if (!text.isNullOrBlank()) callback?.onPartial(text)
        }

        override fun onResult(hypothesis: String?) {
            val text = extract(hypothesis, "text")
            if (!text.isNullOrBlank()) callback?.onFinal(text)
        }

        override fun onFinalResult(hypothesis: String?) {
            val text = extract(hypothesis, "text")
            if (!text.isNullOrBlank()) callback?.onFinal(text)
        }

        override fun onError(exception: Exception?) {
            callback?.onError(exception?.message ?: "Hitilafu ya kusikiliza")
        }

        override fun onTimeout() {
            callback?.onTimeout()
        }
    }

    private fun extract(json: String?, key: String): String? {
        return try {
            if (json.isNullOrBlank()) null else JSONObject(json).optString(key, "").ifBlank { null }
        } catch (e: Exception) {
            Log.w("Vosk", "parse failed", e); null
        }
    }
}
