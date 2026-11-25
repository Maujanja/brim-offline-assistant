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
    const val KEY_FREE = "ai_free"            // tumia AI ya bure ikiwa yangu haifanyi kazi
    const val KEY_FREE_URL = "ai_free_url"
    const val KEY_FREE_KEY = "ai_free_key"
    const val KEY_FREE_MODEL = "ai_free_model"
    const val DEFAULT_FREE_URL = "https://text.pollinations.ai/openai"
    const val DEFAULT_FREE_MODEL = "openai"

    @Volatile private var sautiDownUntil = 0L
    /** Ya kuonyesha kwenye "Jaribu AI": ni AI ipi iliyojibu mara ya mwisho, na kosa la Sauti kama lipo. */
    @Volatile var lastProvider = ""
    @Volatile var lastSautiError: String? = null

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
washa tochi | zima tochi | fungua kamera | fungua video | volume juu | volume chini | kimya | saa ngapi | piga <jina au namba> | piga <jina> sim 2 | tuma ujumbe kwa <jina au namba> | tuma ujumbe kwa <jina au namba> kwamba <maneno ya ujumbe> | cheza <jina la wimbo> | cheza muziki | simamisha | endelea | wimbo unaofuata | acha muziki | fungua <jina la app> | fungua wifi | fungua bluetooth | fungua mipangilio | washa data sim 1 | zima data sim 2 | zima simu | soma skrini
Mifano: "mpigie baba" -> piga baba; "mwambie mama kesho nitakuja asubuhi" -> tuma ujumbe kwa mama kwamba kesho nitakuja asubuhi; "weka nyimbo za diamond" -> cheza diamond; "niwashie mwanga" -> washa tochi; "nifungie simu" -> zima simu."""

    const val SMS_CONTEXT = """Unasafisha ujumbe mfupi wa SMS uliosemwa kwa sauti na kuandikwa na utambuzi wa sauti. Rekebisha tahajia ya maneno, herufi kubwa na alama za uakifishaji tu. Usibadilishe maana, usitafsiri, usiongeze wala kupunguza maneno ya maana, wala usiongeze salamu au sahihi. Mtu wa kwanza abaki mtu wa kwanza (mfano "nitakuja" libaki "nitakuja"). Jibu ujumbe uliorekebishwa TU, bila nukuu wala maelezo."""

    const val CONTACT_CONTEXT = """Unasaidia kutafuta jina kwenye anwani za simu. Mtumiaji alisema jina, na utambuzi wa sauti unaweza kuwa umeliandika vibaya (herufi zinazofanana kama b/p, d/t, g/k, ch/sh/j, l/r, au irabu zilizobadilika). Kutoka kwenye orodha ya anwani, chagua jina MOJA linalosikika karibu zaidi na alichosema. Jibu jina hilo hasa kama lilivyo kwenye orodha, bila maelezo. Kama hakuna linalofanana kwa uhakika, jibu NONE."""

    private fun freeOn(ctx: Context) =
        ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FREE, true)

    /** AI yoyote ipo (yangu au ya bure)? */
    fun available(ctx: Context) = config(ctx).enabled || freeOn(ctx)

    /**
     * Inajaribu AI yako (Sauti); ikishindwa au haijawekwa, na [allowFree], inatumia AI ya bure ya mbadala.
     * [allowFree] = false kwa data nyeti (orodha ya anwani): inakwenda kwa seva yako tu.
     * Inazuia thread; ita kutoka thread ya nyuma. Inatupa Exception ikishindwa zote.
     */
    fun ask(ctx: Context, context: String, message: String, allowFree: Boolean = true): String {
        val cfg = config(ctx)
        lastSautiError = null
        if (cfg.enabled && System.currentTimeMillis() >= sautiDownUntil) {
            try {
                val r = askSauti(ctx, cfg, context, message)
                lastProvider = "Sauti"
                return r
            } catch (e: Exception) {
                lastSautiError = e.message ?: e.javaClass.simpleName
                sautiDownUntil = System.currentTimeMillis() + 5 * 60_000L   // usijaribu tena kwa dakika 5
            }
        } else if (cfg.enabled) {
            lastSautiError = "imepumzishwa baada ya kushindwa"
        }
        if (!allowFree || !freeOn(ctx)) throw RuntimeException(lastSautiError ?: "AI haijawekwa")
        val r = askFree(ctx, context, message)
        lastProvider = "AI ya bure"
        return r
    }

    private fun askFree(ctx: Context, context: String, message: String): String {
        val p = ctx.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE)
        var u = p.getString(KEY_FREE_URL, "")!!.trim().ifBlank { DEFAULT_FREE_URL }
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        u = u.trimEnd('/')
        val endpoint = if (u.endsWith("/chat/completions") || u.endsWith("/openai")) u else "$u/chat/completions"
        val key = p.getString(KEY_FREE_KEY, "")!!.trim()
        val model = p.getString(KEY_FREE_MODEL, "")!!.trim().ifBlank { DEFAULT_FREE_MODEL }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", context))
                    .put(JSONObject().put("role", "user").put("content", message))
            )
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(160) } catch (_: Exception) { null }
                throw RuntimeException("AI ya bure $code ${err ?: ""}".trim())
            }
            val text = conn.inputStream.bufferedReader().readText()
            return try {
                JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .getString("content").ifBlank { throw RuntimeException("AI ya bure haikujibu") }
            } catch (e: org.json.JSONException) {
                text.takeIf { it.isNotBlank() && !it.trimStart().startsWith("{") }
                    ?: throw RuntimeException("AI ya bure imerudisha jibu lisilojulikana")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun askSauti(ctx: Context, cfg: Config, context: String, message: String): String {
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
        val s = line.trim('`', '"', '\'', '*', ' ', '.').take(400)
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
