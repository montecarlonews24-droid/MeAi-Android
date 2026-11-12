package com.montecarlo.meai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.min
import kotlin.math.sin

object C {
    val BG = 0xFF000000.toInt()
    val PANEL = 0xFF0B1312.toInt()
    val LINE = 0xFF1D3532.toInt()
    val TEAL = 0xFF4F9E96.toInt()
    val CYAN = 0xFF62E2E6.toInt()
    val AMBER = 0xFFEBA33E.toInt()
    val TEXT = 0xFFE8EEED.toInt()
    val MUTED = 0xFF8A9C9A.toInt()
}

/** راس محمد: حلقة متوهجة، عيون، وتم بيتحرك وهو بيحكي */
class AvatarView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f

    var speaking = false
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.74f
        val pulse = if (speaking) sin(phase) * 0.5f + 0.5f else 0f

        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r * 1.34f, intArrayOf(0x664F9E96, 0x00000000),
            floatArrayOf(0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.34f, p)
        p.shader = null

        p.color = C.BG
        c.drawCircle(cx, cy, r, p)

        p.style = Paint.Style.STROKE
        p.color = C.TEAL
        p.strokeWidth = r * 0.05f
        c.drawCircle(cx, cy, r, p)
        p.color = if (speaking) C.CYAN else 0xFF22403C.toInt()
        p.strokeWidth = r * 0.02f
        c.drawCircle(cx, cy, r * 0.88f, p)

        p.style = Paint.Style.FILL
        p.color = C.CYAN
        val ey = cy - r * 0.12f
        c.drawCircle(cx - r * 0.3f, ey, r * 0.08f, p)
        c.drawCircle(cx + r * 0.3f, ey, r * 0.08f, p)

        p.color = C.AMBER
        val mh = r * (0.05f + 0.16f * pulse)
        val my = cy + r * 0.32f
        c.drawRoundRect(cx - r * 0.22f, my - mh / 2, cx + r * 0.22f, my + mh / 2, mh, mh, p)

        if (speaking) {
            phase += 0.35f
            postInvalidateOnAnimation()
        }
    }
}

/** دائرة الـ score من 10 */
class ScoreView(ctx: Context, private val score: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val sw = s * 0.1f
        box.set(sw / 2, sw / 2, s - sw / 2, s - sw / 2)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFF1A2A28.toInt()
        c.drawArc(box, 0f, 360f, false, p)
        p.color = C.AMBER
        c.drawArc(box, -90f, score * 36f, false, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.textSize = s * 0.38f
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(score.toString(), s / 2, s / 2 - (p.descent() + p.ascent()) / 2, p)
    }
}
