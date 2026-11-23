package com.msaidizi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** Inaweka arifa ya "gusa kusikiliza ujumbe" pale hujajibu Msaidizi ndani ya sekunde 15. */
object MessageNotifier {

    private const val CHANNEL = "msaidizi_messages"

    /** Maneno yanayosomwa kwa sauti. Ujumbe mrefu sana unakatwa. */
    fun spokenText(m: IncomingMessage): String =
        "Ujumbe wa ${m.app} kutoka kwa ${m.sender}: ${m.text.take(600)}"

    fun post(ctx: Context, m: IncomingMessage) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ujumbe wa kusomewa", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val id = 1000 + (m.key.hashCode() and 0xffff)
        val read = Intent(ctx, ReadAloudService::class.java).putExtra(ReadAloudService.EXTRA_TEXT, spokenText(m))
        val pi = PendingIntent.getService(
            ctx, id, read, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("${m.app}: ${m.sender}")
            .setContentText("Gusa kusikiliza ujumbe huu")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
