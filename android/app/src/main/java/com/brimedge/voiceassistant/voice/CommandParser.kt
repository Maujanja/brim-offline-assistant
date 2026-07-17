package com.brimedge.voiceassistant.voice

/**
 * The set of high-level actions Brim can execute. New commands are added
 * here and mapped per language in [LanguageManager]'s profiles — the parser
 * itself stays language-agnostic.
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

/**
 * Language-agnostic transcript → [Command] resolver. Delegates to the
 * patterns registered on the active [LanguageProfile].
 */
object CommandParser {

    fun parse(raw: String?): Command {
        if (raw.isNullOrBlank()) return Command.Unknown
        val profile = LanguageManager.current()
        val text = " " + raw.lowercase(profile.locale).trim() + " "

        for (pattern in profile.patterns) {
            if (pattern.requiredGroups.all { group ->
                    group.any { needle -> text.contains(" $needle ") || text.contains(needle) }
                }
            ) {
                return pattern.command
            }
        }
        return Command.Unknown
    }
}
