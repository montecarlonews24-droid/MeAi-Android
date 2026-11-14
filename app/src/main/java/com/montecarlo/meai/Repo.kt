package com.montecarlo.meai

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Idea(
    val name: String, val pitch: String, val problem: String, val audience: String,
    val whyNow: String, val competition: String, val monetization: String,
    val difficulty: String, val days: Int, val score: Int, val firstStep: String,
    val competitors: List<String> = emptyList(), val edge: String = ""
)

data class Rejected(val name: String, val reason: String)

data class Source(val title: String, val url: String)

data class Report(
    val date: String, val summary: String, val trends: List<String>,
    val ideas: List<Idea>, val sources: List<Source>,
    val rejected: List<Rejected> = emptyList()
)

object Repo {
    // التقارير اللي بيكتبها الـ Agent كل يوم بريبو Me.ai
    private const val BASE = "https://raw.githubusercontent.com/montecarlonews24-droid/Me.ai/main/reports/"

    private const val PLANS = "https://raw.githubusercontent.com/montecarlonews24-droid/Me.ai/main/plans/"

    /** خطة البناء، أو null إذا لسا ما جهزت */
    fun plan(key: String): JSONObject? {
        val c = URL(PLANS + Uri.encode(key) + ".json?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.useCaches = false
        try {
            return when (c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                else -> throw Exception("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("meai", Context.MODE_PRIVATE)

    fun fetch(file: String): String {
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

    fun parse(json: String, en: Boolean = false): Report {
        val root = JSONObject(json)
        // النسخة الإنجليزية إذا موجودة، وإلا العربي
        val o = if (en) root.optJSONObject("en") ?: root else root
        val ia = o.optJSONArray("ideas")
        val ideas = if (ia == null) emptyList() else List(ia.length()) { i ->
            val x = ia.getJSONObject(i)
            Idea(
                x.s("name"), x.s("pitch"), x.s("problem"), x.s("audience"), x.s("whyNow"),
                x.s("competition"), x.s("monetization"), x.s("buildDifficulty"),
                x.optInt("buildDays", 0), x.optInt("score", 0).coerceIn(0, 10), x.s("firstStep"),
                strings(x.optJSONArray("competitors")).filter { it.isNotBlank() }, x.s("edge")
            )
        }
        val sa = root.optJSONArray("sources")
        val sources = if (sa == null) emptyList() else List(sa.length()) { i ->
            val x = sa.getJSONObject(i)
            Source(x.s("title"), x.s("url"))
        }
        val ra = o.optJSONArray("rejected")
        val rejected = if (ra == null) emptyList() else List(ra.length()) { i ->
            val x = ra.getJSONObject(i)
            Rejected(x.s("name"), x.s("reason"))
        }
        return Report(root.s("date"), o.s("summary"), strings(o.optJSONArray("trends")),
            ideas.sortedByDescending { it.score }, sources, rejected)
    }

    fun latest(ctx: Context): Report {
        val t = fetch("latest.json")
        val r = parse(t, Lang.en(ctx))
        prefs(ctx).edit().putString("cache", t).apply()
        return r
    }

    fun cached(ctx: Context): Report? =
        prefs(ctx).getString("cache", null)?.let { runCatching { parse(it, Lang.en(ctx)) }.getOrNull() }

    fun byDate(date: String, en: Boolean): Report = parse(fetch("$date.json"), en)

    fun dates(): List<String> = strings(JSONObject(fetch("index.json")).optJSONArray("dates"))
}
