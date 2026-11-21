package com.montecarlo.meai

import android.content.Context
import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * خمسات بيسكّر على سيرفرات GitHub، بس مش على موبايلك (نفس قصة مستقل).
 * بخمسات المشترين ما بنزّلوا "مشاريع"، بنزّلوا "طلبات خدمات" بالمجتمع.
 * فالموبايل بيقرأ صفحة طلبات المجتمع، بياخد الطلبات اللي إلها علاقة بالتطبيقات والـ AI،
 * بيفتح صفحة كل طلب جديد (الوصف)، وبيرفعه لـ Firebase /khamsat.
 * الـ Job Scout بياخدها من هناك وبيحكم عليها وبيكتبلها عرض بالعربي.
 */
object KhamsatRelay {
    private const val ROOT = "https://meai-agent-ee448-default-rtdb.europe-west1.firebasedatabase.app/"
    private val LISTS = listOf(
        "https://khamsat.com/community/requests"
    )
    // الرابط بيجي "/community/requests/123456-عنوان-الطلب": منوخذ الرقم والمسار كامل
    private val LINK = Regex("href=[\"']((?:https?://(?:www\\.)?khamsat\\.com)?/community/requests/(\\d{3,})[^\"'#?]*)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
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
            // خمسات بيحط رسالة "فعّل JavaScript" جوا noscript: منشيلها عشان ما توصل مكان الوصف
            .replace(Regex("<noscript[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
        return Html.fromHtml(noCode).toString().replace(Regex("\\s+"), " ").trim()
    }

    /** من صفحة الطلب: الوصف (من عنوان الطلب ورايح)، وإذا الطلب مسكّر */
    private fun details(html: String, title: String): JSONObject {
        val text = plain(html)
            .replace(Regex("[^.!؟\n]*(javascript|جافا ?سكريبت)[^.!؟\n]*[.!؟]?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()
        val start = text.indexOf(title.take(30)).takeIf { it >= 0 } ?: 0
        val closed = Regex("هذا الطلب مغلق|تم إغلاق الطلب|الطلب مغلق").containsMatchIn(text)
        return JSONObject()
            .put("text", text.substring(start).take(1800))
            .put("open", !closed)
    }

    /** بيرجع كم طلب جديد انرفع */
    fun sync(ctx: Context): Int {
        val prefs = Repo.prefs(ctx)
        val sent = prefs.getStringSet("khamsatSent", emptySet())!!.toMutableSet()
        val found = LinkedHashMap<String, Pair<String, String>>() // رقم الطلب -> (المسار، العنوان)
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
                val title = plain(m.groupValues[3])
                if (title.length < 8 || title.length > 200) continue
                val path = m.groupValues[1].replace(Regex("^https?://(?:www\\.)?khamsat\\.com"), "")
                found.putIfAbsent(m.groupValues[2], path to title)
                n++
            }
            status.append("HTTP ${p.code} ${p.body.length}ch $n links; ")
        }

        var count = 0
        var opened = 0
        for ((id, v) in found) {
            val (path, title) = v
            if (id in sent) continue
            if (!RELEVANT.containsMatchIn(title) || OFF.containsMatchIn(title)) { sent.add(id); continue }
            if (opened >= 8) break // ما منفتح أكثر من 8 صفحات بالمرة الوحدة
            opened++
            val url = "https://khamsat.com$path"
            val d = runCatching { details(get(url).body, title) }.getOrNull() ?: continue
            if (!d.optBoolean("open", true)) { sent.add(id); continue }
            val ok = put("khamsat/$id", JSONObject()
                .put("title", title.take(290))
                .put("url", url)
                .put("text", d.optString("text"))
                .put("created", System.currentTimeMillis() / 1000))
            if (ok) { sent.add(id); count++ }
            Thread.sleep(1200)
        }
        // حالة آخر قراءة، عشان نعرف من تقرير الـ Agent إذا خمسات عم يفتح من الموبايل
        put("khamsatStatus", JSONObject()
            .put("at", System.currentTimeMillis() / 1000)
            .put("lists", status.toString().take(300))
            .put("found", found.size)
            .put("sent", count))
        prefs.edit().putStringSet("khamsatSent", sent.toList().takeLast(400).toSet()).apply()
        return count
    }
}
