package app.tessera.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Process-wide metrics and threads. Sizes are cached in px, so a density change recreates the activity. */
object Ui {
    var d = 1f
    var sd = 1f
    /** Gesture-bar or keyboard height that scrolling content must be able to clear. */
    var bottom = 0
    val main = Handler(Looper.getMainLooper())
    val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    private val pool = Executors.newFixedThreadPool(2)

    fun init(c: Context) {
        val m = c.resources.displayMetrics
        d = m.density
        sd = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, m)
    }

    fun bg(work: () -> Unit) = pool.execute { try { work() } catch (_: Throwable) {} }
    fun ui(f: () -> Unit) { main.post(f) }
}

fun dp(v: Float) = v * Ui.d
fun dp(v: Int) = (v * Ui.d).roundToInt()
fun sp(v: Float) = v * Ui.sd

/** Baseline that centres [p]'s text vertically on [cy]. */
fun base(p: Paint, cy: Float) = cy - (p.ascent() + p.descent()) / 2

fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = ((a ushr s and 255) + ((b ushr s and 255) - (a ushr s and 255)) * t).roundToInt()
    return ch(24) shl 24 or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** iPadOS palette. Views read it while drawing, so switching mode is one flag plus an invalidate. */
object Theme {
    var dark = false
    private fun pick(l: Long, d: Long) = (if (dark) d else l).toInt()
    val bg get() = pick(0xFFF2F2F7, 0xFF000000)
    val sidebar get() = pick(0xFFF2F2F7, 0xFF1C1C1E)
    val card get() = pick(0xFFFFFFFF, 0xFF1C1C1E)
    /** Dark cards on the dark sidebar need one step more lift to stay visible. */
    val cardSide get() = pick(0xFFFFFFFF, 0xFF2C2C2E)
    val text get() = pick(0xFF000000, 0xFFFFFFFF)
    val secondary get() = pick(0xFF8A8A8E, 0xFF98989F)
    val separator get() = pick(0xFFC6C6C8, 0xFF38383A)
    val tint get() = pick(0xFF007AFF, 0xFF0A84FF)
    val red get() = pick(0xFFFF3B30, 0xFFFF453A)
    val green get() = pick(0xFF34C759, 0xFF30D158)
    val pressed get() = pick(0xFFD1D1D6, 0xFF3A3A3C)
    val chevron get() = pick(0xFFC4C4C7, 0xFF5A5A5F)
    val switchOff get() = pick(0xFFE9E9EA, 0xFF39393D)
    val track get() = pick(0xFFE4E4E6, 0xFF39393D)
    val searchFill get() = pick(0x1F767680, 0x3D767680)
    val alert get() = pick(0xFFF4F4F6, 0xFF2C2C2E)
    val field get() = pick(0xFFFFFFFF, 0xFF1C1C1E)
    val dim get() = pick(0x33000000, 0x7A000000)
}

/** For the few stock widgets (TextView, EditText) that hold colours instead of reading [Theme] per draw. */
interface Themed { fun restyle() }

fun restyle(v: View) {
    (v as? Themed)?.restyle()
    v.invalidate()
    if (v is ViewGroup) for (i in 0 until v.childCount) restyle(v.getChildAt(i))
}

/** The handful of line glyphs that are cheaper stroked than shipped as drawables. */
object G {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val r = RectF()

    private fun stroke(color: Int, w: Float) { p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = dp(w) }
    private fun fill(color: Int) { p.style = Paint.Style.FILL; p.color = color }

    /** Disclosure chevron whose tip touches [right]. */
    fun chevron(c: Canvas, right: Float, cy: Float, color: Int) {
        stroke(color, 2.2f)
        path.rewind()
        path.moveTo(right - dp(5.5f), cy - dp(5.5f)); path.lineTo(right, cy); path.lineTo(right - dp(5.5f), cy + dp(5.5f))
        c.drawPath(path, p)
    }

    /** Back chevron whose tip touches [left]. */
    fun back(c: Canvas, left: Float, cy: Float, color: Int) {
        stroke(color, 2.8f)
        path.rewind()
        path.moveTo(left + dp(8f), cy - dp(8.5f)); path.lineTo(left, cy); path.lineTo(left + dp(8f), cy + dp(8.5f))
        c.drawPath(path, p)
    }

    fun check(c: Canvas, cx: Float, cy: Float, color: Int, s: Float = 1f) {
        stroke(color, 2.3f * s)
        path.rewind()
        path.moveTo(cx - dp(6.5f) * s, cy + dp(0.5f) * s)
        path.lineTo(cx - dp(2f) * s, cy + dp(5f) * s)
        path.lineTo(cx + dp(6.5f) * s, cy - dp(5f) * s)
        c.drawPath(path, p)
    }

    fun lock(c: Canvas, cx: Float, cy: Float, color: Int) {
        fill(color)
        c.drawRoundRect(cx - dp(5f), cy - dp(1.5f), cx + dp(5f), cy + dp(6.5f), dp(1.8f), dp(1.8f), p)
        stroke(color, 1.7f)
        r.set(cx - dp(3f), cy - dp(6.5f), cx + dp(3f), cy + dp(2f))
        c.drawArc(r, 180f, 180f, false, p)
    }

    /** Wi-Fi fan with [level] of 1..3 lit; the rest stay as a faint ghost like iOS. */
    fun wifi(c: Canvas, cx: Float, cy: Float, level: Int, color: Int) {
        val by = cy + dp(6f)
        val faint = color and 0xFFFFFF or 0x40000000
        fill(if (level >= 1) color else faint)
        r.set(cx - dp(4.2f), by - dp(4.2f), cx + dp(4.2f), by + dp(4.2f))
        c.drawArc(r, 225f, 90f, true, p)
        for (i in 1..2) {
            stroke(if (level > i) color else faint, 2.5f)
            p.strokeCap = Paint.Cap.BUTT
            val rad = dp(3.4f + 4.6f * i)
            r.set(cx - rad, by - rad, cx + rad, by + rad)
            c.drawArc(r, 225f, 90f, false, p)
            p.strokeCap = Paint.Cap.ROUND
        }
    }

    fun magnifier(c: Canvas, cx: Float, cy: Float, color: Int) {
        stroke(color, 1.8f)
        c.drawCircle(cx - dp(1.5f), cy - dp(1.5f), dp(5f), p)
        c.drawLine(cx + dp(2.3f), cy + dp(2.3f), cx + dp(6f), cy + dp(6f), p)
    }

    /** Small filled circle with a cross: the search field's clear button. */
    fun clear(c: Canvas, cx: Float, cy: Float, color: Int, cross: Int) {
        fill(color)
        c.drawCircle(cx, cy, dp(8f), p)
        stroke(cross, 1.6f)
        c.drawLine(cx - dp(3f), cy - dp(3f), cx + dp(3f), cy + dp(3f), p)
        c.drawLine(cx + dp(3f), cy - dp(3f), cx - dp(3f), cy + dp(3f), p)
    }

    fun sun(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        fill(color)
        c.drawCircle(cx, cy, radius * 0.48f, p)
        stroke(color, 1.6f)
        for (i in 0 until 8) {
            c.save(); c.rotate(i * 45f, cx, cy)
            c.drawLine(cx, cy - radius * 0.74f, cx, cy - radius, p)
            c.restore()
        }
    }
}
