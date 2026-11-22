package com.msaidizi.app

import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Inagusa yenyewe arifa za "Gusa kufungua …" ambazo Msaidizi anatuma (kamera, wifi, apps, kupiga simu).
 * Inapuuza arifa zote za apps nyingine: haisomi wala haigusi chochote kisicho cha Msaidizi.
 * Inahitaji ruhusa ya "Notification access" (inawashwa kwenye Settings).
 */
class AutoTapListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName || sbn.id != CommandHandler.ACTION_NOTIF_ID) return
        val pi = sbn.notification.contentIntent ?: return
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        } catch (e: PendingIntent.CanceledException) {
            Log.w("Msaidizi", "auto-tap canceled", e)
        } catch (e: Exception) {
            Log.w("Msaidizi", "auto-tap failed", e)
        }
        // Arifa haifutwi hapa: ikiwa Android imezuia kufungua, bado unaweza kuigusa mwenyewe.
    }
}
