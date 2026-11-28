package com.msaidizi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Kumbukumbu ya mazungumzo na majibu yako kwa siku 2. Inatumika kuuliza maswali yenye ufahamu. Inabaki kwenye simu yako. */
object Memory {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val QUESTION = "question"        // swali la check-in lililoulizwa kwa arifa

    data class Turn(val ts: Long, val role: String, val text: String)

    private const val FILE = "memory"
    private const val KEY = "turns"
    const val MAX_AGE_MS = 2L * 24 * 3600 * 1000
    private const val MAX_ITEMS = 200

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(ctx: Context): MutableList<Turn> {
        val out = ArrayList<Turn>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Turn(o.getLong("t"), o.getString("r"), o.getString("x")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    fun add(ctx: Context, role: String, text: String) {
        if (text.isBlank()) return
        val list = load(ctx)
        list.add(Turn(System.currentTimeMillis(), role, text.trim().take(600)))
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val kept = list.filter { it.ts >= cutoff }.takeLast(MAX_ITEMS)
        val arr = JSONArray()
        for (t in kept) arr.put(JSONObject().put("t", t.ts).put("r", t.role).put("x", t.text))
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun recent(ctx: Context): List<Turn> {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return load(ctx).filter { it.ts >= cutoff }
    }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
