package com.montecarlo.meai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * مستقل بيسكّر على سيرفرات GitHub، بس مش على موبايلك.
 * فالموبايل بيقرأ أحدث مشاريع البرمجة على مستقل (صفحة عامة، متل ما بتفتحها بالمتصفح)،
 * بياخد المشاريع اللي إلها علاقة بالتطبيقات والـ AI، بيفتح صفحة كل مشروع جديد
 * (الوصف والميزانية وعدد العروض)، وبيرفعها لـ Firebase /mostaql.
 * الـ Job Scout بياخدها من هناك وبيحكم عليها وبيكتبلها عرض بالعربي.
 */
object MostaqlRelay {
    private const val ROOT = "https://meai-agent-ee448-default-rtdb.europe-west1.firebasedatabase.app/"
    private val LISTS = listOf(
        "https://mostaql.com/projects?category=development&sort=latest",
        "https://mostaql.com/projects?category=ai-machine-learning&sort=latest",
        "https://mostaql.com/projects?keyword=%D8%AA%D8%B7%D8%A8%D9%8A%D9%82&sort=latest" // "تطبيق"
    )
    private val LINK = Regex("href=[\"'](?:https?://(?:www\\.)?mostaql\\.com)?/project/(\\d{4,})[^\"']*[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
    // للإشعار الفوري بس: شغل تطبيقات أندرويد فعلاً (الباقي بيحكم عليه الـ Agent)
    private val APP = Regex("تطبيق|أندرويد|اندرويد|android|apk|aab|play|جوجل بلاي|قوقل بلاي|متجر جوجل|تحويل موقع|webview|kotlin|كوتلن", RegexOption.IGNORE_CASE)
    // تصميم وتسويق: كلمة "تطبيق" فيها بس مش شغل برمجة، فما منبعت إلها إشعار
    private val NOT_DEV = Regex("تصميم|مصمم|figma|فيجما|wireframe|ui/ux|هوية|شعار|لوجو|logo|تسويق|ماركيتنج|marketing|إعلان|اعلان|حملات|سوشيال|مونتاج|فيديو|كتابة|محتوى|ترجمة|seo", RegexOption.IGNORE_CASE)
    private val RELEVANT = Regex("تطبيق|أندرويد|اندرويد|android|موبايل|جوال|هاتف|ذكاء اصطناعي|ذكاء إصطناعي|\\bai\\b|بوت|شات|chat|gpt|فايربيس|firebase|play|متجر جوجل|قوقل بلاي|جوجل بلاي|webview|تحويل موقع|أتمتة|وكيل|kotlin|كوتلن|apk", RegexOption.IGNORE_CASE)
    private val OFF = Regex("flutter|فلاتر|react native|ios فقط|آيفون فقط|ووردبريس|wordpress|شوبيفاي|متجر سلة|منصة زد|تصميم شعار|لوجو|مونتاج|كتابة محتوى|ترجمة|إعلانات ممولة", RegexOption.IGNORE_CASE)
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private class Page(val code: Int, val body: String)

    private fun get(url: String): Page {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            Page(code, body)
        } finally {
            c.disconnect()
        }
    }

    private fun put(path: String, body: JSONObject): Boolean {
        val c = URL("$ROOT$path.json").openConnection() as HttpURLConnection
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

    @Suppress("DEPRECATION")
    private fun plain(html: String): String {
        val noCode = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        return Html.fromHtml(noCode).toString().replace(Regex("\\s+"), " ").trim()
    }

    /** من صفحة المشروع: الوصف والميزانية وعدد العروض وإذا لسا مفتوح */
    private fun details(html: String): JSONObject {
        val text = plain(html)
        val start = listOf("تفاصيل المشروع", "وصف المشروع").map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val budget = Regex("\\$\\s?[\\d,.]+\\s*-\\s*\\$\\s?[\\d,.]+").find(text)?.value ?: ""
        val offers = Regex("عدد العروض\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val open = !Regex("حالة المشروع\\s*(مغلق|مكتمل|ملغي|بانتظار)").containsMatchIn(text)
        return JSONObject()
            .put("text", text.substring(start).take(1800))
            .put("budget", budget)
            .put("offers", offers)
            .put("open", open)
    }

    /** بيرجع كم مشروع جديد انرفع */
    fun sync(ctx: Context): Int {
        val prefs = Repo.prefs(ctx)
        val sent = prefs.getStringSet("mostaqlSent", emptySet())!!.toMutableSet()
        val found = LinkedHashMap<String, String>() // رقم المشروع -> العنوان
        val status = StringBuilder()
        for (u in LISTS) {
            val p = try {
                get(u)
            } catch (e: Exception) {
                status.append("err ${e.message?.take(40)}; ")
                continue
            }
            var n = 0
            for (m in LINK.findAll(p.body)) {
                val title = plain(m.groupValues[2])
                if (title.length < 8 || title.length > 200) continue
                found.putIfAbsent(m.groupValues[1], title)
                n++
            }
            status.append("HTTP ${p.code} ${p.body.length}ch $n links; ")
        }

        var count = 0
        var opened = 0
        val fresh = mutableListOf<Pair<String, String>>()
        for ((id, title) in found) {
            if (id in sent) continue
            if (!RELEVANT.containsMatchIn(title) || OFF.containsMatchIn(title)) { sent.add(id); continue }
            if (opened >= 8) break // ما منفتح أكثر من 8 صفحات بالمرة الوحدة
            opened++
            val url = "https://mostaql.com/project/$id"
            val d = runCatching { details(get(url).body) }.getOrNull() ?: continue
            if (!d.optBoolean("open", true)) { sent.add(id); continue }
            val ok = put("mostaql/$id", JSONObject()
                .put("title", title.take(290))
                .put("url", url)
                .put("text", d.optString("text"))
                .put("budget", d.optString("budget"))
                .put("offers", d.optInt("offers", -1))
                .put("created", System.currentTimeMillis() / 1000))
            if (ok) {
                sent.add(id); count++
                val offers = d.optInt("offers", -1)
                if (APP.containsMatchIn(title) && !NOT_DEV.containsMatchIn(title) && offers <= 15) fresh.add(title to url)
            }
            Thread.sleep(1200)
        }
        // حالة آخر قراءة، عشان نعرف من تقرير الـ Agent إذا مستقل عم يفتح من الموبايل
        put("mostaqlStatus", JSONObject()
            .put("at", System.currentTimeMillis() / 1000)
            .put("lists", status.toString().take(300))
            .put("found", found.size)
            .put("sent", count))
        prefs.edit().putStringSet("mostaqlSent", sent.toList().takeLast(400).toSet()).apply()
        // إشعار فوري من الموبايل لمشاريع التطبيقات، بدون ما نستنى جولة الـ Agent
        if (fresh.isNotEmpty()) {
            val (t, u) = fresh.first()
            val more = if (fresh.size > 1) " (+${fresh.size - 1})" else ""
            Notify.show(ctx, Lang.t(ctx, "🔵 مشروع تطبيق جديد على مستقل$more", "🔵 New app project on Mostaql$more"), t, Intent(Intent.ACTION_VIEW, Uri.parse(u)), 4)
        }
        return count
    }
}
