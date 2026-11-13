package app.homepad

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.RoundedCorner
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A spring described the way a designer thinks of it: damping 1 settles with no overshoot, lower
 * bounces; response is roughly how long it takes to get there. Used as an interpolator, with
 * [millis] as the animation length, so the motion ends exactly when the spring has come to rest.
 */
class Spring(private val damping: Float = 1f, response: Float = 0.36f) : TimeInterpolator {
    private val w = (2 * PI / response).toFloat()
    val millis = (6.9f / (min(damping, 1f) * w) * 1000).toLong()

    override fun getInterpolation(f: Float): Float {
        if (f >= 1f) return 1f
        val t = f * millis / 1000f
        return if (damping >= 1f) 1 - exp(-w * t) * (1 + w * t)
        else {
            val wd = w * sqrt(1 - damping * damping)
            1 - exp(-damping * w * t) * (cos(wd * t) + damping * w / wd * sin(wd * t))
        }
    }
}

/**
 * Rounded rectangles whose corners ease into the straight edges, the same curve the icons use.
 * A plain arc meets the edge with a visible kink at these sizes.
 */
object Smooth {
    private val seg = floatArrayOf(
        1.08849323f, 0f, 0.86840689f, 0f, 0.63149399f, 0.07491100f,
        0.37282392f, 0.16905899f, 0.16906013f, 0.37282401f, 0.07491176f, 0.63149399f,
        0f, 0.86840701f, 0f, 1.08849299f, 0f, 1.52866483f,
    )
    private const val REACH = 1.52866483f
    private val scratch = Path()

    /** False when the radius is too large for the eased corner to fit; the caller should fall back to an arc. */
    fun fits(w: Float, h: Float, r: Float) = r > 0f && r * REACH * 2 <= min(w, h) + 0.5f

    fun path(p: Path, l: Float, t: Float, rt: Float, b: Float, r: Float): Path {
        p.rewind()
        fun x(q: Int, u: Float, v: Float) = when (q) { 0 -> rt - u * r; 1 -> rt - v * r; 2 -> l + u * r; else -> l + v * r }
        fun y(q: Int, u: Float, v: Float) = when (q) { 0 -> t + v * r; 1 -> b - u * r; 2 -> b - v * r; else -> t + u * r }
        p.moveTo(x(3, 0f, REACH), y(3, 0f, REACH))
        for (q in 0..3) {
            p.lineTo(x(q, REACH, 0f), y(q, REACH, 0f))
            var i = 0
            while (i < seg.size) {
                p.cubicTo(
                    x(q, seg[i], seg[i + 1]), y(q, seg[i], seg[i + 1]),
                    x(q, seg[i + 2], seg[i + 3]), y(q, seg[i + 2], seg[i + 3]),
                    x(q, seg[i + 4], seg[i + 5]), y(q, seg[i + 4], seg[i + 5]),
                )
                i += 6
            }
        }
        p.close()
        return p
    }

    fun draw(c: Canvas, r: RectF, radius: Float, paint: Paint) {
        when {
            radius <= 0f -> c.drawRect(r, paint)
            fits(r.width(), r.height(), radius) -> c.drawPath(path(scratch, r.left, r.top, r.right, r.bottom, radius), paint)
            else -> c.drawRoundRect(r, radius, radius, paint)
        }
    }
}

/**
 * The opening app, as the launcher sees it: the tapped icon grows from its own spot to the whole
 * screen while the real app window fades in on top. Without a system-level animation hook this
 * is as close as a launcher can get to the window emerging from the icon.
 */
@SuppressLint("ViewConstructor")
class LaunchView(private val act: HomeActivity, private val bmp: Bitmap?, private val from: RectF) : View(act) {
    private var p = 0f
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val tone = bmp?.let(::toneOf) ?: -1
    private val corner = act.display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f

    /** One averaged pixel of the icon: the colour the growing sheet settles into behind the fading artwork. */
    private fun toneOf(b: Bitmap): Int = try {
        val soft = b.copy(Bitmap.Config.ARGB_8888, false)
        val one = Bitmap.createScaledBitmap(soft, 1, 1, true)
        val c = one.getPixel(0, 0)
        soft.recycle()
        one.recycle()
        c or (0xFF shl 24)
    } catch (_: Exception) {
        -1
    }

    fun start() {
        val s = Spring(1f, 0.38f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = s.millis
            interpolator = s
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val l = from.left * (1 - p)
        val t = from.top * (1 - p)
        val r = from.right + (width - from.right) * p
        val b = from.bottom + (height - from.bottom) * p
        val rad = from.width() * 0.2237f * (1 - p) + corner * p
        val n = c.save()
        if (Smooth.fits(r - l, b - t, rad)) c.clipPath(Smooth.path(path, l, t, r, b, rad))
        else { path.rewind(); path.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW); c.clipPath(path) }
        c.drawColor(tone)
        if (bmp != null && p < 0.7f) {
            // The artwork shrinks towards a splash-sized mark in the middle as it fades.
            val side = min(r - l, b - t) * (1 - 0.55f * p)
            val cx = (l + r) / 2
            val cy = (t + b) / 2
            dst.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            paint.alpha = (255 * (1 - p / 0.7f)).toInt()
            c.drawBitmap(bmp, null, dst, paint)
        }
        c.restoreToCount(n)
    }
}

/**
 * Two tiny synthesised sounds, made at first use, so no audio file ships. They play as system
 * sonification: silent mode and the system volume apply.
 */
object Sfx {
    private const val RATE = 44100
    private var pop: AudioTrack? = null
    private var tick: AudioTrack? = null

    private fun build(ms: Int, f0: Float, f1: Float, gain: Float): AudioTrack? = try {
        val n = RATE * ms / 1000
        val pcm = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val x = i / n.toFloat()
            val f = f0 + (f1 - f0) * x
            phase += 2 * PI * f / RATE
            // A fast attack and a long soft tail keep it from clicking.
            val env = min(1f, i / (RATE * 0.004f)) * exp(-5.5f * x)
            pcm[i] = (sin(phase) * env * gain * Short.MAX_VALUE).toInt().toShort()
        }
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build()
            .also { it.write(pcm, 0, n) }
    } catch (_: Exception) {
        null
    }

    private fun play(t: AudioTrack?) {
        try {
            t ?: return
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: Exception) {
        }
    }

    /** Something opening out of an icon: a folder, a menu. */
    fun pop() = Bg.run {
        if (pop == null) pop = build(90, 620f, 300f, 0.20f)
        play(pop)
    }

    /** A small state change: entering edit mode, a drop. */
    fun tick() = Bg.run {
        if (tick == null) tick = build(28, 1500f, 1100f, 0.14f)
        play(tick)
    }
}
