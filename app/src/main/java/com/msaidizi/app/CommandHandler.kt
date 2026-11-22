package com.msaidizi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
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
        return opened(Intent(action), "kamera")
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
        return opened(Intent(action), "mipangilio ya mtandao")
    }

    private fun openSettings(action: String, name: String): String {
        return opened(Intent(action), name)
    }

    private fun tellTime(): String {
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return "Saa ni $now"
    }

    private fun dial(t: String): String {
        val number = t.filter { it.isDigit() || it == '+' }
        if (number.length < 3) return "Sema namba ya simu, mfano piga 0712345678"
        return opened(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), "simu kwa namba $number")
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
        return opened(intent, name)
    }

    /** Inafungua moja kwa moja, au (simu isiyo na overlay) inatuma arifa ya kugusa. Inarudisha ujumbe wa kusema. */
    private fun opened(intent: Intent, label: String): String =
        if (launch(intent, label)) "Nafungua $label" else "Gusa arifa ili kufungua $label"

    /**
     * Android 10+ hairuhusu huduma ya nyuma kufungua skrini. Njia mbili:
     *  1) Ukiwa na "Display over other apps" -> inafungua moja kwa moja.
     *  2) Vinginevyo (mfano simu za Android Go) -> arifa yenye full-screen intent: skrini ikiwa imefungwa
     *     inafunguka yenyewe, ikiwa wazi unagusa arifa.
     * Inarudisha true ikifunguliwa moja kwa moja.
     */
    private fun launch(intent: Intent, label: String): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val direct = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)
        if (direct) {
            ctx.startActivity(intent)
            return true
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(ACTION_CHANNEL, "Vitendo vya Msaidizi", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            ctx, label.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, ACTION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Gusa kufungua $label")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .build()
        nm.notify(ACTION_NOTIF_ID, n)
        return false
    }

    private companion object {
        const val ACTION_CHANNEL = "msaidizi_actions"
        const val ACTION_NOTIF_ID = 2
    }
}
