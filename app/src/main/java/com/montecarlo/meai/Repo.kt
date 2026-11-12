package com.montecarlo.meai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Idea(
    val name: String, val pitch: String, val problem: String, val audience: String,
    val whyNow: String, val competition: String, val monetization: String,
    val difficulty: String, val days: Int, val score: Int, val firstStep: String
)

data class Source(val title: String, val url: String)

data class Report(
    val date: String, val summary: String, val trends: List<String>,
    val ideas: List<Idea>, val sources: List<Source>
)

object Repo {
    // التقارير اللي بيكتبها الـ Agent كل يوم بريبو Me.ai
    private const val BASE = "https://raw.githubusercontent.com/montecarlonews24-droid/Me.ai/main/reports/"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("meai", Context.MODE_PRIVATE)

    private fun fetch(file: String): String {
        val c = URL(BASE + file + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.useCaches = false
        try {
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun JSONObject.s(k: String): String {
        val v = optString(k, "")
        return if (v == "null") "" else v
    }

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.optString(it) }

    fun parse(json: String): Report {
        val o = JSONObject(json)
        val ia = o.optJSONArray("ideas")
        val ideas = if (ia == null) emptyList() else List(ia.length()) { i ->
            val x = ia.getJSONObject(i)
            Idea(
                x.s("name"), x.s("pitch"), x.s("problem"), x.s("audience"), x.s("whyNow"),
                x.s("competition"), x.s("monetization"), x.s("buildDifficulty"),
                x.optInt("buildDays", 0), x.optInt("score", 0).coerceIn(0, 10), x.s("firstStep")
            )
        }
        val sa = o.optJSONArray("sources")
        val sources = if (sa == null) emptyList() else List(sa.length()) { i ->
            val x = sa.getJSONObject(i)
            Source(x.s("title"), x.s("url"))
        }
        return Report(o.s("date"), o.s("summary"), strings(o.optJSONArray("trends")),
            ideas.sortedByDescending { it.score }, sources)
    }

    fun latest(ctx: Context): Report {
        val t = fetch("latest.json")
        val r = parse(t)
        prefs(ctx).edit().putString("cache", t).apply()
        return r
    }

    fun cached(ctx: Context): Report? =
        prefs(ctx).getString("cache", null)?.let { runCatching { parse(it) }.getOrNull() }

    fun byDate(date: String): Report = parse(fetch("$date.json"))

    fun dates(): List<String> = strings(JSONObject(fetch("index.json")).optJSONArray("dates"))
}
