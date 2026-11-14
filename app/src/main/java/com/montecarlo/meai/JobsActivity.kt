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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** شاشة "شغل": الفرص اللي لقاها الـ Agent اليوم، مع عرض جاهز لكل وحدة */
class JobsActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var dateTv: TextView
    private var shown = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val en by lazy { Lang.en(this) }
    private fun t(ar: String, en: String) = if (this.en) en else ar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        setContentView(buildUi())
        Jobs.cached(this)?.let { show(it) }
        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        setPadding(dp(16), dp(9), dp(16), dp(9))
    }

    private fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun h2(t: String) = tv(t, 14f, C.TEAL, true)

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast(t("ما قدرت أفتح الرابط", "Couldn't open the link")) }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(20), dp(18), dp(40))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv(t("شغل اليوم", "Today's work"), 32f, C.AMBER, true))
        titles.addView(tv(t("فرص فريلانس بتناسبك، مع عرض جاهز", "Freelance leads that fit you, with a ready proposal"), 15f, C.MUTED))
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(pill(t("رجوع", "Back"), C.PANEL, C.TEXT).apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(top, lp())

        dateTv = tv("", 15f, C.MUTED)
        col.addView(dateTv, lp(top = 10))

        col.addView(pill(t("تحديث", "Refresh"), C.PANEL, C.TEXT).apply { setOnClickListener { load() } },
            lp(WRAP_CONTENT, WRAP_CONTENT, 10))

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(content, lp(top = 18))

        return ScrollView(this).apply {
            setBackgroundColor(C.BG)
            isFillViewport = true
            addView(col)
        }
    }

    // ---------- Data ----------

    private fun setState(msg: String) {
        content.removeAllViews()
        content.addView(tv(msg, 16f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(30), dp(10), dp(30))
        }, lp())
    }

    private fun load() {
        if (!shown) setState(t("عم بجيب الفرص…", "Loading leads…"))
        io.execute {
            val r = runCatching { Jobs.latest(this) }
            main.post {
                r.onSuccess {
                    Repo.prefs(this).edit().putString("jobsSeen", it.date).apply()
                    show(it)
                }.onFailure {
                    if (!shown) setState(t(
                        "ما في تقرير شغل لسا.\nمن GitHub افتح Actions وشغّل Me.ai Daily Job Scout، وارجع بعد 3 دقايق.",
                        "No job report yet.\nOpen GitHub Actions and run Me.ai Daily Job Scout, then come back in 3 minutes."
                    ))
                    else toast(t("ما قدرت أحدّث، تأكد من النت", "Couldn't refresh, check your connection"))
                }
            }
        }
    }

    private fun fmt(d: String) = runCatching {
        LocalDate.parse(d).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", if (en) Locale.ENGLISH else Locale("ar")))
    }.getOrDefault(d)

    private fun show(r: JobsReport) {
        shown = true
        dateTv.text = fmt(r.date)
        content.removeAllViews()

        if (r.summary.isNotBlank()) content.addView(tv(r.summary, 17f, C.TEXT).apply {
            background = GradientDrawable().apply {
                setColor(C.PANEL)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), C.LINE)
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }, lp())

        if (r.leads.isNotEmpty()) {
            content.addView(h2(t("الفرص حسب قد إيش بتناسبك", "Leads by how well they fit you")), lp(top = 26))
            r.leads.forEach {
                content.addView(card(it), lp())
                content.addView(View(this).apply { setBackgroundColor(C.LINE) }, lp(h = dp(1)))
            }
        }

        if (r.rejected.isNotEmpty()) {
            content.addView(h2(t("فرص رفضها الـ Agent", "Leads the agent skipped")), lp(top = 26))
            r.rejected.forEach { s ->
                content.addView(tv(s.title, 15f, C.MUTED, true).apply {
                    if (s.url.isNotBlank()) setOnClickListener { openUrl(s.url) }
                }, lp(top = 8))
                if (s.reason.isNotBlank()) content.addView(tv(s.reason, 14f, C.MUTED), lp())
            }
        }
    }

    private fun marketLabel(m: String) = when (m) {
        "arab" -> t("عربي", "Arab")
        "west" -> t("أمريكا وأوروبا", "US & Europe")
        "east" -> t("الشرق الأقصى", "Far East")
        else -> ""
    }

    private fun card(x: Lead): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ScoreView(this, x.fit), LinearLayout.LayoutParams(dp(56), dp(56)))
        val txt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(14), 0, 0, 0)
        }
        val applied = Jobs.applied(this, x.url)
        txt.addView(tv((if (applied) "✅ " else "") + x.title, 18f, C.TEXT, true))
        val meta = listOf(x.platform, marketLabel(x.market), x.country, x.budget).filter { it.isNotBlank() }
            .joinToString(" · ")
        if (meta.isNotBlank()) txt.addView(tv(meta, 14f, C.AMBER))
        val meta2 = listOf(
            x.posted,
            if (x.proposals.isNotBlank()) t("${x.proposals} عرض", "${x.proposals} proposals") else ""
        ).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta2.isNotBlank()) txt.addView(tv(meta2, 13f, C.MUTED))
        head.addView(txt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPaddingRelative(dp(70), dp(4), 0, 0)
        }
        fun row(k: String, v: String) {
            if (v.isBlank()) return
            details.addView(tv(k, 13f, C.TEAL, true).apply { setPadding(0, dp(8), 0, 0) })
            details.addView(tv(v, 16f, C.TEXT))
        }
        row(t("شو بدهم", "What they need"), x.summary)
        row(t("ليش بتناسبك", "Why it fits you"), x.why)
        row(t("انتبه", "Watch out"), x.watch)

        if (x.proposal.isNotBlank()) {
            details.addView(tv(t("العرض الجاهز", "Ready proposal"), 13f, C.TEAL, true).apply { setPadding(0, dp(8), 0, 0) })
            details.addView(tv(x.proposal, 15f, C.TEXT).apply {
                layoutDirection = if (x.language == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                textDirection = if (x.language == "ar") View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
                background = GradientDrawable().apply {
                    setColor(C.PANEL)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), C.LINE)
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextIsSelectable(true)
            }, lp(top = 6))
        }

        val copyLabel = t("📋 انسخ العرض", "📋 Copy proposal")
        val copy = pill(copyLabel, C.AMBER, 0xFF1A1205.toInt())
        copy.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("proposal", x.proposal))
            copy.text = t("انتسخ ✓", "Copied ✓")
            copy.postDelayed({ copy.text = copyLabel }, 2000)
        }
        if (x.proposal.isNotBlank()) details.addView(copy, lp(WRAP_CONTENT, WRAP_CONTENT, 12))

        details.addView(pill(t("🔗 افتح المشروع", "🔗 Open project"), C.PANEL, C.TEXT).apply {
            setOnClickListener { openUrl(x.url) }
        }, lp(WRAP_CONTENT, WRAP_CONTENT, 10))

        val appliedLabel = { on: Boolean -> if (on) t("✅ قدّمت عليها", "✅ Applied") else t("علّمها إني قدّمت", "Mark as applied") }
        val mark = pill(appliedLabel(applied), C.PANEL, C.TEXT)
        mark.setOnClickListener {
            val now = !Jobs.applied(this, x.url)
            Jobs.setApplied(this, x.url, now)
            mark.text = appliedLabel(now)
            (txt.getChildAt(0) as TextView).text = (if (now) "✅ " else "") + x.title
        }
        details.addView(mark, lp(WRAP_CONTENT, WRAP_CONTENT, 10))

        head.setOnClickListener {
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        box.addView(head)
        box.addView(details)
        return box
    }
}
