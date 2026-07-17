package com.brimedge.voiceassistant.voice

import java.util.Locale

/**
 * Registry of supported offline languages. Phase 1 ships English only; Phase 2
 * will register Swahili, Phase 3 a bilingual profile. The rest of the app
 * only ever talks to this manager — no language code is hardcoded elsewhere.
 */
object LanguageManager {

    private val english = LanguageProfile(
        code = "en",
        displayName = "English",
        locale = Locale.US,
        modelAssetPath = "model-en",
        grammar = listOf(
            "turn on the flashlight",
            "turn off the flashlight",
            "turn on the torch",
            "turn off the torch",
            "open whatsapp",
            "open the camera",
            "what time is it",
            "what is the time",
            "[unk]"
        ),
        patterns = listOf(
            // Order matters: "off" checked before "on" would misfire, so we
            // use two disjoint word groups per pattern.
            CommandPattern(
                Command.TorchOn,
                listOf(
                    listOf("turn", "switch", "put"),
                    listOf("on"),
                    listOf("flashlight", "torch", "light")
                )
            ),
            CommandPattern(
                Command.TorchOff,
                listOf(
                    listOf("turn", "switch", "put"),
                    listOf("off"),
                    listOf("flashlight", "torch", "light")
                )
            ),
            CommandPattern(
                Command.OpenWhatsApp,
                listOf(
                    listOf("open", "launch", "start"),
                    listOf("whatsapp", "whats app", "whats up")
                )
            ),
            CommandPattern(
                Command.OpenCamera,
                listOf(
                    listOf("open", "launch", "start"),
                    listOf("camera")
                )
            ),
            CommandPattern(
                Command.CurrentTime,
                listOf(
                    listOf("time"),
                    listOf("what", "tell", "say", "current")
                )
            )
        )
    )

    private val profiles: MutableList<LanguageProfile> = mutableListOf(english)

    @Volatile
    private var active: LanguageProfile = english

    fun available(): List<LanguageProfile> = profiles.toList()
    fun current(): LanguageProfile = active

    /** Register an additional profile at runtime (used by future phases). */
    fun register(profile: LanguageProfile) {
        if (profiles.none { it.code == profile.code }) profiles.add(profile)
    }

    fun setActive(code: String): Boolean {
        val p = profiles.firstOrNull { it.code == code } ?: return false
        active = p
        return true
    }
}
