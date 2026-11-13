package com.montecarlo.meai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** شاشة خطة البناء */
class PlanActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var name: String
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val en by lazy { Lang.en(this) }
    private fun t(ar: String, en: String) = if (this.en) en else ar
    // بنشيل علامات التنسيق ** و * اللي بيحطها الموديل أحياناً
    private fun clean(s: String) = s.replace("**", "").replace(Regex("(?m)^\\s*[*•-]\\s+"), "").trim()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        name = intent.getStringExtra("name") ?: run { finish(); return }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(20), dp(18), dp(40))
        }
        col.addView(tv(t("رجوع", "Back"), 15f, C.MUTED).apply { setOnClickListener { finish() } })
        col.addView(tv(t("خطة البناء", "Build plan"), 15f, C.TEAL, true), lp(top = 14))
        col.addView(tv(name, 32f, C.AMBER, true))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content, lp(top = 16))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.BG)
            isFillViewport = true
            addView(col)
        })
        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.3f)
    }
    private fun pill(text: String, bg: Int, fg: Int) = tv(text, 15f, fg, true).apply {
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(40).toFloat()
            setStroke(dp(1), if (bg == C.AMBER) C.AMBER else C.LINE)
        }
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    private fun h2(x: String) = content.addView(tv(x, 14f, C.TEAL, true), lp(top = 24))
    private fun body(x: String) { val c = clean(x); if (c.isNotBlank() && c != "null") content.addView(tv(c, 16f, C.TEXT), lp(top = 6)) }
    private fun bullet(x: String) { val c = clean(x); if (c.isNotBlank()) content.addView(tv("•  $c", 16f, C.TEXT), lp(top = 6)) }
    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, t("انتسخ ✓", "Copied ✓"), Toast.LENGTH_SHORT).show()
    }
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { optString(it) }.filter { it.isNotBlank() }
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

    // ---------- data ----------
    private fun load() {
        content.removeAllViews()
        content.addView(tv(t("عم بجيب الخطة…", "Loading plan…"), 16f, C.MUTED), lp(top = 20))
        io.execute {
            val r = runCatching { Repo.plan(Feedback.key(name)) }
            main.post {
                content.removeAllViews()
                r.onSuccess { if (it == null) waiting() else render(it) }
                 .onFailure { waiting(t("ما قدرت أوصل للخطة، تأكد من النت.", "Couldn't load the plan, check your connection.")) }
            }
        }
    }

    private fun waiting(msg: String = t("محمد عم يشتغل على الخطة 🔨\nبياخد عادةً بين 15 و30 دقيقة، وبيوصلك إشعار لما تجهز.",
        "The agent is working on your plan 🔨\nIt usually takes 15 to 30 minutes. You'll get a notification when it's ready.")) {
        content.addView(tv(msg, 17f, C.TEXT).apply {
            background = GradientDrawable().apply {
                setColor(C.PANEL); cornerRadius = dp(22).toFloat(); setStroke(dp(1), C.LINE)
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }, lp(top = 8))
        content.addView(pill(t("تحديث", "Refresh"), C.PANEL, C.TEXT).apply { setOnClickListener { load() } },
            lp(WRAP_CONTENT, WRAP_CONTENT, 16))
    }

    private fun render(root: JSONObject) {
        val o = if (en) root.optJSONObject("en") ?: root else root
        val first = root.optString("firstPrompt")
        if (first.isNotBlank()) {
            content.addView(pill(t("📋 انسخ أول رسالة لـ Claude", "📋 Copy first prompt for Claude"), C.AMBER, 0xFF1A1205.toInt()).apply {
                setOnClickListener { copy("prompt", first) }
            }, lp(WRAP_CONTENT, WRAP_CONTENT, 4))
        }

        body(o.optString("summary"))

        o.optJSONArray("mvpFeatures").strings().takeIf { it.isNotEmpty() }?.let { h2(t("ميزات النسخة الأولى", "MVP features")); it.forEach(::bullet) }

        o.optJSONArray("screens").objects().takeIf { it.isNotEmpty() }?.let {
            h2(t("الشاشات", "Screens"))
            it.forEach { s -> bullet("${s.optString("name")}: ${s.optString("purpose")}") }
        }

        o.optJSONArray("dataSources").objects().takeIf { it.isNotEmpty() }?.let {
            h2(t("مصادر البيانات", "Data sources"))
            it.forEach { s ->
                val free = if (s.optBoolean("free", false)) t(" (مجاني)", " (free)") else ""
                bullet("${s.optString("name")}$free: ${s.optString("notes")}")
            }
        }

        o.optString("techStack").takeIf { it.isNotBlank() }?.let { h2(t("التقنية", "Tech stack")); body(it) }

        o.optJSONArray("buildSteps").objects().takeIf { it.isNotEmpty() }?.let {
            h2(t("خطة الأيام", "Day-by-day plan"))
            it.forEach { s -> bullet(t("يوم ", "Day ") + "${s.optString("day")}: ${s.optString("task")}") }
        }

        o.optString("beatCompetitors").takeIf { it.isNotBlank() }?.let { h2(t("كيف نتفوق على المنافسين", "How to beat competitors")); body(it) }
        o.optString("monetization").takeIf { it.isNotBlank() }?.let { h2(t("الربح", "Monetization")); body(it) }
        o.optJSONArray("risks").strings().takeIf { it.isNotEmpty() }?.let { h2(t("المخاطر", "Risks")); it.forEach(::bullet) }
        o.optJSONArray("laterFeatures").strings().takeIf { it.isNotEmpty() }?.let { h2(t("للنسخ الجاية", "Later versions")); it.forEach(::bullet) }

        o.optJSONObject("playStore")?.let { ps ->
            h2(t("صفحة Google Play", "Google Play listing"))
            val title = ps.optString("title")
            val short = ps.optString("shortDescription")
            val full = ps.optString("fullDescription")
            val kw = ps.optJSONArray("keywords").strings().joinToString(if (en) ", " else "، ")
            body(t("الاسم: ", "Title: ") + title)
            body(t("وصف قصير: ", "Short description: ") + short)
            body(full)
            if (kw.isNotBlank()) body(t("كلمات مفتاحية: ", "Keywords: ") + kw)
            content.addView(pill(t("انسخ صفحة Play", "Copy Play listing"), C.PANEL, C.TEXT).apply {
                setOnClickListener { copy("play", "$title\n\n$short\n\n$full") }
            }, lp(WRAP_CONTENT, WRAP_CONTENT, 12))
        }

        root.optJSONArray("sources").objects().takeIf { it.isNotEmpty() }?.let {
            h2(t("المصادر", "Sources"))
            it.take(8).forEach { s ->
                content.addView(tv(s.optString("title").ifBlank { s.optString("url") }, 14f, C.CYAN).apply {
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.optString("url")))) } }
                }, lp())
            }
        }
    }
}
