package com.msaidizi.app

import android.app.Notification
import android.content.Context
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

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

        val (sender, text) = extract(n) ?: return
        if (text.isBlank()) return
        // WhatsApp hutuma arifa za mfumo ("Checking for new messages", "3 new messages"): zipuuze
        if (sender.equals("WhatsApp", true) || text.contains("new messages", true) ||
            text.contains("Checking for new", true)
        ) return

        val signature = "$sender|$text"
        if (seen[sbn.key] == signature) return        // arifa hiyo hiyo iliyosasishwa
        seen[sbn.key] = signature

        val msg = IncomingMessage(app, sender, text, sbn.key)
        val voice = VoiceService.instance
        if (voice != null) voice.onIncomingMessage(msg) else MessageNotifier.post(this, msg)
    }

    /** Inarudisha (mtumaji, ujumbe wa mwisho). */
    private fun extract(n: Notification): Pair<String, String>? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val last = style.messages.last()
            val who = last.person?.name?.toString()?.takeIf { it.isNotBlank() }
                ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val group = style.conversationTitle?.toString()
            val sender = if (style.isGroupConversation && !group.isNullOrBlank() && who != group) {
                "$who katika $group"
            } else {
                who
            }
            return sender to (last.text?.toString().orEmpty())
        }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank()) return null
        return title to body
    }
}
