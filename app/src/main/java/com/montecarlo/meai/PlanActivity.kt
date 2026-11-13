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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        name = intent.getStringExtra("name") ?: run { finish(); return }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(20), dp(18), dp(40))
        }
        col.addView(tv("رجوع", 15f, C.MUTED).apply { setOnClickListener { finish() } })
        col.addView(tv("خطة البناء", 15f, C.TEAL, true), lp(top = 14))
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
    private fun h2(t: String) = content.addView(tv(t, 14f, C.TEAL, true), lp(top = 24))
    private fun body(t: String) { if (t.isNotBlank() && t != "null") content.addView(tv(t, 16f, C.TEXT), lp(top = 6)) }
    private fun bullet(t: String) { if (t.isNotBlank()) content.addView(tv("•  $t", 16f, C.TEXT), lp(top = 6)) }
    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "انتسخ ✓", Toast.LENGTH_SHORT).show()
    }
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { optString(it) }.filter { it.isNotBlank() }
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()

    // ---------- data ----------
    private fun load() {
        content.removeAllViews()
        content.addView(tv("عم بجيب الخطة…", 16f, C.MUTED), lp(top = 20))
        io.execute {
            val r = runCatching { Repo.plan(Feedback.key(name)) }
            main.post {
                content.removeAllViews()
                r.onSuccess { if (it == null) waiting() else render(it) }
                 .onFailure { waiting("ما قدرت أوصل للخطة، تأكد من النت.") }
            }
        }
    }

    private fun waiting(msg: String = "محمد عم يشتغل على الخطة 🔨\nبياخد عادةً بين 15 و30 دقيقة، وبيوصلك إشعار لما تجهز.") {
        content.addView(tv(msg, 17f, C.TEXT).apply {
            background = GradientDrawable().apply {
                setColor(C.PANEL); cornerRadius = dp(22).toFloat(); setStroke(dp(1), C.LINE)
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }, lp(top = 8))
        content.addView(pill("تحديث", C.PANEL, C.TEXT).apply { setOnClickListener { load() } },
            lp(WRAP_CONTENT, WRAP_CONTENT, 16))
    }

    private fun render(o: JSONObject) {
        val first = o.optString("firstPrompt")
        if (first.isNotBlank()) {
            content.addView(pill("📋 انسخ أول رسالة لـ Claude", C.AMBER, 0xFF1A1205.toInt()).apply {
                setOnClickListener { copy("prompt", first) }
            }, lp(WRAP_CONTENT, WRAP_CONTENT, 4))
        }

        body(o.optString("summary"))

        o.optJSONArray("mvpFeatures").strings().takeIf { it.isNotEmpty() }?.let { h2("ميزات النسخة الأولى"); it.forEach(::bullet) }

        o.optJSONArray("screens").objects().takeIf { it.isNotEmpty() }?.let {
            h2("الشاشات")
            it.forEach { s -> bullet("${s.optString("name")}: ${s.optString("purpose")}") }
        }

        o.optJSONArray("dataSources").objects().takeIf { it.isNotEmpty() }?.let {
            h2("مصادر البيانات")
            it.forEach { s ->
                val free = if (s.optBoolean("free", false)) " (مجاني)" else ""
                bullet("${s.optString("name")}$free: ${s.optString("notes")}")
            }
        }

        o.optString("techStack").takeIf { it.isNotBlank() }?.let { h2("التقنية"); body(it) }

        o.optJSONArray("buildSteps").objects().takeIf { it.isNotEmpty() }?.let {
            h2("خطة الأيام")
            it.forEach { s -> bullet("يوم ${s.optString("day")}: ${s.optString("task")}") }
        }

        o.optString("beatCompetitors").takeIf { it.isNotBlank() }?.let { h2("كيف نتفوق على المنافسين"); body(it) }
        o.optString("monetization").takeIf { it.isNotBlank() }?.let { h2("الربح"); body(it) }
        o.optJSONArray("risks").strings().takeIf { it.isNotEmpty() }?.let { h2("المخاطر"); it.forEach(::bullet) }
        o.optJSONArray("laterFeatures").strings().takeIf { it.isNotEmpty() }?.let { h2("للنسخ الجاية"); it.forEach(::bullet) }

        o.optJSONObject("playStore")?.let { ps ->
            h2("صفحة Google Play")
            val title = ps.optString("title")
            val short = ps.optString("shortDescription")
            val full = ps.optString("fullDescription")
            val kw = ps.optJSONArray("keywords").strings().joinToString("، ")
            body("الاسم: $title")
            body("وصف قصير: $short")
            body(full)
            if (kw.isNotBlank()) body("كلمات مفتاحية: $kw")
            content.addView(pill("انسخ صفحة Play", C.PANEL, C.TEXT).apply {
                setOnClickListener { copy("play", "$title\n\n$short\n\n$full") }
            }, lp(WRAP_CONTENT, WRAP_CONTENT, 12))
        }

        o.optJSONArray("sources").objects().takeIf { it.isNotEmpty() }?.let {
            h2("المصادر")
            it.take(8).forEach { s ->
                content.addView(tv(s.optString("title").ifBlank { s.optString("url") }, 14f, C.CYAN).apply {
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.optString("url")))) } }
                }, lp())
            }
        }
    }
}
