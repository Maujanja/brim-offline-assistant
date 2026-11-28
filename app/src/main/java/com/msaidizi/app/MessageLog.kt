package com.msaidizi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Kumbukumbu ya ujumbe wa watu halisi (SMS/WhatsApp) uliofika, kwa siku 3, ili uweze kuuliza "leo kulikua na ujumbe gani?". Inabaki kwenye simu yako. */
object MessageLog {
    data class Entry(val ts: Long, val app: String, val sender: String, val text: String)

    private const val FILE = "msglog"
    private const val KEY = "items"
    private const val MAX_AGE_MS = 3L * 24 * 3600 * 1000
    private const val MAX_ITEMS = 300

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(ctx: Context): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.getLong("t"), o.getString("a"), o.getString("s"), o.getString("x")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Entry>) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val kept = list.filter { it.ts >= cutoff }.takeLast(MAX_ITEMS)
        val arr = JSONArray()
        for (e in kept) arr.put(JSONObject().put("t", e.ts).put("a", e.app).put("s", e.sender).put("x", e.text))
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    @Synchronized
    fun add(ctx: Context, app: String, sender: String, text: String) {
        val list = load(ctx)
        list.add(Entry(System.currentTimeMillis(), app, sender, text.take(500)))
        save(ctx, list)
    }

    fun since(ctx: Context, sinceMs: Long): List<Entry> = load(ctx).filter { it.ts >= sinceMs }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
