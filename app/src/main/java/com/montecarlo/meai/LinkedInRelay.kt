package com.montecarlo.meai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LinkedIn بيسكّر على سيرفرات GitHub (429)، بس مش على موبايلك.
 * فالموبايل بيدوّر بصفحة الوظائف العامة (Remote: دوام كامل أو جزئي أو عقد، آخر 24 ساعة)
 * وبيرفع النتائج لـ Firebase /linkedin، والـ Job Scout بياخدها من هناك وبيحكم عليها.
 * كل ساعة 3 بحثات بس، وبيلف على باقي الكلمات والمناطق بالساعات الجاية.
 */
object LinkedInRelay {
    private const val DB = "https://meai-agent-ee448-default-rtdb.europe-west1.firebasedatabase.app/linkedin/"
    private val KEYWORDS = listOf(
        "Android Developer", "Android iOS Developer", "Mobile App Developer",
        "AI App Developer", "Kotlin Developer", "Freelance Android Developer", "AI Agent Developer"
    )
    private val PLACES = listOf("United States", "EMEA", "Middle East", "Canada", "Australia")
    // للإشعار الفوري: وظايف تطبيقات أو AI Agents، مش حضوري ولا هايبرد
    private val FIT = Regex("android|kotlin|mobile|\\bios\\b|\\bapps?\\b|ai agent|agentic|llm|gen ?ai", RegexOption.IGNORE_CASE)
    private val ONSITE = Regex("on-?site|hybrid", RegexOption.IGNORE_CASE)
    private val SENIOR = Regex("\\b(senior|sr\\.?|staff|lead|principal|head of|director|manager|architect)\\b", RegexOption.IGNORE_CASE)

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
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

    @Suppress("DEPRECATION")
    private fun plain(html: String): String =
        Html.fromHtml(html).toString().replace(Regex("\\s+"), " ").trim()

    private fun pick(card: String, cls: String, tag: String): String =
        Regex("$cls[^>]*>([\\s\\S]*?)</$tag>").find(card)?.groupValues?.get(1)?.let { plain(it) } ?: ""

    /** بيرجع كم وظيفة جديدة انرفعت */
    fun sync(ctx: Context): Int {
        val prefs = Repo.prefs(ctx)
        val sent = prefs.getStringSet("linkedinSent", emptySet())!!.toMutableSet()
        val combos = KEYWORDS.flatMap { k -> PLACES.map { p -> k to p } }
        val slot = (System.currentTimeMillis() / 3_600_000L).toInt()
        var count = 0
        val fresh = mutableListOf<Triple<String, String, String>>() // (العنوان، الشركة، الرابط)
        for (i in 0 until 3) {
            val (kw, loc) = combos[((slot * 3 + i) % combos.size + combos.size) % combos.size]
            val url = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search?keywords=" +
                URLEncoder.encode(kw, "UTF-8") + "&location=" + URLEncoder.encode(loc, "UTF-8") +
                "&f_WT=2&f_JT=F%2CC%2CP%2CT&f_TPR=r86400&start=0"
            val html = get(url) ?: break   // إذا سكّر، منوقف هالساعة
            for (card in html.split(Regex("<li[ >]")).drop(1)) {
                val id = Regex("jobPosting:(\\d+)").find(card)?.groupValues?.get(1)
                    ?: Regex("jobs/view/[^\"]*?-(\\d{6,})").find(card)?.groupValues?.get(1)
                    ?: continue
                if (id in sent) continue
                val title = pick(card, "base-search-card__title", "h3")
                if (title.isBlank()) continue
                sent.add(id)
                if (SENIOR.containsMatchIn(title)) continue
                val company = pick(card, "base-search-card__subtitle", "h4")
                val place = pick(card, "job-search-card__location", "span")
                // الهند وباكستان وبنغلادش: ما منرفعها أبداً
                if (Regex("india|pakistan|bangladesh|bengaluru|bangalore|mumbai|delhi|hyderabad|chennai|pune|kolkata|noida|gurgaon|gurugram|karachi|lahore|islamabad|dhaka", RegexOption.IGNORE_CASE).containsMatchIn("$place $company")) continue
                val ok = put(id, JSONObject()
                    .put("title", title.take(290))
                    .put("company", company.take(190))
                    .put("location", place.take(190))
                    .put("raw", listOf(title, company, place).filter { it.isNotBlank() }.joinToString(" | ").take(900))
                    .put("url", "https://www.linkedin.com/jobs/view/$id/")
                    .put("created", System.currentTimeMillis() / 1000))
                if (ok) {
                    count++
                    if (FIT.containsMatchIn(title) && !ONSITE.containsMatchIn(place)) fresh.add(Triple(title, company, "https://www.linkedin.com/jobs/view/$id/"))
                }
            }
            Thread.sleep(4000L + (Math.random() * 4000).toLong())
        }
        prefs.edit().putStringSet("linkedinSent", sent.toList().takeLast(500).toSet()).apply()
        // إشعار فوري بصوت لأول وظيفة مناسبة (الـ Agent بيحكم عليها كمان بعدين وبيكتب العرض)
        if (fresh.isNotEmpty()) {
            val (t, co, u) = fresh.first()
            val more = if (fresh.size > 1) " (+${fresh.size - 1})" else ""
            Notify.show(ctx, Lang.t(ctx, "🔷 وظيفة Remote جديدة على LinkedIn$more", "🔷 New remote job on LinkedIn$more"),
                listOf(t, co).filter { it.isNotBlank() }.joinToString(" · "), Intent(Intent.ACTION_VIEW, Uri.parse(u)), 6)
        }
        return count
    }
}
