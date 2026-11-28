package com.msaidizi.app

import android.content.Context
import android.util.Log
import java.util.Calendar

/**
 * Akili ya mazungumzo ya Msaidizi: anajibu maongezi ya kawaida, maswali kuhusu ujumbe wa leo, na anatengeneza
 * maswali ya check-in kutokana na muda, kumbukumbu ya siku 2 na ujumbe uliofika. Anatumia AI (AiClient).
 *
 * Faragha: ujumbe na kumbukumbu vinatumwa kwa AI yako ya Sauti tu. Kwa AI ya bure ya umma vinatumwa tu
 * ukiruhusu kwa kisanduku cha "Ruhusu AI ya bure kupokea ujumbe wangu na majibu yangu" (kimezimwa mwanzoni).
 */
object Assistant {
    private const val TAG = "Msaidizi"
    const val KEY_PRIVATE = "ai_private"
    const val KEY_CHECKIN = "checkin_on"

    private val DAYS = arrayOf("Jumapili", "Jumatatu", "Jumanne", "Jumatano", "Alhamisi", "Ijumaa", "Jumamosi")
    private val MONTHS = arrayOf(
        "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
        "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba"
    )

    private const val COMMANDS =
        "washa tochi | zima tochi | fungua kamera | fungua video | volume juu | volume chini | kimya | saa ngapi | " +
            "piga <jina au namba> | piga <jina> sim 2 | tuma ujumbe kwa <jina au namba> kwamba <maneno ya ujumbe> | " +
            "cheza <jina la wimbo> | cheza muziki | simamisha | endelea | wimbo unaofuata | acha muziki | " +
            "fungua <jina la app> | fungua wifi | fungua bluetooth | fungua mipangilio | washa data sim 1 | " +
            "zima data sim 2 | zima simu | soma skrini"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(VoiceService.PREFS, Context.MODE_PRIVATE)

    /** AI ya bure ya umma imeruhusiwa kupokea data binafsi? */
    fun freeAllowed(ctx: Context) = AiClient.sautiOff(ctx) || prefs(ctx).getBoolean(KEY_PRIVATE, false)

    /** Mazungumzo yanawezekana: AI yako ya Sauti imewekwa, au umeruhusu ya bure. */
    fun chatReady(ctx: Context) = AiClient.config(ctx).enabled || (freeAllowed(ctx) && AiClient.available(ctx))

    fun checkinOn(ctx: Context) = prefs(ctx).getBoolean(KEY_CHECKIN, true)

    // ------------------------------------------------------------------ muda

    fun stamp(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "%s %02d:%02d".format(DAYS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun nowText(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val part = when {
            h < 12 -> "asubuhi"
            h < 16 -> "mchana"
            h < 19 -> "jioni"
            else -> "usiku"
        }
        return "%s, %d %s %d, saa %02d:%02d (%s)".format(
            DAYS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH), MONTHS[c.get(Calendar.MONTH)],
            c.get(Calendar.YEAR), h, c.get(Calendar.MINUTE), part
        )
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ------------------------------------------------------------------ data

    private fun dataBlock(ctx: Context): String {
        val sb = StringBuilder()
        val today = startOfToday()
        val msgs = MessageLog.since(ctx, today - 24L * 3600 * 1000).takeLast(60)
        sb.append("UJUMBE ULIOFIKA KWENYE SIMU (jana na leo; watu halisi tu):\n")
        if (msgs.isEmpty()) sb.append("(hakuna)\n")
        for (m in msgs) {
            val whenText = if (m.ts >= today) "leo" else "jana"
            sb.append("- [$whenText ${stamp(m.ts).substringAfter(' ')}] ${m.app}, ${m.sender}: ${m.text.take(220).replace('\n', ' ')}\n")
        }
        sb.append("\nMAZUNGUMZO NA MAJIBU YA MTUMIAJI (siku 2 zilizopita):\n")
        val turns = Memory.recent(ctx).takeLast(50)
        if (turns.isEmpty()) sb.append("(bado hakuna)\n")
        for (t in turns) {
            val who = when (t.role) {
                Memory.USER -> "Mtumiaji"
                Memory.QUESTION -> "Msaidizi (swali la check-in)"
                else -> "Msaidizi"
            }
            sb.append("- [${stamp(t.ts)}] $who: ${t.text.replace('\n', ' ')}\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ mazungumzo

    class Reply(val command: String?, val text: String?)

    /**
     * Inazuia thread: ita kutoka thread ya nyuma. Inarudisha amri ya simu (CMD), jibu la mazungumzo, au null.
     * [allowCommands] = false kwa majibu ya check-in (ni mazungumzo tu).
     */
    fun respond(ctx: Context, userText: String, allowCommands: Boolean): Reply? {
        if (!chatReady(ctx)) {
            // Hakuna ruhusa ya data binafsi: kazi ya zamani tu ya kubadilisha maneno kuwa amri
            if (!allowCommands || !AiClient.available(ctx)) {
                if (!allowCommands) Memory.add(ctx, Memory.USER, userText)
                return null
            }
            return try {
                AiClient.clean(AiClient.ask(ctx, AiClient.COMMAND_CONTEXT, userText))?.let { Reply(it, null) }
            } catch (e: Exception) {
                Log.w(TAG, "AI command: ${e.message}")
                null
            }
        }
        val system = buildString {
            append("Wewe ni Msaidizi, msaidizi binafsi wa sauti wa mtumiaji kwenye simu yake. ")
            append("Ongea Kiswahili rahisi, cha kirafiki na cha kawaida, kama rafiki mwerevu na mwenye huruma. ")
            append("Majibu yawe mafupi (sentensi 1 hadi 3) kwa sababu yatasomwa kwa sauti; usitumie orodha, nyota wala alama za markdown. ")
            append("Mtumiaji anaweza kuongea kwa namna yoyote; elewa nia yake.\n")
            if (allowCommands) {
                append("Kama anataka kufanya kitendo cha simu (kupiga simu, tochi, SMS, muziki, kufungua app n.k.), jibu mstari MMOJA tu unaoanza na \"CMD: \" ")
                append("ikifuatwa na MOJA ya amri hizi: $COMMANDS. Vinginevyo jibu mazungumzo ya kawaida bila \"CMD:\".\n")
            }
            append("Tumia taarifa hapa chini kujibu maswali kuhusu ujumbe wa leo (nani alituma, alitaka nini, kama kuna aliyeomba huduma au jibu) ")
            append("na kuhusu alichokuambia awali. Usibuni ujumbe au mambo yasiyokuwepo; ukijua hujui, sema hujui.\n")
            append("Sasa ni: ${nowText()}.\n\n")
            append(dataBlock(ctx))
        }
        return try {
            val raw = AiClient.ask(ctx, system, userText, allowFree = freeAllowed(ctx)).trim()
            val first = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            if (allowCommands && first.startsWith("CMD:", ignoreCase = true)) {
                AiClient.clean(first.substring(4))?.let { Reply(it, null) }
            } else {
                val text = raw.trim('"', '`', '*', ' ').take(600)
                Memory.add(ctx, Memory.USER, userText)
                Memory.add(ctx, Memory.ASSISTANT, text)
                Reply(null, text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI chat: ${e.message}")
            if (!allowCommands) Memory.add(ctx, Memory.USER, userText)   // jibu lako lisipotee
            null
        }
    }

    // ------------------------------------------------------------------ check-in

    /** Swali jipya la check-in linalofaa muda na hali, au null (hakuna cha kuuliza sasa / AI haipo). Inazuia thread. */
    fun checkinQuestion(ctx: Context): String? {
        if (!chatReady(ctx)) return null
        val system = buildString {
            append("Wewe ni Msaidizi, rafiki na msaidizi binafsi wa mtumiaji kwenye simu yake. ")
            append("Kazi yako sasa: tengeneza SWALI MOJA fupi la Kiswahili la kumuuliza mtumiaji kwa arifa, linalofaa muda na hali yake ya sasa.\n")
            append("Kanuni:\n")
            append("- Swali liwe la ufahamu na la asili, si la kupangwa wala la kujirudia. USIRUDIE swali lolote lililoulizwa siku 2 zilizopita (angalia orodha), wala usiulize kitu alichokwisha kujibu.\n")
            append("- Tumia alichosema awali na ujumbe uliofika (mfano alisema ana mkutano saa 9, au mtu aliomba huduma: uliza kama ameshughulikia).\n")
            append("- Kulingana na muda: asubuhi (mpango wa siku, ameamkaje), mchana (maendeleo, chakula, mauzo), jioni/usiku (je alikamilisha mipango, ")
            append("lengo la kipato la leo, ilikuwaje siku yake). Wakati mwingine unaweza kuuliza kuhusu afya, familia, mahusiano, biashara au hisia, kwa upole.\n")
            append("- Usiwe mkali wala wa kuchosha. Sentensi moja, ikiwezekana mbili fupi. Bila salamu ndefu.\n")
            append("- Kama hakuna cha maana kuuliza sasa, jibu SKIP.\n")
            append("Jibu swali lenyewe tu, au SKIP.\n")
            append("Sasa ni: ${nowText()}.\n\n")
            append(dataBlock(ctx))
        }
        return try {
            val raw = AiClient.ask(ctx, system, "Tengeneza swali la sasa.", allowFree = freeAllowed(ctx))
            val q = raw.trim().trim('"', '`', '*', ' ').take(400)
            if (q.isBlank() || q.startsWith("SKIP", ignoreCase = true)) null
            else {
                Memory.add(ctx, Memory.QUESTION, q)
                q
            }
        } catch (e: Exception) {
            Log.w(TAG, "check-in: ${e.message}")
            null
        }
    }
}
