package com.montecarlo.meai

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Freelancer: الـ Agent على GitHub بيقرأ المشاريع كل ساعتين، بس المشاريع هناك بتنزحم خلال ساعة.
 * فالموبايل بيقرأ المشاريع الجديدة كل 30 دقيقة (من الـ API العام، بدون مفتاح)،
 * وأول ما يلاقي مشروع تطبيق أو AI Agent جديد وفاضي نسبياً بيبعت إشعار فوري بصوت.
 * الحكم الكامل والعرض الجاهز بيضلّوا من شغل الـ Agent.
 */
object FreelancerRelay {
    private val QUERIES = listOf("android app", "mobile app", "website to app", "google play", "ai agent", "kotlin", "vibe coding", "android ios app")
    private val FIT = Regex("android|mobile app|\\bapps?\\b|kotlin|apk|play store|google play|webview|website to app|ai agent|agentic|vibe ?cod|claude|chatgpt", RegexOption.IGNORE_CASE)
    private val OFF = Regex("flutter|react native|unity|wordpress|shopify|logo|design only|seo|marketing|video|testers?\\b|reviews|installs|data entry|n8n|zapier|make\\.com|full[- ]?time|on-?site|hybrid", RegexOption.IGNORE_CASE)
    private val BLOCK = setOf("India", "Pakistan", "Bangladesh")
    private const val MAX_BIDS = 15
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** بيرجع كم مشروع جديد مناسب لقى */
    fun sync(ctx: Context): Int {
        val prefs = Repo.prefs(ctx)
        val sent = prefs.getStringSet("freelancerSent", emptySet())!!.toMutableSet()
        val first = sent.isEmpty() // أول مرة: منسجّل الموجود بدون إشعارات (عشان ما يطلع سيل إشعارات قديمة)
        val now = System.currentTimeMillis() / 1000
        val fresh = mutableListOf<Triple<String, String, String>>() // (العنوان، التفاصيل، الرابط)
        for (q in QUERIES) {
            val body = get(
                "https://www.freelancer.com/api/projects/0.1/projects/active/?query=" + URLEncoder.encode(q, "UTF-8") +
                    "&limit=20&job_details=true&user_details=true&user_country_details=true"
            ) ?: continue
            val res = runCatching { JSONObject(body).getJSONObject("result") }.getOrNull() ?: continue
            val users = res.optJSONObject("users") ?: JSONObject()
            val list = res.optJSONArray("projects") ?: continue
            for (i in 0 until list.length()) {
                val p = list.optJSONObject(i) ?: continue
                val id = p.optLong("id").toString()
                if (id == "0" || id in sent) continue
                sent.add(id)
                if (first) continue
                val title = p.optString("title")
                val seo = p.optString("seo_url")
                if (title.isBlank() || seo.isBlank()) continue
                // جديد بس: آخر 3 ساعات، وعليه 15 عرض أو أقل
                if (now - p.optLong("time_submitted", now) > 3 * 3600) continue
                val bids = p.optJSONObject("bid_stats")?.optInt("bid_count", 0) ?: 0
                if (bids > MAX_BIDS) continue
                val skills = (0 until (p.optJSONArray("jobs")?.length() ?: 0))
                    .joinToString(", ") { p.optJSONArray("jobs")!!.optJSONObject(it)?.optString("name") ?: "" }
                if (!FIT.containsMatchIn("$title $skills") || OFF.containsMatchIn(title)) continue
                // الميزانية بالدولار: 100 وفوق (أو 10 بالساعة). الهند وباكستان وبنغلادش: 150 وفوق (أو 15 بالساعة)
                val cur = p.optJSONObject("currency")
                val rate = cur?.optDouble("exchange_rate", 1.0) ?: 1.0
                val b = p.optJSONObject("budget")
                val max = ((b?.optDouble("maximum", 0.0)?.takeIf { it > 0 } ?: b?.optDouble("minimum", 0.0)) ?: 0.0) * rate
                val hourly = p.optString("type") == "hourly"
                val owner = users.optJSONObject(p.optLong("owner_id").toString())
                val country = owner?.optJSONObject("location")?.optJSONObject("country")?.optString("name") ?: ""
                val blocked = country in BLOCK || (cur?.optString("code") ?: "") in setOf("INR", "PKR", "BDT")
                // Moe ما بده مشاريع من الهند وباكستان وبنغلادش أبداً، حتى لو الميزانية كبيرة
                if (blocked) continue
                val minNeeded = if (hourly) 10.0 else 100.0
                if (max < minNeeded) continue
                val budget = "${b?.optDouble("minimum", 0.0)?.toInt() ?: 0}-${b?.optDouble("maximum", 0.0)?.toInt() ?: 0} ${cur?.optString("code") ?: "USD"}${if (hourly) "/hr" else ""}"
                val detail = listOf(budget, "$bids bids", country).filter { it.isNotBlank() }.joinToString(" · ")
                fresh.add(Triple(title, detail, "https://www.freelancer.com/projects/$seo"))
            }
            Thread.sleep(800)
        }
        prefs.edit().putStringSet("freelancerSent", sent.toList().takeLast(800).toSet()).apply()
        if (fresh.isNotEmpty()) {
            val (t, d, u) = fresh.first()
            val more = if (fresh.size > 1) " (+${fresh.size - 1})" else ""
            Notify.show(ctx, Lang.t(ctx, "🟢 مشروع جديد على Freelancer$more", "🟢 New Freelancer project$more"),
                "$t\n$d", Intent(Intent.ACTION_VIEW, Uri.parse(u)), 5)
        }
        return fresh.size
    }
}
