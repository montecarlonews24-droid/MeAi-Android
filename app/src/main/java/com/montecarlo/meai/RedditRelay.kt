package com.montecarlo.meai

import android.content.Context
import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/**
 * Reddit بيسكّر على سيرفرات GitHub، بس مش على موبايلك.
 * فالموبايل بيقرأ بوستات [Hiring] الجديدة من r/forhire (RSS عام، متل أي قارئ أخبار)
 * وبيرفعها لـ Firebase، والـ Job Scout بياخدها من هناك وبيحكم عليها وبيكتبلها عرض.
 */
object RedditRelay {
    private const val FEED = "https://www.reddit.com/r/forhire/new/.rss?limit=50"
    private const val DB = "https://meai-agent-ee448-default-rtdb.europe-west1.firebasedatabase.app/reddit/"
    private val KEY = Regex("android|mobile app|\\bapps?\\b|play store|google play|apk|kotlin|webview", RegexOption.IGNORE_CASE)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Android:com.montecarlo.meai:1.5 (personal feed reader)")
        try {
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun put(id: String, body: JSONObject): Boolean {
        val c = URL("$DB$id.json").openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        return try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            c.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    private fun tag(e: String, name: String): String =
        Regex("<$name[^>]*>([\\s\\S]*?)</$name>").find(e)?.groupValues?.get(1) ?: ""

    @Suppress("DEPRECATION")
    private fun plain(html: String): String {
        // الـ RSS فيه HTML مشفّر مرتين: منفكّه ومنشيل الوسوم
        val once = Html.fromHtml(html).toString()
        return Html.fromHtml(once).toString().replace(Regex("\\s+"), " ").trim()
    }

    /** بيرجع كم بوست جديد انرفع */
    fun sync(ctx: Context): Int {
        val xml = get(FEED)
        val prefs = Repo.prefs(ctx)
        val sent = prefs.getStringSet("redditSent", emptySet())!!.toMutableSet()
        var count = 0
        for (m in Regex("<entry>([\\s\\S]*?)</entry>").findAll(xml)) {
            val e = m.groupValues[1]
            val title = plain(tag(e, "title"))
            if (!title.trimStart().startsWith("[hiring]", ignoreCase = true)) continue
            val link = Regex("<link[^>]*href=\"([^\"]+)\"").find(e)?.groupValues?.get(1) ?: continue
            val id = Regex("/comments/(\\w+)/").find(link)?.groupValues?.get(1) ?: continue
            if (id in sent) continue
            val text = plain(tag(e, "content")).take(1800)
            if (!KEY.containsMatchIn("$title $text")) { sent.add(id); continue }
            val created = runCatching {
                OffsetDateTime.parse(tag(e, "published").ifBlank { tag(e, "updated") }.trim()).toEpochSecond()
            }.getOrDefault(System.currentTimeMillis() / 1000)
            val url = link.replace(Regex("^https?://(www\\.|old\\.)?reddit\\.com"), "https://www.reddit.com")
            val ok = put(id, JSONObject()
                .put("title", title.take(290))
                .put("url", url)
                .put("text", text)
                .put("created", created))
            if (ok) { sent.add(id); count++ }
        }
        // منخلي آخر 300 بس عشان ما تكبر الذاكرة
        prefs.edit().putStringSet("redditSent", sent.toList().takeLast(300).toSet()).apply()
        return count
    }
}
