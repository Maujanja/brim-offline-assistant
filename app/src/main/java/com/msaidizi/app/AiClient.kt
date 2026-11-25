package com.msaidizi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI ya kuelewa amri na majina (Sauti API: POST /api/chat). Anwani na ufunguo vikiachwa tupu,
 * zinatumika zile za TTS API (seva ni ile ile). Kila kitu ni hiari: bila AI, utambuzi wa ndani unafanya kazi.
 */
object AiClient {
    const val KEY_URL = "ai_url"
    const val KEY_KEY = "ai_key"

    class Config(val url: String, val key: String) {
        val enabled get() = url.isNotBlank() && key.isNotBlank()
    }

    fun config(ctx: Context): Config {
        val p = ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE)
        val url = p.getString(KEY_URL, "")!!.trim().ifBlank { p.getString(Speaker.Config.KEY_URL, "")!!.trim() }
        val key = p.getString(KEY_KEY, "")!!.trim().ifBlank { p.getString(Speaker.Config.KEY_KEY, "")!!.trim() }
        return Config(url, key)
    }

    const val COMMAND_CONTEXT = """Wewe ni kichanganuzi cha amri za sauti za simu. Mtumiaji anaongea Kiswahili (au Kiingereza) na maandishi yanaweza kuwa na makosa ya utambuzi wa sauti. Elewa nia yake kisha ibadilishe kuwa MOJA ya amri kamili zifuatazo. Jibu amri hiyo TU, mstari mmoja, bila maelezo wala alama. Kama si amri ya simu, jibu NONE.
Amri:
washa tochi | zima tochi | fungua kamera | fungua video | volume juu | volume chini | kimya | saa ngapi | piga <jina au namba> | piga <jina> sim 2 | tuma ujumbe kwa <jina au namba> | cheza <jina la wimbo> | cheza muziki | simamisha | endelea | wimbo unaofuata | acha muziki | fungua <jina la app> | fungua wifi | fungua bluetooth | fungua mipangilio | washa data sim 1 | zima data sim 2 | zima simu | soma skrini
Mifano: "mpigie baba" -> piga baba; "weka nyimbo za diamond" -> cheza diamond; "niwashie mwanga" -> washa tochi; "nifungie simu" -> zima simu."""

    const val CONTACT_CONTEXT = """Unasaidia kutafuta jina kwenye anwani za simu. Mtumiaji alisema jina, na utambuzi wa sauti unaweza kuwa umeliandika vibaya (herufi zinazofanana kama b/p, d/t, g/k, ch/sh/j, l/r, au irabu zilizobadilika). Kutoka kwenye orodha ya anwani, chagua jina MOJA linalosikika karibu zaidi na alichosema. Jibu jina hilo hasa kama lilivyo kwenye orodha, bila maelezo. Kama hakuna linalofanana kwa uhakika, jibu NONE."""

    /** Inatuma ombi (inazuia thread; ita kutoka thread ya nyuma). Inatupa Exception ikishindwa. */
    fun ask(ctx: Context, context: String, message: String): String {
        val cfg = config(ctx)
        if (!cfg.enabled) throw RuntimeException("AI haijawekwa")
        val body = JSONObject()
            .put("context", context)
            .put("message", message)
            .put("history", JSONArray())
        val conn = URL(endpoint(cfg.url)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("X-API-Key", cfg.key)
            conn.setRequestProperty("Authorization", "Bearer ${cfg.key}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(160) } catch (_: Exception) { null }
                throw RuntimeException("API $code ${err ?: ""}".trim())
            }
            val text = conn.inputStream.bufferedReader().readText()
            return try {
                JSONObject(text).optString("reply", "").ifBlank { throw RuntimeException("AI haikujibu") }
            } catch (e: org.json.JSONException) {
                text
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Jibu la AI -> mstari mmoja safi, au null kama NONE/tupu. */
    fun clean(reply: String): String? {
        val line = reply.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val s = line.trim('`', '"', '\'', '*', ' ', '.').take(120)
        if (s.isEmpty() || s.equals("none", true) || s.startsWith("none", true)) return null
        return s
    }

    private fun endpoint(raw: String): String {
        var u = raw.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        val url = try { URL(u) } catch (_: Exception) { return u.trimEnd('/') + "/api/chat" }
        if (url.path.endsWith("/api/chat")) return u
        val port = if (url.port != -1) ":${url.port}" else ""
        return "${url.protocol}://${url.host}$port/api/chat"
    }
}
