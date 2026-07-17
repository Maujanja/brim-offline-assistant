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
 * Language-agnostic offline speech recognizer. The active [LanguageProfile]
 * (from [LanguageManager]) decides which Vosk model to unpack from assets
 * and which grammar to constrain recognition to. Adding a new language does
 * NOT require editing this file.
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
    private var loadedProfileCode: String? = null
    private var speechService: SpeechService? = null
    private var callback: Callback? = null

    fun initialize(callback: Callback) {
        this.callback = callback
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        loadActiveProfile()
    }

    private fun loadActiveProfile() {
        val profile = LanguageManager.current()
        val cb = callback ?: return
        // Unpack from `assets/<modelAssetPath>/` into the app's private storage
        // under the folder name "model-<code>". Vosk requires an unpacked
        // directory on disk — assets stay compressed in the APK.
        StorageService.unpack(
            context,
            profile.modelAssetPath,
            "model-${profile.code}",
            { m ->
                model?.close()
                model = m
                loadedProfileCode = profile.code
                cb.onReady()
            },
            { e ->
                cb.onError(
                    "Speech model for ${profile.displayName} could not be loaded. " +
                            "Expected assets/${profile.modelAssetPath}/. (${e.message})"
                )
            }
        )
    }

    fun startListening() {
        val cb = callback ?: return
        // Reload the model if the active language changed while we were idle.
        if (loadedProfileCode != LanguageManager.current().code) {
            loadActiveProfile()
            cb.onError("Language switched — reloading model, try again in a moment.")
            return
        }
        val m = model ?: run {
            cb.onError("Speech model not ready yet")
            return
        }
        stopInternal()
        try {
            val phraseArray = org.json.JSONArray(LanguageManager.current().grammar)
            val recognizer = Recognizer(m, 16000.0f, phraseArray.toString())
            val service = SpeechService(recognizer, 16000.0f)
            service.startListening(voskListener)
            speechService = service
        } catch (e: IOException) {
            cb.onError("Could not start listening: ${e.message}")
        }
    }

    fun stopListening() { stopInternal() }

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
        loadedProfileCode = null
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
            callback?.onError(exception?.message ?: "Recognition error")
        }
        override fun onTimeout() { callback?.onTimeout() }
    }

    private fun extract(json: String?, key: String): String? = try {
        if (json.isNullOrBlank()) null else JSONObject(json).optString(key, "").ifBlank { null }
    } catch (e: Exception) {
        Log.w("Vosk", "parse failed", e); null
    }
}
