package com.msaidizi.app

import android.app.Notification
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Inasoma arifa za SMS na WhatsApp tu (apps nyingine zinapuuzwa) ili kukujulisha kuna ujumbe mpya.
 * Msaidizi akiwa anasikiliza, anauliza kama usomewe na kusubiri jibu lako; vinginevyo anaweka arifa ya kugusa-kusoma.
 * Inahitaji ruhusa ya "Notification access" (inawashwa kwenye Settings).
 */
class MessageListener : NotificationListenerService() {

    companion object {
        private const val TAG = "Msaidizi"
        private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val KNOWN_SMS = setOf(
            "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
            "com.android.messaging", "com.sonyericsson.conversations", "com.miui.mms", "com.coloros.mms"
        )
    }

    private val seen = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "message notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(VoiceService.KEY_MSG_ALERTS, true)) return

        val pkg = sbn.packageName
        val smsPkg = Telephony.Sms.getDefaultSmsPackage(this)
        val app = when {
            pkg in WHATSAPP -> "WhatsApp"
            pkg == smsPkg || pkg in KNOWN_SMS -> "SMS"
            else -> return
        }
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing || n.category == Notification.CATEGORY_CALL) return

        val ex = extract(n) ?: return
        val sender = ex.sender
        val text = ex.text
        if (text.isBlank()) return
        // Magroup ya WhatsApp yanapuuzwa
        if (ex.isGroup) return
        // WhatsApp hutuma arifa za mfumo ("Checking for new messages", "3 new messages"): zipuuze
        if (sender.equals("WhatsApp", true) || text.contains("new messages", true) ||
            text.contains("Checking for new", true)
        ) return

        // Zinazojulikana tu: namba za simu za kawaida, au majina yaliyo kwenye phonebook
        if (!isPersonalSender(sender)) return

        val signature = "$sender|$text"
        if (seen[sbn.key] == signature) return        // arifa hiyo hiyo iliyosasishwa
        seen[sbn.key] = signature

        val msg = IncomingMessage(app, sender, text, sbn.key)
        val voice = VoiceService.instance
        if (voice != null) voice.onIncomingMessage(msg) else MessageNotifier.post(this, msg)
    }

    private class Extracted(val sender: String, val text: String, val isGroup: Boolean)

    /** Inarudisha mtumaji, ujumbe wa mwisho, na kama ni mazungumzo ya kikundi. */
    private fun extract(n: Notification): Extracted? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val last = style.messages.last()
            val who = last.person?.name?.toString()?.takeIf { it.isNotBlank() }
                ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val group = style.isGroupConversation || !style.conversationTitle.isNullOrBlank() ||
                n.extras.getBoolean("android.isGroupConversation", false)
            return Extracted(cleanTitle(who), last.text?.toString().orEmpty(), group)
        }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank()) return null
        // Magroup ya WhatsApp bila MessagingStyle: "Kikundi: Juma: habari" au "Juma @ Kikundi"
        val group = n.extras.getBoolean("android.isGroupConversation", false) ||
            title.contains(" @ ") || Regex("^[^:]{1,40}: .+").matches(body) && !looksLikeNumber(title)
        return Extracted(cleanTitle(title), body, group)
    }

    /** "Juma (3 messages)" -> "Juma" */
    private fun cleanTitle(t: String) = t.replace(Regex("\\s*\\(\\d+ (new )?messages?\\)\\s*$", RegexOption.IGNORE_CASE), "").trim()

    private fun looksLikeNumber(s: String): Boolean {
        val t = s.trim()
        return t.isNotEmpty() && t.all { it.isDigit() || it in "+ -()\u00a0\u202a\u202c" }
    }

    private fun digitCount(s: String) = s.count { it.isDigit() }

    private val serviceNames = listOf(
        "mpesa", "m-pesa", "vodacom", "yas", "tigo", "airtel", "halotel", "ttcl", "zantel", "tcra", "safaricom",
        "mixx", "selcom", "tigopesa", "airtelmoney", "halopesa", "azampesa", "nmb", "crdb", "nbc", "equity",
        "customer care", "huduma", "info", "alert", "bank", "benki", "bima", "tra", "luku", "tanesco", "dstv"
    )

    /**
     * true = mtu halisi. Huduma za mitandao, benki na namba fupi (kama 100, 15040) zinakataliwa.
     * Namba ndefu (7+ tarakimu) zinaruhusiwa, hata kama hazijasevu. Jina linaruhusiwa tu likiwa kwenye phonebook.
     */
    private fun isPersonalSender(sender: String): Boolean {
        val s = sender.trim()
        if (s.isEmpty()) return false
        if (looksLikeNumber(s)) return digitCount(s) >= 7
        val lower = s.lowercase()
        // Linganisha maneno kamili ili jina kama "Yasin" lisikataliwe kwa sababu ya "yas"
        val words = lower.split(Regex("[^\\p{L}0-9-]+")).filter { it.isNotEmpty() }
        if (serviceNames.any { it in words || lower == it || (it.length >= 6 && lower.contains(it)) }) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            return isSavedContact(lower)
        }
        // Bila ruhusa ya anwani hatuwezi kuthibitisha: kataa majina yenye herufi kubwa tupu au tarakimu (kama "MPESA", "INFO123")
        return s.any { it.isLowerCase() } && s.none { it.isDigit() }
    }

    private fun isSavedContact(lowerName: String): Boolean = try {
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            "LOWER(${ContactsContract.Contacts.DISPLAY_NAME}) = ?",
            arrayOf(lowerName),
            null
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "contact lookup", e)
        false
    }
}
