package com.montecarlo.meai

import android.content.Context
import org.json.JSONObject

data class Lead(
    val title: String, val url: String, val platform: String, val market: String,
    val country: String, val budget: String, val posted: String, val proposals: String,
    val language: String, val summary: String, val fit: Int,
    val why: String, val watch: String, val proposal: String,
    val fresh: Boolean = false,
    /** Freelancer: وقت نشر المشروع (ثواني)، ووقت آخر عدّ للعروض، وإذا صار زحمة (فوق 30 عرض) */
    val postedAt: Long = 0, val bidsAt: String = "", val crowded: Boolean = false
)

data class Skipped(val title: String, val url: String, val reason: String)

data class JobsReport(
    val date: String, val summary: String, val leads: List<Lead>, val rejected: List<Skipped>,
    val generatedAt: String = "", val fresh: Int = 0
)

/** فرص الشغل اللي بيلاقيها الـ Job Scout كل يوم */
object Jobs {

    /** فوق هيك عدد عروض، فرصة Freelancer بتنخفى (إلا إذا قدّمت عليها) */
    const val MAX_BIDS = 30

    private fun JSONObject.s(k: String): String {
        val v = optString(k, "")
        return if (v == "null") "" else v
    }

    /** الحقول اللي إلها نسختين {ar, en} */
    private fun JSONObject.lang(k: String, en: Boolean): String {
        val o = optJSONObject(k) ?: return s(k)
        val v = o.optString(if (en) "en" else "ar", "")
        return if (v.isBlank() || v == "null") o.optString(if (en) "ar" else "en", "") else v
    }

    fun parse(json: String, en: Boolean): JobsReport {
        val root = JSONObject(json)
        val la = root.optJSONArray("leads")
        val leads = if (la == null) emptyList() else List(la.length()) { i ->
            val x = la.getJSONObject(i)
            Lead(
                x.s("title"), x.s("url"), x.s("platform"), x.s("market"), x.s("country"),
                x.s("budget"), x.s("posted"), x.s("proposals"), x.s("language"), x.s("summary"),
                x.optInt("fit", 0).coerceIn(0, 10), x.lang("why", en), x.lang("watch", en), x.s("proposal"),
                x.optBoolean("fresh", false),
                x.optLong("postedAt", 0), x.s("bidsAt"),
                x.optBoolean("crowded", false) || (x.s("platform") == "Freelancer" && (x.s("proposals").toIntOrNull() ?: 0) > MAX_BIDS)
            )
        }
        val ra = root.optJSONArray("rejected")
        val rejected = if (ra == null) emptyList() else List(ra.length()) { i ->
            val x = ra.getJSONObject(i)
            Skipped(x.s("title"), x.s("url"), x.lang("reason", en))
        }
        // الجديد من آخر جولة فوق، وبعدين حسب قد إيش بتناسب
        return JobsReport(root.s("date"), root.lang("summary", en),
            leads.sortedWith(compareByDescending<Lead> { it.fresh }.thenByDescending { it.fit }), rejected,
            root.s("generatedAt"), root.optInt("fresh", leads.size))
    }

    fun latest(ctx: Context): JobsReport {
        val t = Repo.fetch("jobs-latest.json")
        val r = parse(t, Lang.en(ctx))
        Repo.prefs(ctx).edit().putString("jobsCache", t).apply()
        return r
    }

    fun cached(ctx: Context): JobsReport? =
        Repo.prefs(ctx).getString("jobsCache", null)?.let { runCatching { parse(it, Lang.en(ctx)) }.getOrNull() }

    // "قدّمت عليها" بينحفظ على الموبايل بس
    fun applied(ctx: Context, url: String) = Repo.prefs(ctx).getBoolean("applied:$url", false)

    fun setApplied(ctx: Context, url: String, v: Boolean) =
        Repo.prefs(ctx).edit().putBoolean("applied:$url", v).apply()
}
