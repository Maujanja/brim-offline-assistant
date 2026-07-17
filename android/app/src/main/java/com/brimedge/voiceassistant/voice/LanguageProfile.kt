package com.brimedge.voiceassistant.voice

import java.util.Locale

/**
 * Everything the recognition + TTS layers need to know to work in a given
 * language. Adding a new language is a matter of dropping a Vosk model in
 * `assets/model-<code>/` and registering a new profile in [LanguageManager].
 *
 * The recognizer itself is fully language-agnostic — it only ever reads
 * [modelAssetPath], [grammar] and [patterns] from the active profile.
 */
data class LanguageProfile(
    /** Short language code, e.g. "en", "sw", "en-sw". */
    val code: String,
    /** Human-readable name for logs / UI. */
    val displayName: String,
    /** Locale used by TTS + text normalisation. */
    val locale: Locale,
    /** Folder name under `assets/` where the Vosk model is unpacked. */
    val modelAssetPath: String,
    /** Phrases fed to Vosk as a restricted grammar for higher accuracy. */
    val grammar: List<String>,
    /** Ordered list of command match patterns. First match wins. */
    val patterns: List<CommandPattern>
)

/**
 * A command is recognised when the transcript contains at least one word
 * from every [requiredGroups] entry. This is deliberately fuzzy so small
 * ASR errors ("torch on" vs "turn on the torch") still resolve correctly.
 */
data class CommandPattern(
    val command: Command,
    val requiredGroups: List<List<String>>
)
