package com.brimedge.voiceassistant.voice

import java.util.Locale

/**
 * Parses raw Swahili transcripts into a strongly-typed [Command].
 * Extend by adding entries here and handling them in the executor.
 */
sealed class Command {
    object TorchOn : Command()
    object TorchOff : Command()
    object OpenWhatsApp : Command()
    object OpenCamera : Command()
    object CurrentTime : Command()
    object Unsupported : Command()
    object Unknown : Command()
}

object CommandParser {

    /** The complete recognizable vocabulary for Phase 1 (used as Vosk grammar). */
    val GRAMMAR: List<String> = listOf(
        "washa tochi",
        "zima tochi",
        "fungua whatsapp",
        "fungua kamera",
        "saa ngapi"
    )

    fun parse(raw: String?): Command {
        if (raw.isNullOrBlank()) return Command.Unknown
        val t = raw.lowercase(Locale.getDefault()).trim()
        return when {
            t.contains("washa") && t.contains("tochi") -> Command.TorchOn
            t.contains("zima") && t.contains("tochi") -> Command.TorchOff
            t.contains("fungua") && t.contains("whatsapp") -> Command.OpenWhatsApp
            t.contains("fungua") && t.contains("kamera") -> Command.OpenCamera
            t.contains("saa") && t.contains("ngapi") -> Command.CurrentTime
            else -> Command.Unknown
        }
    }
}
