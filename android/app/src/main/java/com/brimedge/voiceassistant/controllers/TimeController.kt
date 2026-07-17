package com.brimedge.voiceassistant.controllers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Speaks the current wall-clock time in the given locale. English uses a
 * natural "It's 3:42 PM" phrasing; future locales (Swahili in Phase 2) can
 * add their own branch without touching the recognizer.
 */
object TimeController {

    fun currentTimeSpoken(locale: Locale = Locale.US): String {
        val now = Date()
        return when (locale.language) {
            "sw" -> swahili(now)
            else -> english(now, locale)
        }
    }

    private fun english(now: Date, locale: Locale): String {
        val fmt = SimpleDateFormat("h:mm a", locale)
        return "It's ${fmt.format(now)}."
    }

    private fun swahili(now: Date): String {
        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        return "Sasa ni saa ${fmt.format(now)}."
    }
}
