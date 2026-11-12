package com.montecarlo.meai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var avatar: AvatarView
    private lateinit var dateTv: TextView
    private lateinit var speakBtn: TextView
    private lateinit var content: LinearLayout
    private var report: Report? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val speakLabel = "🔊 اسمع الملخص"
    private val copyLabel = "انسخ الفكرة لـ Claude"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        setContentView(buildUi())
        tts = TextToSpeech(this, this)
        Notify.channel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        ScoutWorker.schedule(this)
        Repo.cached(this)?.let { show(it) }
        load(null)
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
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

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(20), dp(18), dp(40))
        }
        col.addView(tv("محمد", 36f, C.AMBER, true))
        col.addView(tv("كشّاف الأفكار اليومي", 16f, C.MUTED))

        avatar = AvatarView(this)
        col.addView(avatar, LinearLayout.LayoutParams(dp(190), dp(190)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        dateTv = tv("", 15f, C.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(dateTv, lp(top = 4))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        speakBtn = pill(speakLabel, C.AMBER, 0xFF1A1205.toInt()).apply { setOnClickListener { speak() } }
        val arch = pill("الأرشيف", C.PANEL, C.TEXT).apply { setOnClickListener { archive() } }
        val ref = pill("تحديث", C.PANEL, C.TEXT).apply { setOnClickListener { load(null) } }
        listOf(speakBtn, arch, ref).forEachIndexed { i, v ->
            actions.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        col.addView(actions, lp(top = 14))

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

    private fun load(date: String?) {
        if (report == null) setState("عم بجيب التقرير…")
        io.execute {
            val r = runCatching { if (date == null) Repo.latest(this) else Repo.byDate(date) }
            main.post {
                r.onSuccess {
                    if (date == null) Repo.prefs(this).edit().putString("lastSeen", it.date).apply()
                    show(it)
                }.onFailure {
                    if (report == null) setState("ما في تقرير لسا.\nمن GitHub افتح Actions وشغّل Me.ai Daily Idea Scout، وارجع بعد دقيقتين.")
                    else toast("ما قدرت أحدّث، تأكد من النت")
                }
            }
        }
    }

    private fun fmt(d: String) = runCatching {
        LocalDate.parse(d).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale("ar")))
    }.getOrDefault(d)

    private fun show(r: Report) {
        report = r
        dateTv.text = fmt(r.date)
        content.removeAllViews()

        content.addView(tv(r.summary, 17f, C.TEXT).apply {
            background = GradientDrawable().apply {
                setColor(C.PANEL)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), C.LINE)
            }
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }, lp())

        if (r.trends.isNotEmpty()) {
            content.addView(h2("شو ماشي بالسوق"), lp(top = 26))
            r.trends.forEach { t ->
                content.addView(tv(t, 15f, 0xFFCFDAD8.toInt()).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), C.LINE)
                    }
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }, lp(WRAP_CONTENT, WRAP_CONTENT, 8))
            }
        }

        content.addView(h2("الأفكار حسب قوة الفرصة"), lp(top = 26))
        r.ideas.forEach {
            content.addView(card(it), lp())
            content.addView(View(this).apply { setBackgroundColor(C.LINE) }, lp(h = dp(1)))
        }

        if (r.rejected.isNotEmpty()) {
            content.addView(h2("أفكار رفضها محمد"), lp(top = 26))
            r.rejected.forEach { rj ->
                content.addView(tv(rj.name, 16f, C.MUTED, true), lp(top = 8))
                if (rj.reason.isNotBlank()) content.addView(tv(rj.reason, 14f, C.MUTED), lp())
            }
        }

        if (r.sources.isNotEmpty()) {
            content.addView(h2("المصادر"), lp(top = 26))
            r.sources.take(8).forEach { s ->
                content.addView(tv(s.title.ifBlank { s.url }, 14f, C.CYAN).apply {
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) }
                    }
                }, lp())
            }
        }
    }

    private fun card(x: Idea): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ScoreView(this, x.score), LinearLayout.LayoutParams(dp(56), dp(56)))
        val txt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(14), 0, 0, 0)
        }
        txt.addView(tv(x.name, 19f, C.TEXT, true))
        if (x.pitch.isNotBlank()) txt.addView(tv(x.pitch, 15f, C.MUTED))
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
        row("المشكلة", x.problem)
        row("لمين", x.audience)
        row("ليش هلأ", x.whyNow)
        row("المنافسة", x.competition)
        row("المنافسين", x.competitors.joinToString("، "))
        row("كيف تتفوق عليهم", x.edge)
        row("الربح", x.monetization)
        row("البناء", listOf(x.difficulty, if (x.days > 0) "حوالي ${x.days} يوم" else "")
            .filter { it.isNotBlank() }.joinToString("، "))
        row("أول خطوة", x.firstStep)

        // 👍 / 👎
        val votes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val up = pill("👍 عجبتني", C.PANEL, C.TEXT)
        val down = pill("👎 مش لإلي", C.PANEL, C.TEXT)
        fun paint(v: String?) {
            listOf(up to "up", down to "down").forEach { (b, k) ->
                val on = v == k
                (b.background as GradientDrawable).setColor(if (on) C.AMBER else C.PANEL)
                b.setTextColor(if (on) 0xFF1A1205.toInt() else C.TEXT)
            }
        }
        paint(Feedback.local(this, x.name))
        fun vote(k: String) {
            val next = if (Feedback.local(this, x.name) == k) null else k
            paint(next)
            io.execute {
                val ok = Feedback.send(this, x, next)
                main.post {
                    if (ok) toast(if (next == null) "انشال التقييم" else "محمد رح يتعلم من هالتقييم")
                    else { paint(Feedback.local(this, x.name)); toast("ما انحفظ، تأكد من النت") }
                }
            }
        }
        up.setOnClickListener { vote("up") }
        down.setOnClickListener { vote("down") }
        votes.addView(up, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        votes.addView(down, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        details.addView(votes, lp(WRAP_CONTENT, WRAP_CONTENT, 14))

        val copy = pill(copyLabel, 0xFF0E1D1C.toInt(), C.TEXT)
        copy.setOnClickListener {
            copyIdea(x)
            copy.text = "انتسخت ✓"
            copy.postDelayed({ copy.text = copyLabel }, 2000)
        }
        details.addView(copy, lp(WRAP_CONTENT, WRAP_CONTENT, 14))

        head.setOnClickListener {
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        box.addView(head)
        box.addView(details)
        return box
    }

    private fun copyIdea(x: Idea) {
        val text = """بدي أبني تطبيق اسمه ${x.name}.
الفكرة: ${x.pitch}
المشكلة: ${x.problem}
الجمهور: ${x.audience}
الربح: ${x.monetization}
بدي ياه Kotlin أصلي زي Flicker، وأعطيني الملفات جاهزة للرفع على GitHub."""
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("idea", text))
    }

    private fun archive() {
        io.execute {
            val d = runCatching { Repo.dates() }.getOrDefault(emptyList())
            main.post {
                if (d.isEmpty()) {
                    toast("الأرشيف فاضي لسا")
                    return@post
                }
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("التقارير السابقة")
                    .setItems(d.toTypedArray()) { _, i -> load(d[i]) }
                    .show()
            }
        }
    }

    // ---------- Voice ----------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val res = tts.setLanguage(Locale("ar"))
        ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                main.post { avatar.speaking = true; speakBtn.text = "⏹ وقّف" }
            }
            override fun onDone(id: String?) { main.post { stopUi() } }
            override fun onError(id: String?) { main.post { stopUi() } }
        })
    }

    private fun stopUi() {
        avatar.speaking = false
        speakBtn.text = speakLabel
    }

    private fun speak() {
        val r = report ?: return
        if (tts.isSpeaking) {
            tts.stop()
            stopUi()
            return
        }
        if (!ttsReady) {
            toast("نزّل صوت عربي من إعدادات تحويل النص إلى كلام")
            return
        }
        val top = r.ideas.take(3).joinToString("، ") { it.name }
        tts.speak("${r.summary}. أقوى الأفكار اليوم: $top", TextToSpeech.QUEUE_FLUSH, null, "summary")
    }
}
