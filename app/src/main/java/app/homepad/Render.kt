package app.homepad

import android.app.Notification
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import java.io.File
import kotlin.math.max

/**
 * Continuous-corner rounded square: each corner is three cubic segments, so curvature ramps up
 * gradually instead of jumping where a circular arc meets a straight edge. Radius is 22.37% of size.
 */
fun squircle(size: Float, x: Float = 0f, y: Float = 0f): Path {
    val r = size * 0.2237f
    val p = Path()
    // One corner, traced from the top edge round to the right edge, in units of r from that corner.
    val seg = floatArrayOf(
        1.08849323f, 0f, 0.86840689f, 0f, 0.63149399f, 0.07491100f,
        0.37282392f, 0.16905899f, 0.16906013f, 0.37282401f, 0.07491176f, 0.63149399f,
        0f, 0.86840701f, 0f, 1.08849299f, 0f, 1.52866483f,
    )
    // Four rotations of that corner: top right, bottom right, bottom left, top left.
    fun px(q: Int, a: Float, b: Float) = x + when (q) { 0 -> size - a * r; 1 -> size - b * r; 2 -> a * r; else -> b * r }
    fun py(q: Int, a: Float, b: Float) = y + when (q) { 0 -> b * r; 1 -> size - a * r; 2 -> size - b * r; else -> a * r }
    p.moveTo(px(3, 0f, 1.52866483f), py(3, 0f, 1.52866483f))
    for (q in 0..3) {
        val sa = 1.52866471f
        p.lineTo(px(q, sa, 0f), py(q, sa, 0f))
        var i = 0
        while (i < seg.size) {
            p.cubicTo(
                px(q, seg[i], seg[i + 1]), py(q, seg[i], seg[i + 1]),
                px(q, seg[i + 2], seg[i + 3]), py(q, seg[i + 2], seg[i + 3]),
                px(q, seg[i + 4], seg[i + 5]), py(q, seg[i + 4], seg[i + 5]),
            )
            i += 6
        }
    }
    p.close()
    return p
}

/**
 * Bitmaps live in GPU buffers (Config.HARDWARE), so the Java/native heaps hold no pixel copy.
 * The LRU budget is what keeps memory flat however many apps are installed.
 */
object Icons {
    private const val BUDGET = 9 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(BUDGET) {
        override fun sizeOf(k: String, v: Bitmap) = v.allocationByteCount
    }
    private val pending = HashSet<String>()
    var size = 176
    var onLoaded: (() -> Unit)? = null
    private var shape: Path? = null
    private fun outline(s: Int) = shape ?: squircle(s.toFloat()).also { shape = it }

    fun get(a: AppInfo): Bitmap? {
        val b = cache.get(a.key)
        if (b != null) return b
        if (pending.add(a.key)) Bg.run {
            val out = render(a)
            Bg.handler.removeCallbacks(collect)
            Bg.handler.postDelayed(collect, 700)
            Bg.ui {
                pending.remove(a.key)
                if (out != null) cache.put(a.key, out)
                onLoaded?.invoke()
            }
        }
        return null
    }

    /**
     * Decoded source icons sit in native memory, which the small Java heap gives the collector no
     * reason to reclaim. Without this they pile up to 100 MB after one pass over every app.
     */
    private val collect = Runnable { Runtime.getRuntime().gc() }
    fun release() {
        Bg.handler.removeCallbacks(collect)
        Bg.handler.postDelayed(collect, 700)
    }

    fun trim(bytes: Int) = cache.trimToSize(bytes)
    fun restore() = cache.resize(BUDGET)
    fun shrink() = cache.resize(3 * 1024 * 1024)

    private fun render(a: AppInfo): Bitmap? = try {
        val s = size
        val soft = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(soft)
        val d: Drawable = a.info.getIcon(320)
        if (d is AdaptiveIconDrawable) {
            val extra = (s * 0.25f).toInt()
            d.background?.let { it.setBounds(-extra, -extra, s + extra, s + extra); it.draw(c) }
            d.foreground?.let { it.setBounds(-extra, -extra, s + extra, s + extra); it.draw(c) }
        } else fillLegacy(c, d, s)
        // Filling the outline with the artwork needs no blend mode, so nothing can leak past the corners.
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fillPaint.shader = BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawPath(outline(s), fillPaint)
        val hw = out.copy(Bitmap.Config.HARDWARE, false)
        soft.recycle()
        out.recycle()
        hw
    } catch (_: Exception) {
        null
    }

    /** Old-style art: trims its transparent margin, then scales it to fill the whole tile. */
    private fun fillLegacy(c: Canvas, d: Drawable, s: Int) {
        val raw = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(raw))
        val px = IntArray(s * s)
        raw.getPixels(px, 0, s, 0, 0, s, s)
        var l = s; var t = s; var r = -1; var b = -1
        for (y in 0 until s) for (x in 0 until s) if (px[y * s + x] ushr 24 > 48) {
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        c.drawColor(Color.WHITE)
        if (r > l && b > t) {
            val side = max(r - l, b - t) + 1
            val cx = (l + r) / 2
            val cy = (t + b) / 2
            val src = Rect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            c.drawBitmap(raw, src, Rect(0, 0, s, s), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        raw.recycle()
    }
}

/**
 * Frosted material. Compositor blur is unavailable on this device, so panels sample a tiny
 * pre-blurred copy of the wallpaper in screen space. It costs about 100 KB instead of a
 * full-resolution wallpaper bitmap.
 */
object Glass {
    private var bmp: Bitmap? = null
    private var shader: BitmapShader? = null
    private val m = Matrix()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x38FFFFFF }
    var topLuma = 0f
    var bottomLuma = 0f
    var screenW = 1
    var screenH = 1
    /** How much of the frosted wallpaper covers the home screen behind an overlay; the blurred icons show through the rest. */
    const val veil = 200

    fun draw(c: Canvas, r: RectF, radius: Float, tintColor: Int, alpha: Int = 255, ox: Float = 0f, oy: Float = 0f, border: Boolean = true) {
        val b = bmp
        val sh = shader
        if (b != null && sh != null) {
            val s = max(screenW.toFloat() / b.width, screenH.toFloat() / b.height)
            m.setScale(s, s)
            m.postTranslate((screenW - b.width * s) / 2 - ox, (screenH - b.height * s) / 2 - oy)
            sh.setLocalMatrix(m)
            p.shader = sh
            p.alpha = alpha
            Smooth.draw(c, r, radius, p)
        }
        tint.color = if (b == null) (tintColor or 0x50000000) else tintColor
        tint.alpha = Color.alpha(tint.color) * alpha / 255
        Smooth.draw(c, r, radius, tint)
        if (border) {
            edge.strokeWidth = 1.5f
            edge.alpha = 0x38 * alpha / 255
            Smooth.draw(c, r, radius, edge)
        }
    }

    fun load(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        Bg.run {
            val small = decode(app)
            if (small != null) {
                val band = maxOf(1, small.height / 16)
                val top = luma(small, 0, band)
                val bottom = luma(small, small.height - band, small.height)
                val blurred = blur(small, 7)
                val out = blurred.copy(Bitmap.Config.HARDWARE, false)
                small.recycle()
                blurred.recycle()
                Bg.ui {
                    topLuma = top
                    bottomLuma = bottom
                    bmp = out
                    shader = BitmapShader(out, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    done()
                }
            }
        }
    }

    private fun decode(c: Context): Bitmap? {
        val o = BitmapFactory.Options().apply { inSampleSize = 16 }
        try {
            WallpaperManager.getInstance(c).getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use {
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, o)?.let { b -> return b }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** Mean brightness (0 to 1) of a band of rows, read from the small unblurred sample. */
    private fun luma(b: Bitmap, from: Int, to: Int): Float {
        val soft = b.copy(Bitmap.Config.ARGB_8888, false)
        val row = IntArray(soft.width)
        var sum = 0.0
        for (y in from until to) {
            soft.getPixels(row, 0, soft.width, 0, y, soft.width, 1)
            for (v in row) sum += 0.2126 * (v shr 16 and 255) + 0.7152 * (v shr 8 and 255) + 0.0722 * (v and 255)
        }
        soft.recycle()
        return (sum / (soft.width * (to - from)) / 255).toFloat()
    }

    private fun blur(src: Bitmap, r: Int): Bitmap {
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.copy(Bitmap.Config.ARGB_8888, false).getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) {
            pass(a, b, w, h, r, 1, w)
            pass(b, a, h, w, r, w, 1)
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(s: IntArray, d: IntArray, len: Int, lines: Int, r: Int, step: Int, line: Int) {
        val n = 2 * r + 1
        for (l in 0 until lines) {
            val base = l * line
            var cr = 0
            var cg = 0
            var cb = 0
            for (i in -r..r) {
                val v = s[base + i.coerceIn(0, len - 1) * step]
                cr += v shr 16 and 255; cg += v shr 8 and 255; cb += v and 255
            }
            for (i in 0 until len) {
                d[base + i * step] = (255 shl 24) or (cr / n shl 16) or (cg / n shl 8) or (cb / n)
                val add = s[base + (i + r + 1).coerceAtMost(len - 1) * step]
                val sub = s[base + (i - r).coerceAtLeast(0) * step]
                cr += (add shr 16 and 255) - (sub shr 16 and 255)
                cg += (add shr 8 and 255) - (sub shr 8 and 255)
                cb += (add and 255) - (sub and 255)
            }
        }
    }
}

/** Counts live in memory only and content is never stored; new arrivals are handed to [Speaker]. */
class BadgeService : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(n: StatusBarNotification?, map: RankingMap?) {
        refresh()
        if (n != null) try { Speaker.posted(this, n, map) } catch (_: Exception) {}
    }
    override fun onNotificationRemoved(n: StatusBarNotification?) = refresh()
    override fun onNotificationRankingUpdate(map: RankingMap?) = refresh()

    private fun refresh() {
        val out = HashMap<UserHandle, HashMap<String, Int>>()
        try {
            val ranks = currentRanking
            val rank = Ranking()
            val skip = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE
            for (n in activeNotifications) {
                if (n.isOngoing || n.notification.flags and skip != 0) continue
                // Players and other silent channels opt out of badges; honour that like the system does.
                if (ranks.getRanking(n.key, rank) && !rank.canShowBadge()) continue
                val apps = out.getOrPut(n.user) { HashMap() }
                apps[n.packageName] = (apps[n.packageName] ?: 0) + 1
            }
        } catch (_: Exception) { return }
        Bg.ui { counts = out; onChanged?.invoke() }
    }

    companion object {
        @Volatile private var counts: Map<UserHandle, Map<String, Int>> = emptyMap()
        var onChanged: (() -> Unit)? = null

        /** Per profile, so a clone never shows the owner's count. */
        fun of(a: AppInfo) = counts[a.info.user]?.get(a.pkg) ?: 0
    }
}
