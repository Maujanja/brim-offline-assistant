package com.msaidizi.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hutafsiri maneno ya amri kuwa vitendo vya simu. Ongeza amri mpya ndani ya execute(). */
class CommandHandler(private val ctx: Context) {

    /** Amri inayohitaji kusikiliza sauti ya pili (mfano ujumbe wa SMS). Huduma ndiyo inayosikiliza. */
    class Dictation(val onText: (String) -> String)

    val music = MusicPlayer(ctx)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var ttsReady = false
    @Volatile private var speaking = false
    private var afterSpeech: (() -> Unit)? = null
    private var pendingDictation: Dictation? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(Locale("sw", "TZ"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US)
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        speaking = false
                        mainHandler.post { runAfterSpeech() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        speaking = false
                        mainHandler.post { runAfterSpeech() }
                    }
                })
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
        if (ttsReady) {
            speaking = true
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msaidizi")
        }
    }

    private val afterTimeout = Runnable { runAfterSpeech() }

    /** Inaita [block] pale usemi wa sasa ukiisha (au mara moja kama hakuna usemi, au baada ya [maxWaitMs]). */
    fun afterSpeech(maxWaitMs: Long = 10_000, block: () -> Unit) {
        afterSpeech = block
        mainHandler.removeCallbacks(afterTimeout)
        if (!speaking) runAfterSpeech() else mainHandler.postDelayed(afterTimeout, maxWaitMs)
    }

    private fun runAfterSpeech() {
        mainHandler.removeCallbacks(afterTimeout)
        val b = afterSpeech
        afterSpeech = null
        b?.invoke()
    }

    /** Inarudisha (na kufuta) amri ya kusikiliza ujumbe, kama ipo. */
    fun takeDictation(): Dictation? {
        val d = pendingDictation
        pendingDictation = null
        return d
    }

    fun shutdown() {
        music.release()
        tts.stop()
        tts.shutdown()
    }

    // ------------------------------------------------------------------ amri

    fun execute(raw: String) {
        val t = raw.lowercase(Locale.ROOT).trim()
        pendingDictation = null
        val reply: String = try {
            when {
                // Muziki: kucheza wimbo uliopo kwenye simu kwa jina
                has(t, "cheza", "play") && !has(t, "fungua", "open", "store") -> music.play(songName(t))
                has(t, "simamisha", "pause", "nyamaza") -> music.pause()
                has(t, "endelea", "resume", "continue") -> music.resume()
                has(t, "unaofuata", "ifuatayo", "wimbo mwingine", "badili wimbo", "next", "skip") -> music.next()
                has(t, "acha muziki", "zima muziki", "stop music", "stop the music", "acha wimbo", "zima wimbo") -> music.stop()

                // SMS: "tuma ujumbe kwa mama" -> kisha unasema ujumbe wenyewe
                has(t, "sms", "ujumbe", "meseji", "message") && has(t, "tuma", "andika", "send", "write") -> startSms(t)

                // Tochi
                has(t, "tochi", "flashlight", "torch", "flash") ->
                    setTorch(!has(t, "zima", "zimisha", "funga", "off", "close"))

                // Kamera
                has(t, "kamera", "camera", "picha", "selfie") -> openCamera(has(t, "video", "vidio"))

                // Sauti
                has(t, "kimya", "mute", "silent") -> setVolumeMute()
                has(t, "volume", "sauti") -> changeVolume(t)

                // Data ya simu (SIM 1 au SIM 2)
                has(t, "data", "mtandao wa simu", "intaneti ya simu", "mobile") -> mobileData(t)

                // Kufunga simu (lock): "zima simu", "funga simu", "lock phone", "zima skrini"
                has(t, "zima", "zimisha", "funga", "lock", "kufuli") &&
                    has(t, "simu", "skrini", "screen", "phone") -> lockPhone()

                // Mtandao / Bluetooth
                has(t, "wifi", "wai fai", "intaneti", "internet") -> openWifiPanel()
                has(t, "bluetooth", "blututh") -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")

                // Mipangilio
                has(t, "mipangilio", "settings") -> openSettings(Settings.ACTION_SETTINGS, "mipangilio")

                // Muda
                has(t, "saa ngapi", "muda", "what time", "time is it") -> tellTime()

                // Kupiga simu kwa namba au kwa jina kutoka phonebook
                has(t, "piga", "call", "dial") -> dial(t)

                // Kufungua app yoyote kwa jina
                has(t, "fungua", "open", "anzisha", "launch") -> openApp(t)

                else -> "Samahani, sijaelewa amri hiyo"
            }
        } catch (e: SecurityException) {
            "Sina ruhusa ya kufanya hilo"
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
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

    /** "cheza wimbo wa diamond" -> "diamond". Tupu = cheza chochote. */
    private fun songName(t: String): String =
        t.replace(Regex("\\b(cheza|play|wimbo|nyimbo|song|songs|music|muziki|audio|wa|ya|kwa|the|by|by the|some|a|me)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun lockPhone(): String {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, LockAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
            return "Nimefunga simu"
        }
        val svc = MsaidiziAccessibilityService.instance
        if (svc != null && svc.lockScreen()) return "Nimefunga simu"
        return "Washa ruhusa ya kufunga simu au Accessibility ndani ya app kwanza"
    }

    // ------------------------------------------------------------------ SIM 1 / SIM 2

    private val simRegex = Regex("\\bsim\\s*(1|moja|one|kwanza|first|2|mbili|two|pili|second)\\b")

    /** "sim 1" / "sim mbili" tu (inatumika kwenye SMS na kupiga simu, ambapo namba zinaweza kuwa na tarakimu). */
    private fun simStrict(t: String): Int? {
        val g = simRegex.find(t)?.groupValues?.get(1) ?: return null
        return if (g in setOf("1", "moja", "one", "kwanza", "first")) 1 else 2
    }

    /** "data 1", "data sim 2", "data moja" n.k. */
    private fun simLoose(t: String): Int? = when {
        Regex("\\b(sim\\s*)?(1|moja|one|kwanza|first)\\b").containsMatchIn(t) -> 1
        Regex("\\b(sim\\s*)?(2|mbili|two|pili|second)\\b").containsMatchIn(t) -> 2
        else -> null
    }

    private fun carrierNames(sim: Int?): List<String> {
        if (sim == null || !granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val info = try {
            ctx.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfoForSimSlotIndex(sim - 1)
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return listOfNotNull(info.displayName?.toString(), info.carrierName?.toString())
            .filter { it.isNotBlank() }
    }

    /**
     * Android haziruhusu app kuwasha data moja kwa moja. Kwa hiyo tunafungua paneli ya intaneti kisha
     * (kama Accessibility imewashwa) tunagusa swichi ya data ya SIM husika kama binadamu angefanya.
     */
    private fun mobileData(t: String): String {
        val sim = simLoose(t)
        val on = !has(t, "zima", "zimisha", "funga", "off", "disable")
        val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        else Settings.ACTION_DATA_ROAMING_SETTINGS
        val direct = launch(Intent(action), "data ya simu")
        val svc = MsaidiziAccessibilityService.instance
        val simText = if (sim != null) "SIM $sim" else "SIM unayotaka"
        if (!direct || svc == null) {
            return (if (direct) "Nafungua data ya simu" else "Gusa arifa ili kufungua data ya simu") +
                ". Gusa data ya $simText mwenyewe. Washa Accessibility ndani ya app nifanye hivyo mwenyewe."
        }
        val labels = carrierNames(sim)
        Thread {
            val ok = try {
                svc.toggleMobileData(labels, on)
            } catch (e: Exception) {
                Log.e(TAG, "toggleMobileData", e)
                false
            }
            mainHandler.post {
                speak(
                    if (ok) (if (on) "Data imewashwa" else "Data imezimwa")
                    else "Nimefungua data lakini sijaweza kuigusa. Gusa data ya $simText mwenyewe"
                )
            }
        }.start()
        return "Nafungua data ya simu"
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

    // ------------------------------------------------------------------ kupiga simu

    /** "piga 0712345678", "piga mama", "piga mama sim 2". */
    private fun dial(t0: String): String {
        val sim = simStrict(t0)
        val t = t0.replace(simRegex, " ")
        val digits = t.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 7) return placeCall(digits, digits, sim)

        val name = t
            .replace(Regex("\\b(piga|simu|kwa|call|dial|to|namba|ya|kwenye)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (name.isEmpty()) return "Sema jina la mtu au namba, mfano piga mama"

        if (!granted(Manifest.permission.READ_CONTACTS)) {
            return "Ruhusu anwani za simu ndani ya app ili nipige kwa jina"
        }
        val match = findContact(name) ?: return "Sijapata $name kwenye anwani zako"
        return placeCall(match.second, match.first, sim)
    }

    /** Inapiga moja kwa moja kupitia TelecomManager (haihitaji kufungua skrini). */
    private fun placeCall(number: String, label: String, sim: Int?): String {
        if (granted(Manifest.permission.CALL_PHONE)) {
            try {
                val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val extras = Bundle()
                if (sim != null && granted(Manifest.permission.READ_PHONE_STATE)) {
                    tm.callCapablePhoneAccounts.getOrNull(sim - 1)?.let {
                        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
                    }
                }
                tm.placeCall(Uri.fromParts("tel", number, null), extras)
                return "Napiga $label"
            } catch (e: SecurityException) {
                Log.w(TAG, "placeCall denied", e)
            }
        }
        return opened(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")), "simu kwa $label")
    }

    // ------------------------------------------------------------------ SMS

    /** "tuma ujumbe kwa mama", "tuma sms kwa 0712345678 sim 2". Ujumbe wenyewe unasikilizwa baada ya hapa. */
    private fun startSms(t0: String): String {
        val sim = simStrict(t0)
        val t = t0.replace(simRegex, " ")
        val digits = t.filter { it.isDigit() || it == '+' }
        val target = t
            .replace(Regex("\\b(tuma|andika|send|write|ujumbe|meseji|message|sms|text|kwa|to|ya|na|namba|simu)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val number: String
        val label: String
        if (digits.count { it.isDigit() } >= 7) {
            number = digits
            label = digits
        } else {
            if (target.isEmpty()) return "Sema jina, mfano: tuma ujumbe kwa mama"
            if (!granted(Manifest.permission.READ_CONTACTS)) return "Ruhusu anwani za simu ndani ya app kwanza"
            val m = findContact(target, minScore = 50) ?: return "Sijapata $target kwenye anwani zako"
            number = m.second
            label = m.first
        }
        if (!granted(Manifest.permission.SEND_SMS)) return "Ruhusu kutuma SMS ndani ya app kwanza"

        pendingDictation = Dictation { message -> sendSms(number, label, message, sim) }
        return "Sema ujumbe wako kwa $label"
    }

    private fun sendSms(number: String, label: String, message: String, sim: Int?): String = try {
        val sm = smsManager(sim)
        val parts = sm.divideMessage(message)
        if (parts.size > 1) sm.sendMultipartTextMessage(number, null, parts, null, null)
        else sm.sendTextMessage(number, null, message, null, null)
        "Nimetuma ujumbe kwa $label: $message"
    } catch (e: Exception) {
        Log.e(TAG, "sendSms", e)
        "Imeshindwa kutuma ujumbe: ${e.message}"
    }

    @Suppress("DEPRECATION")
    private fun smsManager(sim: Int?): SmsManager {
        val default: SmsManager =
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        if (sim == null || !granted(Manifest.permission.READ_PHONE_STATE)) return default
        val info = try {
            ctx.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfoForSimSlotIndex(sim - 1)
        } catch (e: Exception) {
            null
        } ?: return default
        return if (Build.VERSION.SDK_INT >= 31) default.createForSubscriptionId(info.subscriptionId)
        else SmsManager.getSmsManagerForSubscriptionId(info.subscriptionId)
    }

    // ------------------------------------------------------------------ anwani

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Inatafuta jina lililo karibu zaidi: sawa kabisa > linaanza na > lina neno lote > lina baadhi ya maneno.
     * [minScore] ya juu = kali zaidi (SMS inatumia 50 ili isitume kwa mtu asiye sahihi).
     */
    private fun findContact(spoken: String, minScore: Int = 1): Pair<String, String>? {
        val query = normalize(spoken)
        if (query.isEmpty()) return null
        val words = query.split(" ")
        var best: Pair<String, String>? = null
        var bestScore = 0

        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null

        cursor.use {
            while (it.moveToNext()) {
                val display = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                val n = normalize(display)
                val score = when {
                    n == query -> 100
                    n.startsWith(query) -> 80
                    n.contains(query) -> 60
                    words.all { w -> n.contains(w) } -> 50
                    else -> {
                        val hits = words.count { w -> w.length > 2 && n.contains(w) }
                        if (hits > 0) 10 * hits else 0
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = display to number
                }
            }
        }
        return if (bestScore >= minScore) best else null
    }

    // ------------------------------------------------------------------ apps

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

    /** Inafungua moja kwa moja, au inatuma arifa ya kugusa. Inarudisha ujumbe wa kusema. */
    private fun opened(intent: Intent, label: String): String =
        if (launch(intent, label)) "Nafungua $label" else "Gusa arifa ili kufungua $label"

    /**
     * Android 10+ hairuhusu huduma ya nyuma kufungua skrini. Njia, kwa mpangilio:
     *  1) Huduma ya Accessibility ya Msaidizi (njia bora; haihitaji "Display over other apps").
     *  2) "Display over other apps".
     *  3) Arifa ya kugusa (skrini ikiwa imefungwa inafunguka yenyewe).
     * Inarudisha true ikifunguliwa moja kwa moja.
     */
    private fun launch(intent: Intent, label: String): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val svc = MsaidiziAccessibilityService.instance
        if (svc != null) {
            try {
                svc.launch(intent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "accessibility launch failed", e)
            }
        }

        if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)) {
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

    companion object {
        private const val TAG = "Msaidizi"
        private const val ACTION_CHANNEL = "msaidizi_actions"
        const val ACTION_NOTIF_ID = 2
    }
}
