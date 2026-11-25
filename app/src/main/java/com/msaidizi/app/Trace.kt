package com.msaidizi.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kumbukumbu fupi ya hatua ya mwisho (nimesikia nini, nimefanya nini), ili ionekane kwenye skrini kuu kutatua matatizo. */
object Trace {
    const val KEY = "trace"

    fun set(ctx: Context, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, "[$time] $msg").apply()
    }

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
}
