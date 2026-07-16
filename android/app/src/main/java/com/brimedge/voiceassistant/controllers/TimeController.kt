package com.brimedge.voiceassistant.controllers

import java.util.Calendar

/**
 * Converts the current time into a natural Swahili sentence.
 * Swahili time convention: hours are offset by 6 (6:00 AM = "saa kumi na mbili"→"saa moja asubuhi",
 * i.e. hour 7 AM = "saa moja"). We use the standard Swahili clock.
 */
object TimeController {

    private val hourNames = arrayOf(
        "sita",       // 0 -> saa sita usiku (approx, we'll use context)
        "saba", "nane", "tisa", "kumi", "kumi na moja", "kumi na mbili",
        "moja", "mbili", "tatu", "nne", "tano", "sita"
    )

    private val minuteNames = mapOf(
        0 to "kamili",
        5 to "na dakika tano",
        10 to "na dakika kumi",
        15 to "na robo",
        20 to "na dakika ishirini",
        25 to "na dakika ishirini na tano",
        30 to "na nusu",
        35 to "na dakika thelathini na tano",
        40 to "na dakika arobaini",
        45 to "kasoro robo",
        50 to "kasoro dakika kumi",
        55 to "kasoro dakika tano"
    )

    fun currentTimeSpoken(): String {
        val cal = Calendar.getInstance()
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        // Swahili "saa" number: subtract 6 from 24h clock, mod 12.
        val swahiliHourIndex = ((hour24 - 6) % 12 + 12) % 12
        val hourWord = when (swahiliHourIndex) {
            0 -> "kumi na mbili"
            1 -> "moja"
            2 -> "mbili"
            3 -> "tatu"
            4 -> "nne"
            5 -> "tano"
            6 -> "sita"
            7 -> "saba"
            8 -> "nane"
            9 -> "tisa"
            10 -> "kumi"
            11 -> "kumi na moja"
            else -> "sita"
        }

        val period = when (hour24) {
            in 5..11 -> "asubuhi"
            in 12..15 -> "mchana"
            in 16..18 -> "jioni"
            else -> "usiku"
        }

        // Round minute to nearest 5 for a natural phrasing.
        val roundedMinute = ((minute + 2) / 5) * 5
        val minuteWord = minuteNames[roundedMinute] ?: "na dakika $roundedMinute"

        return "Sasa ni saa $hourWord $minuteWord $period."
    }
}
