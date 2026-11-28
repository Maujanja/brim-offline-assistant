package com.msaidizi.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private val speaker = Speaker(ctx)
    private var pendingDictation: Dictation? = null

    /** Idadi ya herufi za jibu la mwisho; huduma inatumia kukadiria muda wa kusubiri usemi uishe. */
    @Volatile var lastReplyChars = 0
        private set

    private val torchId: String? by lazy {
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    fun speak(text: String) {
        lastReplyChars = text.length
        speaker.speak(text)
    }

    fun stopSpeaking() = speaker.stop()

    /** Inaita [block] pale usemi wa sasa ukiisha (au mara moja kama hakuna usemi, au baada ya [maxWaitMs]). */
    fun afterSpeech(maxWaitMs: Long = 10_000, block: () -> Unit) = speaker.afterSpeech(maxWaitMs, block)

    /** Inarudisha (na kufuta) amri ya kusikiliza ujumbe, kama ipo. */
    fun takeDictation(): Dictation? {
        val d = pendingDictation
        pendingDictation = null
        return d
    }

    fun shutdown() {
        music.release()
        speaker.shutdown()
    }

    // ------------------------------------------------------------------ amri

    private var currentDone: (() -> Unit)? = null
    private var deferred = false          // true = jibu litakuja baadaye (AI), onDone itaitwa na AI
    private var aiAllowed = true
    @Volatile var lastReply = ""
        private set
    private var rawText = ""           // maneno ya mtumiaji kama yalivyo (herufi kubwa/ndogo), kwa ujumbe wa SMS

    /** Inatekeleza [raw]; [onDone] inaitwa (thread kuu) amri ikimalizika, ikiwemo ikisubiri AI. */
    fun execute(raw: String, onDone: () -> Unit) = run(raw, true, onDone)

    private fun run(raw: String, ai: Boolean, onDone: () -> Unit) {
        currentDone = onDone
        deferred = false
        aiAllowed = ai
        rawText = raw.trim()
        val t = canonical(raw.lowercase(Locale.ROOT).trim())
        pendingDictation = null
        // Maongezi ya kawaida (si amri fupi): AI ya mazungumzo, yenye kumbukumbu na ujumbe wa leo
        if (ai && Assistant.chatReady(ctx) && looksConversational(t)) {
            askAi(rawText)
            return
        }
        var spoken = false          // ni usomaji wa skrini tu unaosemwa kwa sauti; majibu mengine ni kimya
        val reply: String = try {
            when {
                // Kusoma maandishi ya skrini iliyo wazi: "soma skrini", "soma ukurasa huu", "read the screen"
                has(t, "acha kusoma", "simamisha kusoma", "stop reading") -> "Sawa"
                has(t, "soma", "read") && has(t, "skrini", "screen", "ukurasa", "kurasa", "page", "maandishi", "text") ->
                { spoken = true; readScreen() }

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

                else -> {
                    if (ai && AiClient.available(ctx)) { askAi(t); "" } else "Samahani, sijaelewa amri hiyo"
                }
            }
        } catch (e: SecurityException) {
            "Sina ruhusa ya kufanya hilo"
        } catch (e: Exception) {
            Log.e(TAG, "execute failed", e)
            "Kuna hitilafu imetokea"
        }
        if (deferred) return
        lastReply = reply
        Trace.set(ctx, "amri \"$t\" -> ${reply.ifBlank { "(imefanyika)" }.take(120)}")
        if (spoken) speak(reply) else { lastReplyChars = 0; Log.i(TAG, "reply (kimya): $reply") }
        onDone()
    }

    // ------------------------------------------------------------------ uelewa (maneno mbadala + AI)

    private val musicWords = Regex("\\b(muziki|mziki|mzik|nyimbo|wimbo|ngoma|kibao|music|song|songs|audio)\\b")

    private val sendVerbs = Regex("\\b(mtumie|nitumie|mtumieni|nitumieni|kumtumia|kunitumia)\\b", RegexOption.IGNORE_CASE)

    /** Inabadilisha njia mbalimbali za kusema amri kuwa umbo ambalo amri za msingi zinaelewa. */
    private fun canonical(t0: String): String {
        val t = t0
            .replace(Regex("\\b(tafadhali|naomba|nataka|hebu|please|unaweza)\\b"), " ")
            .replace(sendVerbs, "tuma")
            .replace(Regex("\\b(mpigie|nipigie|mpigieni|nipigieni|pigia|kumpigia|kunipigia|wapigie|wapigieni)\\b"), "piga")
            .replace(Regex("\\s+"), " ").trim()
        val control = has(t, "zima", "acha", "simamisha", "nyamaza", "stop", "pause", "funga", "endelea",
            "unaofuata", "ifuatayo", "next", "skip", "badili", "fungua", "open")
        if (musicWords.containsMatchIn(t) && !control &&
            has(t, "piga", "weka", "washa", "anzisha", "sikiliza", "sikia", "cheza", "play", "put", "nipe", "tafuta")
        ) return "cheza $t"
        return t
    }

    /** Amri haikueleweka kwa sheria: muulize AI aibadilishe kuwa amri inayojulikana, kisha itekeleze. */
    private fun askAi(text: String) {
        deferred = true
        val done = currentDone ?: {}
        converse(text, allowCommands = true, aloud = true) { done() }
    }

    private val cmdVerbs = setOf(
        "washa", "zima", "zimisha", "fungua", "funga", "piga", "mpigie", "nipigie", "pigia", "cheza", "weka",
        "sikiliza", "tuma", "mtumie", "nitumie", "andika", "soma", "nisomee", "simamisha", "nyamaza", "endelea",
        "acha", "badili", "ongeza", "punguza", "kimya", "volume", "anzisha", "nipe",
        "open", "play", "call", "send", "turn", "stop", "read", "pause", "next", "resume"
    )
    private val chatFillers = setOf("hey", "sasa", "basi", "tafadhali", "naomba", "hebu", "ok", "okay", "sawa", "oya", "mambo", "please")
    private val questionWords = listOf("je", "nini", "gani", "vipi", "kwanini", "lini", "nani", "niambie", "eleza", "nimeamka", "leo", "jana")

    /** Si amri fupi ya kuanza na kitenzi cha amri: ni maongezi (mfano "kuna ujumbe gani leo"). */
    private fun looksConversational(t: String): Boolean {
        val words = t.split(" ").filter { it.isNotBlank() && it !in chatFillers }
        if (words.isEmpty()) return false
        if (words.first() in cmdVerbs) return false
        if (words.size >= 4) return true
        return words.size >= 3 && words.any { it in questionWords }
    }

    /**
     * Mazungumzo na AI (kwa thread ya nyuma). [allowCommands]: AI inaweza kurudisha amri ya simu.
     * [aloud]: jibu lisomwe kwa sauti. [onDone] inaitwa na jibu (au null).
     */
    fun converse(text: String, allowCommands: Boolean, aloud: Boolean, onDone: (String?) -> Unit) {
        Thread {
            val reply = try {
                Assistant.respond(ctx, text, allowCommands)
            } catch (e: Exception) {
                Log.w(TAG, "converse", e)
                null
            }
            mainHandler.post {
                Trace.set(ctx, "AI (${AiClient.lastProvider}) \"${text.take(60)}\" -> " +
                    (reply?.command?.let { "CMD $it" } ?: reply?.text?.take(100) ?: "hakuna jibu") +
                    (AiClient.lastSautiError?.let { " | Sauti: $it" } ?: ""))
                when {
                    reply == null -> onDone(null)
                    reply.command != null -> run(reply.command, false) { onDone(null) }
                    else -> {
                        lastReply = reply.text ?: ""
                        if (aloud) speak(lastReply)
                        onDone(reply.text)
                    }
                }
            }
        }.start()
    }

    /** Jina halikupatikana kwa ulinganisho wa ndani: muulize AI achague kutoka kwenye orodha ya anwani. */
    private fun aiPickContact(spoken: String, sim: Int?): String {
        deferred = true
        val done = currentDone ?: {}
        Thread {
            val pick = try {
                val names = allContactNames()
                if (names.isEmpty()) null
                else AiClient.clean(
                    AiClient.ask(ctx, AiClient.CONTACT_CONTEXT, "Alichosema: $spoken\nAnwani: " + names.joinToString("; "), allowFree = false)
                )
            } catch (e: Exception) {
                Log.w(TAG, "AI contact: ${e.message}")
                null
            }
            mainHandler.post {
                try {
                    val m = pick?.let { findContact(it, minScore = 80) }
                    Log.i(TAG, "AI contact: \"$spoken\" -> $pick")
                    if (m != null) Log.i(TAG, placeCall(m.second, m.first, sim))
                } catch (e: Exception) {
                    Log.w(TAG, "AI contact call", e)
                }
                done()
            }
        }.start()
        return ""
    }

    private fun allContactNames(): List<String> {
        val out = LinkedHashSet<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null
        )?.use {
            while (it.moveToNext() && out.size < 400) it.getString(0)?.takeIf { n -> n.isNotBlank() }?.let(out::add)
        }
        return out.toList()
    }

    private fun has(text: String, vararg keys: String) = keys.any { text.contains(it) }

    // ------------------------------------------------------------------ vitendo

    private fun readScreen(): String {
        val svc = MsaidiziAccessibilityService.instance
            ?: return "Washa Accessibility kwanza ili nisome skrini"
        val text = svc.readScreenText()
        return if (text.isBlank()) "Sioni maandishi yoyote kwenye skrini hii" else text
    }

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
        t.replace(Regex("\\b(cheza|play|piga|weka|washa|anzisha|sikiliza|sikia|nipe|tafuta|wimbo|nyimbo|ngoma|kibao|mziki|mzik|song|songs|music|muziki|audio|wa|ya|kwa|za|the|by|by the|some|a|me)\\b"), " ")
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
                Log.i(TAG, if (ok) "data imebadilishwa" else "data: imeshindwa kugusa swichi")
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
        val match = findContact(name)
            ?: return if (aiAllowed && AiClient.config(ctx).enabled) aiPickContact(name, sim)
            else "Sijapata $name kwenye anwani zako"
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
        val inline = parseInlineSms(rawText)
        val header = inline?.first?.lowercase(Locale.ROOT) ?: t0
        val sim = simStrict(header)
        val t = header.replace(simRegex, " ")
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
        if (!granted(Manifest.permission.SEND_SMS)) return "Sina ruhusa ya kutuma SMS. Fungua Mipangilio > Apps > Msaidizi > Ruhusa > SMS. Android 13+: kwenye Msaidizi bonyeza ⋮ juu kulia > Ruhusu mipangilio iliyozuiwa"

        if (inline != null) {
            // Jina na ujumbe vimesemwa pamoja: "mtumie mama ujumbe kesho nitakuja asubuhi"
            sendPolished(number, label, inline.second, sim, defer = true)
            return ""
        }
        pendingDictation = Dictation { message -> sendPolished(number, label, message, sim, defer = false); "Inatumwa" }
        return "Sema ujumbe wako kwa $label"
    }

    private val smsConn = "(?:kwamba|unaosema|usemao|unaosomeka|that|saying|:)"
    private val smsKw = "(?:ujumbe|sms|meseji|message|text)"
    private val smsRegexes = listOf(
        // "tuma ujumbe kwa mama kwamba kesho nitakuja"
        Regex("^(?:tuma|andika|send|write)\\s+(?:$smsKw\\s+)?(?:kwa|to)\\s+(.+?)\\s+$smsConn\\s*(.+)$", RegexOption.IGNORE_CASE),
        // "mtumie mama ujumbe kesho nitakuja" (mtumie -> tuma)
        Regex("^(?:tuma|andika|send|write)\\s+(?:kwa\\s+)?(.+?)\\s+$smsKw\\s*(?:$smsConn\\s*)?(.+)$", RegexOption.IGNORE_CASE)
    )

    /** (sehemu ya mpokeaji, ujumbe) kama ujumbe umesemwa pamoja na amri; vinginevyo null. */
    private fun parseInlineSms(raw: String): Pair<String, String>? {
        val r = raw.replace(sendVerbs, "tuma").replace(Regex("\\s+"), " ").trim()
        for (re in smsRegexes) {
            val m = re.find(r) ?: continue
            val msg = m.groupValues[2].trim()
            if (msg.length >= 2) return m.groupValues[1].trim() to msg
        }
        return null
    }

    /** Inasafisha ujumbe (AI ikiwekwa: tahajia na uakifishaji) kisha inautuma. [defer] = tuma onDone baada ya kutuma. */
    private fun sendPolished(number: String, label: String, message: String, sim: Int?, defer: Boolean) {
        val done = currentDone ?: {}
        if (defer) deferred = true
        Thread {
            val text = polishMessage(message)
            mainHandler.post {
                Log.i(TAG, sendSms(number, label, text, sim))
                if (defer) done()
            }
        }.start()
    }

    private fun polishMessage(raw: String): String {
        val local = raw.trim().replaceFirstChar { it.uppercase() }
        if (!AiClient.available(ctx)) return local
        return try {
            val r = AiClient.ask(ctx, AiClient.SMS_CONTEXT, raw.trim()).trim().trim('"', '`').trim()
            if (r.isBlank() || r.equals("none", true) || r.length > raw.length * 3 + 60) local else r
        } catch (e: Exception) {
            Log.w(TAG, "AI polish: ${e.message}")
            local
        }
    }

    private fun sendSms(number: String, label: String, message: String, sim: Int?): String = try {
        val sm = smsManager(sim)
        val parts = sm.divideMessage(message)
        val action = "com.msaidizi.app.SMS_SENT_" + System.nanoTime()
        val app = ctx.applicationContext
        var okCount = 0
        var reported = false
        lateinit var receiver: BroadcastReceiver
        fun finishReport(msg: String) {
            if (reported) return
            reported = true
            Trace.set(app, msg)
            try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (resultCode == Activity.RESULT_OK) {
                    if (++okCount >= parts.size) finishReport("SMS imetumwa kwa $label")
                } else {
                    finishReport("SMS imeshindwa kwa $label: ${smsError(resultCode)}")
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        mainHandler.postDelayed({ finishReport("SMS kwa $label: sikupata uthibitisho kutoka kwa mfumo") }, 60_000)
        val pi = PendingIntent.getBroadcast(
            app, 0, Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        Trace.set(app, "natuma SMS kwa $label ($number)${if (sim != null) " SIM $sim" else ""}…")
        if (parts.size > 1) sm.sendMultipartTextMessage(number, null, parts, ArrayList<PendingIntent>(parts.map { pi }), null)
        else sm.sendTextMessage(number, null, message, pi, null)
        "Nimetuma ujumbe kwa $label: $message"
    } catch (e: Exception) {
        Log.e(TAG, "sendSms", e)
        Trace.set(ctx, "SMS imeshindwa kutuma: ${e.message ?: e.javaClass.simpleName}")
        "Imeshindwa kutuma ujumbe: ${e.message}"
    }

    private fun smsError(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "kosa la jumla (angalia salio/SIM)"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "simu iko kwenye hali ya ndege"
        SmsManager.RESULT_ERROR_NULL_PDU -> "ujumbe tupu"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "hakuna mtandao"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "kikomo cha SMS kimezidi"
        else -> "kosa $code"
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

    /** Umbo la sauti la neno: herufi zinazochanganywa kwa urahisi zinafanywa moja. */
    private fun fold(s: String): String {
        val x = normalize(s).replace("ch", "x").replace("sh", "x").replace("j", "x")
            .replace("ny", "n").replace("ng", "n")
        return buildString {
            for (c in x) append(
                when (c) {
                    'p' -> 'b'; 't' -> 'd'; 'k', 'c', 'q' -> 'g'; 'v' -> 'f'; 'r' -> 'l'
                    'z' -> 's'; 'w' -> 'u'; 'y' -> 'i'; else -> c
                }
            )
        }
    }

    private fun skeleton(s: String) = fold(s).filter { it !in "aeiou " }

    private fun levSim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    /** 0.0..1.0: jinsi [query] inavyofanana kwa sauti na [name] (jina zima au neno lake lolote). */
    private fun fuzzy(query: String, name: String): Double {
        val qf = fold(query)
        val qs = skeleton(query)
        var best = 0.0
        for (c in listOf(name) + name.split(" ")) {
            if (c.length < 2) continue
            best = maxOf(best, levSim(qf, fold(c)))
            val cs = skeleton(c)
            if (qs.length >= 3 && cs.length >= 3) best = maxOf(best, 0.92 * levSim(qs, cs))
        }
        return best
    }

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
                }.let { base ->
                    // Majina magumu: ulinganisho wa sauti (b/p, d/t, g/k, ch/sh/j, l/r…) kwa jina zima na kila neno lake
                    if (base >= 50) base else {
                        val sim = fuzzy(query, n)
                        if (sim >= 0.72) maxOf(base, (sim * 55).toInt()) else base
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
