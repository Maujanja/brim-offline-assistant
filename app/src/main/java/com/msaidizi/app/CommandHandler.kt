package com.msaidizi.app

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hutafsiri maneno ya amri kuwa vitendo vya simu. Ongeza amri mpya ndani ya execute(). */
class CommandHandler(private val ctx: Context) {

    private val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var ttsReady = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale("sw", "TZ"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US)
                }
                ttsReady = true
            }
        }
    }

    private val torchId: String? by lazy {
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msaidizi")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    // ------------------------------------------------------------------ amri

    fun execute(raw: String) {
        val t = raw.lowercase(Locale.ROOT).trim()
        val reply: String = try {
            when {
                // Tochi
                has(t, "tochi", "flashlight", "torch", "flash") ->
                    setTorch(!has(t, "zima", "zimisha", "funga", "off", "close"))

                // Kamera
                has(t, "kamera", "camera", "picha", "selfie") -> openCamera(has(t, "video", "vidio"))

                // Sauti
                has(t, "kimya", "mute", "silent") -> setVolumeMute()
                has(t, "volume", "sauti") -> changeVolume(t)

                // Mtandao / Bluetooth (Android 10+ hairuhusu kuzima/kuwasha moja kwa moja)
                has(t, "wifi", "wai fai", "intaneti", "internet") -> openWifiPanel()
                has(t, "bluetooth", "blututh") -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")

                // Mipangilio
                has(t, "mipangilio", "settings") -> openSettings(Settings.ACTION_SETTINGS, "mipangilio")

                // Muda
                has(t, "saa ngapi", "muda", "what time", "time is it") -> tellTime()

                // Kupiga simu (inafungua dialer na namba, mtumiaji anabonyeza kupiga)
                has(t, "piga", "call", "dial") -> dial(t)

                // Kufungua app yoyote kwa jina
                has(t, "fungua", "open", "anzisha", "launch") -> openApp(t)

                else -> "Samahani, sijaelewa amri hiyo"
            }
        } catch (e: SecurityException) {
            "Sina ruhusa ya kufanya hilo"
        } catch (e: Exception) {
            "Kuna hitilafu imetokea"
        }
        speak(reply)
    }

    private fun has(text: String, vararg keys: String) = keys.any { text.contains(it) }

    // ------------------------------------------------------------------ vitendo

    private fun setTorch(on: Boolean): String {
        val id = torchId ?: return "Simu hii haina tochi"
        cameraManager.setTorchMode(id, on)
        return if (on) "Tochi imewashwa" else "Tochi imezimwa"
    }

    private fun openCamera(video: Boolean): String {
        val action = if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE
        startActivity(Intent(action))
        return "Nafungua kamera"
    }

    private fun changeVolume(t: String): String {
        val dir = when {
            has(t, "juu", "ongeza", "up", "increase", "louder") -> AudioManager.ADJUST_RAISE
            has(t, "chini", "punguza", "down", "decrease", "lower") -> AudioManager.ADJUST_LOWER
            else -> return "Sema volume juu au chini"
        }
        repeat(3) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
        return if (dir == AudioManager.ADJUST_RAISE) "Sauti imeongezwa" else "Sauti imepunguzwa"
    }

    private fun setVolumeMute(): String {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        return "Sauti imenyamazishwa"
    }

    private fun openWifiPanel(): String {
        val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        else Settings.ACTION_WIFI_SETTINGS
        startActivity(Intent(action))
        return "Nafungua mipangilio ya mtandao"
    }

    private fun openSettings(action: String, name: String): String {
        startActivity(Intent(action))
        return "Nafungua $name"
    }

    private fun tellTime(): String {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return "Saa ni $now"
    }

    private fun dial(t: String): String {
        val number = t.filter { it.isDigit() || it == '+' }
        if (number.length < 3) return "Sema namba ya simu, mfano piga 0712345678"
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        return "Nafungua simu kwa namba $number"
    }

    private fun openApp(t: String): String {
        val name = t
            .replace(Regex("\\b(fungua|open|anzisha|launch|app|programu)\\b"), "")
            .trim()
        if (name.isEmpty()) return "Sema jina la app"

        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            label.contains(name) || name.contains(label)
        } ?: return "Sijapata app inayoitwa $name"

        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "Siwezi kufungua $name"
        startActivity(intent)
        return "Nafungua $name"
    }

    private fun startActivity(intent: Intent) {
        // Inafanya kazi kutoka huduma ya nyuma tu ukiruhusu "Display over other apps".
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
