package app.homepad

import android.animation.ValueAnimator
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import java.util.Calendar
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The whole home screen is one view: pages, dock, jiggle mode and drag and drop are drawn on a
 * single canvas instead of one view per icon. Only real app widgets are child views, and only
 * for the pages next to the visible one.
 */
class HomeView(private val act: HomeActivity) : ViewGroup(act) {

    class Hit(val item: Item?, val inDock: Boolean, val rect: RectF, val kind: Int)

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    lateinit var layoutModel: Layout
    var cols = 6
    var rows = 5
    var insetTop = 0
    var insetBottom = 0
    private val icon = dp(64f)
    private var gridL = 0f
    private var gridT = 0f
    private var cellW = 1f
    private var cellH = 1f
    private val dockH = dp(92f)
    private val dockRect = RectF()
    private var dockSlot = 0f
    private var dockFirstX = 0f
    private var dockIconY = 0f
    var recents: List<AppInfo> = emptyList()

    private var flows = ArrayList<IntArray>()
    var scroll = 0f
    val libIndex get() = layoutModel.pages.size
    val page get() = if (width == 0) 1 else (scroll / width).roundToInt()
    private var settle: ValueAnimator? = null

    var jiggle = false
        private set
    private var jiggleStart = 0L
    private val cur = IdentityHashMap<Item, FloatArray>()
    private var moving = false

    private var drag: Item? = null
    private var dragX = 0f
    private var dragY = 0f
    private var dragInDock = false
    private var mergeTarget: Item? = null
    private var mergeSince = 0L
    private var edgeSince = 0L

    private enum class Mode { IDLE, PRESS, SCROLL, PULL, MENU, DRAG, IGNORE }
    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var press: Hit? = null
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(act).scaledTouchSlop.toFloat()

    private val widgetViews = IdentityHashMap<Item.Widget, AppWidgetHostView>()
    var library: LibraryView? = null

    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(12f); textAlign = Paint.Align.CENTER
        setShadowLayer(dp(2f), 0f, dp(0.6f), 0x66000000)
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()
    private val tile = squircle(icon)

    private val longPress = Runnable {
        val h = press
        if (mode != Mode.PRESS) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (h?.item != null && h.kind == 0) {
            mode = Mode.MENU
            if (!jiggle) act.showMenu(h.item, h.rect, h.inDock)
        } else if (h == null) {
            startJiggle()
            mode = Mode.IGNORE
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    // ---- layout ----

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(
                MeasureSpec.makeMeasureSpec(c.layoutParams.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(c.layoutParams.height, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(ch: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        }
        placeChildren()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val p = if (ow > 0) (scroll / ow).roundToInt() else 1
        metrics()
        scroll = (p * w).toFloat()
        syncWidgets()
    }

    fun metrics() {
        if (width == 0) return
        val land = width > height
        cols = if (land) 6 else 5
        rows = if (land) 5 else 6
        val side = dp(if (land) 60f else 30f)
        gridL = side
        cellW = (width - side * 2) / cols
        gridT = insetTop + dp(18f)
        val dockTop = height - insetBottom - dockH - dp(4f)
        cellH = (dockTop - dp(28f) - gridT) / rows
        Glass.screenW = width
        Glass.screenH = height
        reflow()
    }

    fun reflow() {
        val m = layoutModel
        val out = ArrayList<IntArray>(m.pages.size)
        for (p in m.pages) out.add(flow(p))
        flows = out
        measureDock()
        placeChildren()
        invalidate()
    }

    /** Items fill the grid from the top left with no gaps; widgets take the first space they fit. */
    private fun flow(p: List<Item>): IntArray {
        val used = BooleanArray(cols * rows)
        val out = IntArray(p.size * 2) { -1 }
        for ((n, it) in p.withIndex()) {
            val sx = if (it is Item.Widget) min(it.sx, cols) else 1
            val sy = if (it is Item.Widget) min(it.sy, rows) else 1
            search@ for (y in 0..rows - sy) for (x in 0..cols - sx) {
                var free = true
                for (a in 0 until sx) for (b in 0 until sy) if (used[(y + b) * cols + x + a]) free = false
                if (!free) continue
                for (a in 0 until sx) for (b in 0 until sy) used[(y + b) * cols + x + a] = true
                out[n * 2] = x
                out[n * 2 + 1] = y
                break@search
            }
        }
        return out
    }

    private fun measureDock() {
        val n = layoutModel.dock.size
        val r = recents.size
        dockSlot = icon + dp(16f)
        val w = dp(16f) * 2 + (n + r + 1) * dockSlot - dp(16f) + dp(21f)
        val top = height - insetBottom - dockH - dp(4f)
        dockRect.set((width - w) / 2, top, (width + w) / 2, top + dockH)
        dockFirstX = dockRect.left + dp(16f)
        dockIconY = top + (dockH - icon) / 2
    }

    private fun widgetRect(p: Int, i: Int, it: Item.Widget, out: RectF): Boolean {
        val f = flows.getOrNull(p) ?: return false
        if (i * 2 + 1 >= f.size || f[i * 2] < 0) return false
        val pad = dp(9f)
        val x = gridL + f[i * 2] * cellW
        val y = gridT + f[i * 2 + 1] * cellH
        out.set(x + pad, y + pad / 2, x + min(it.sx, cols) * cellW - pad, y + min(it.sy, rows) * cellH - pad)
        return true
    }

    // ---- widgets as child views ----

    private fun placeChildren() {
        val m = layoutModel
        for ((p, list) in m.pages.withIndex()) for ((i, it) in list.withIndex()) {
            if (it !is Item.Widget) continue
            val v = widgetViews[it] ?: continue
            if (!widgetRect(p, i, it, tmp)) continue
            val w = tmp.width().toInt()
            val h = tmp.height().toInt()
            if (v.layoutParams.width != w || v.layoutParams.height != h) {
                v.layoutParams = LayoutParams(w, h)
                v.updateAppWidgetSize(null, (w / d).toInt(), (h / d).toInt(), (w / d).toInt(), (h / d).toInt())
            }
            v.translationX = p * width - scroll + tmp.left
            v.translationY = tmp.top
        }
        library?.translationX = libIndex * width - scroll
    }

    /** Keeps widget views only for pages next to the visible one, so far pages cost nothing. */
    fun syncWidgets() {
        if (width == 0) return
        val m = layoutModel
        val keep = (scroll / width).toInt().let { (it - 1)..(it + 2) }
        val live = java.util.Collections.newSetFromMap(IdentityHashMap<Item.Widget, Boolean>())
        for ((p, list) in m.pages.withIndex()) for (it in list) {
            if (it !is Item.Widget || it.kind != K_HOST || p !in keep) continue
            live.add(it)
            if (widgetViews[it] == null) act.widgetView(it)?.let { v ->
                v.layoutParams = LayoutParams(1, 1)
                v.clipToOutline = true
                v.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, dp(22f))
                }
                widgetViews[it] = v
                addView(v)
            }
        }
        val iter = widgetViews.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in live) { removeView(e.value); iter.remove() }
        }
        val nearLib = scroll > (libIndex - 1) * width + 1
        if (nearLib && library == null) {
            library = LibraryView(act).also {
                it.layoutParams = LayoutParams(width, height)
                addView(it)
            }
        } else if (!nearLib && library != null) {
            removeView(library)
            library = null
        }
        placeChildren()
    }

    // ---- drawing ----

    override fun onDraw(c: Canvas) {
        val w = width
        if (w == 0) return
        moving = false
        val first = (scroll / w).toInt()
        for (p in first..first + 1) {
            val list = layoutModel.pages.getOrNull(p) ?: continue
            val ox = p * w - scroll
            if (ox <= -w || ox >= w) continue
            drawPage(c, p, list, ox)
        }
    }

    private fun drawPage(c: Canvas, p: Int, list: List<Item>, ox: Float) {
        val f = flows.getOrNull(p) ?: return
        for ((i, it) in list.withIndex()) {
            if (i * 2 + 1 >= f.size || f[i * 2] < 0 || it === drag) continue
            if (it is Item.Widget) {
                if (widgetRect(p, i, it, tmp)) {
                    if (it.kind != K_HOST) {
                        tmp.offset(ox, 0f)
                        drawBuiltin(c, it.kind, tmp, ox)
                    }
                }
                continue
            }
            val tx = gridL + f[i * 2] * cellW + (cellW - icon) / 2
            val ty = gridT + f[i * 2 + 1] * cellH + dp(6f)
            val pos = animated(it, tx, ty)
            drawItem(c, it, pos[0] + ox, pos[1], i, true)
        }
    }

    private val direct = FloatArray(2)
    private fun animated(it: Item, tx: Float, ty: Float): FloatArray {
        if (!jiggle) { direct[0] = tx; direct[1] = ty; return direct }
        val a = cur.getOrPut(it) { floatArrayOf(tx, ty) }
        val dx = tx - a[0]
        val dy = ty - a[1]
        if (abs(dx) + abs(dy) > 0.6f) {
            a[0] += dx * 0.26f
            a[1] += dy * 0.26f
            moving = true
        } else { a[0] = tx; a[1] = ty }
        return a
    }

    private fun drawItem(c: Canvas, it: Item, x: Float, y: Float, seed: Int, withLabel: Boolean) {
        val save = c.save()
        if (jiggle && it !== drag) {
            val t = (System.currentTimeMillis() - jiggleStart) / 1000.0
            c.rotate((sin(t * 15 + seed * 1.7) * 1.7).toFloat(), x + icon / 2, y + icon / 2)
        }
        if (it === mergeTarget) { c.scale(1.12f, 1.12f, x + icon / 2, y + icon / 2) }
        when (it) {
            is Item.App -> Apps.map[it.key]?.let { a ->
                drawIcon(c, a, x, y, icon)
                if (withLabel) drawLabel(c, a.let { ai -> shortOf(ai) }, x + icon / 2, y + icon + dp(17f))
                badge(c, BadgeService.counts[a.pkg] ?: 0, x + icon, y)
            }
            is Item.Folder -> {
                drawFolder(c, it, x, y)
                if (withLabel) drawLabel(c, it.name, x + icon / 2, y + icon + dp(17f))
                badge(c, it.apps.sumOf { k -> Apps.map[k]?.let { a -> BadgeService.counts[a.pkg] } ?: 0 }, x + icon, y)
            }
            else -> {}
        }
        if (jiggle && it !== drag) minus(c, x, y)
        c.restoreToCount(save)
    }

    private fun shortOf(a: AppInfo): String {
        val w = cellW.toInt() - dp(10f).toInt()
        if (a.shortLabel == null || a.shortWidth != w) {
            a.shortLabel = TextUtils.ellipsize(a.label, label, w.toFloat(), TextUtils.TruncateAt.END).toString()
            a.shortWidth = w
        }
        return a.shortLabel!!
    }

    private fun drawLabel(c: Canvas, s: String, x: Float, y: Float) = c.drawText(s, x, y, label)

    fun drawIcon(c: Canvas, a: AppInfo, x: Float, y: Float, size: Float) {
        val b = Icons.get(a)
        if (b != null) {
            tmp2.set(x, y, x + size, y + size)
            c.drawBitmap(b, null, tmp2, bmpPaint)
        } else {
            val s = c.save()
            c.translate(x, y)
            c.scale(size / icon, size / icon)
            fill.color = 0x33FFFFFF
            c.drawPath(tile, fill)
            c.restoreToCount(s)
        }
    }

    private val tmp2 = RectF()
    private fun drawFolder(c: Canvas, f: Item.Folder, x: Float, y: Float) {
        tmp2.set(x, y, x + icon, y + icon)
        val r = icon * 0.2237f
        val rect = RectF(tmp2)
        Glass.draw(c, rect, r, 0x46FFFFFF)
        val pad = icon * 0.11f
        val gap = icon * 0.05f
        val s = (icon - pad * 2 - gap * 2) / 3
        for (i in 0 until min(9, f.apps.size)) {
            val a = Apps.map[f.apps[i]] ?: continue
            drawIcon(c, a, x + pad + (i % 3) * (s + gap), y + pad + (i / 3) * (s + gap), s)
        }
    }

    private fun badge(c: Canvas, n: Int, rx: Float, ty: Float) {
        if (n <= 0) return
        val r = dp(11f)
        val s = if (n > 99) "99+" else n.toString()
        text.textSize = dp(13f)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
        val w = max(r * 2, text.measureText(s) + dp(10f))
        fill.color = 0xFFFF3B30.toInt()
        tmp2.set(rx - w + dp(7f), ty - dp(7f), rx + dp(7f), ty - dp(7f) + r * 2)
        c.drawRoundRect(tmp2, r, r, fill)
        c.drawText(s, tmp2.centerX(), tmp2.centerY() + dp(4.6f), text)
    }

    private fun minus(c: Canvas, x: Float, y: Float) {
        val r = dp(11f)
        val cx = x + dp(2f)
        val cy = y + dp(2f)
        fill.color = 0xE85A5A60.toInt()
        c.drawCircle(cx, cy, r, fill)
        fill.color = Color.WHITE
        c.drawRoundRect(cx - dp(5.5f), cy - dp(1f), cx + dp(5.5f), cy + dp(1f), dp(1f), dp(1f), fill)
    }

    private fun drawBuiltin(c: Canvas, kind: String, r: RectF, ox: Float) {
        Glass.draw(c, r, dp(22f), 0x30000000)
        val cal = Calendar.getInstance()
        text.color = Color.WHITE
        text.typeface = Typeface.DEFAULT
        when (kind) {
            K_CLOCK -> {
                val cx = r.centerX()
                val cy = r.centerY()
                val rad = min(r.width(), r.height()) * 0.40f
                fill.color = 0xF2FFFFFF.toInt()
                c.drawCircle(cx, cy, rad, fill)
                fill.color = 0xFF1C1C1E.toInt()
                for (i in 0 until 12) {
                    val a = Math.toRadians(i * 30.0)
                    val o = rad * 0.88f
                    c.drawCircle(cx + (sin(a) * o).toFloat(), cy - (cos(a) * o).toFloat(), dp(if (i % 3 == 0) 2.2f else 1.2f), fill)
                }
                val mi = cal.get(Calendar.MINUTE)
                val h = cal.get(Calendar.HOUR) + mi / 60f
                hand(c, cx, cy, h * 30f, rad * 0.5f, dp(4f), 0xFF1C1C1E.toInt())
                hand(c, cx, cy, mi * 6f, rad * 0.76f, dp(3f), 0xFF1C1C1E.toInt())
                fill.color = 0xFFFF9500.toInt()
                c.drawCircle(cx, cy, dp(3.5f), fill)
            }
            K_CAL -> {
                text.textAlign = Paint.Align.LEFT
                val week = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
                text.color = 0xFFFF6B5E.toInt()
                text.textSize = dp(13f)
                text.isFakeBoldText = true
                c.drawText(week, r.left + dp(18f), r.top + dp(30f), text)
                text.isFakeBoldText = false
                text.color = Color.WHITE
                text.textSize = r.height() * 0.42f
                text.isFakeBoldText = false
                c.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(), r.left + dp(16f), r.top + r.height() * 0.62f, text)
                text.textSize = dp(13f)
                text.color = 0xCCFFFFFF.toInt()
                c.drawText("${cal.get(Calendar.YEAR)} 年 ${cal.get(Calendar.MONTH) + 1} 月", r.left + dp(18f), r.bottom - dp(18f), text)
            }
            K_BATT -> {
                val bi = act.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lv = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
                val st = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
                val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                val cx = r.centerX()
                val cy = r.centerY() - dp(10f)
                val rad = min(r.width(), r.height()) * 0.30f
                ring.strokeWidth = dp(9f)
                ring.color = 0x40FFFFFF
                c.drawCircle(cx, cy, rad, ring)
                ring.color = if (charging) 0xFF34C759.toInt() else if (lv < 20) 0xFFFF3B30.toInt() else Color.WHITE
                tmp2.set(cx - rad, cy - rad, cx + rad, cy + rad)
                c.drawArc(tmp2, -90f, lv * 3.6f, false, ring)
                text.color = Color.WHITE
                text.textAlign = Paint.Align.CENTER
                text.textSize = dp(22f)
                c.drawText("$lv%", cx, cy + dp(7f), text)
                text.textSize = dp(13f)
                text.color = 0xCCFFFFFF.toInt()
                c.drawText(if (charging) "正在充电" else "电池", cx, r.bottom - dp(16f), text)
            }
        }
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, w: Float, col: Int) {
        val s = c.save()
        c.rotate(deg, cx, cy)
        fill.color = col
        c.drawRoundRect(cx - w / 2, cy - len, cx + w / 2, cy + w, w / 2, w / 2, fill)
        c.restoreToCount(s)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val w = width.toFloat()
        val libFrac = ((scroll - (libIndex - 1) * w) / w).coerceIn(0f, 1f)
        val a = ((1 - libFrac) * 255).toInt()
        if (a > 0) {
            c.saveLayerAlpha(0f, 0f, w, height.toFloat(), a)
            drawDock(c)
            drawDots(c)
            c.restore()
        }
        if (jiggle) {
            for ((p, list) in layoutModel.pages.withIndex()) {
                val ox = p * w - scroll
                if (ox <= -w || ox >= w) continue
                for ((i, it) in list.withIndex()) if (it is Item.Widget && widgetRect(p, i, it, tmp)) minus(c, tmp.left + ox, tmp.top)
            }
            drawDone(c)
        }
        drag?.let { drawDrag(c, it) }
        if (jiggle || moving) postInvalidateOnAnimation()
    }

    private fun drawDock(c: Canvas) {
        Glass.draw(c, dockRect, dp(30f), 0x40FFFFFF)
        for ((i, it) in layoutModel.dock.withIndex()) {
            if (it === drag) continue
            val pos = animated(it, dockFirstX + i * dockSlot, dockIconY)
            drawItem(c, it, pos[0], pos[1], i + 40, false)
        }
        var x = dockFirstX + layoutModel.dock.size * dockSlot
        fill.color = 0x55FFFFFF
        c.drawRect(x - dp(1f), dockRect.top + dp(20f), x + dp(0.2f), dockRect.bottom - dp(20f), fill)
        x += dp(21f) - dp(8f) + dp(8f)
        for (a in recents) {
            drawIcon(c, a, x - dp(8f) + dp(8f), dockIconY, icon)
            badge(c, BadgeService.counts[a.pkg] ?: 0, x + icon, dockIconY)
            x += dockSlot
        }
        drawLibraryGlyph(c, x, dockIconY)
    }

    private fun drawLibraryGlyph(c: Canvas, x: Float, y: Float) {
        tmp2.set(x, y, x + icon, y + icon)
        Glass.draw(c, RectF(tmp2), icon * 0.2237f, 0x50FFFFFF)
        val s = icon * 0.26f
        val g = icon * 0.09f
        val o = (icon - s * 2 - g) / 2
        val colors = intArrayOf(0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF375F.toInt())
        for (i in 0 until 4) {
            fill.color = colors[i]
            val a = x + o + (i % 2) * (s + g)
            val b = y + o + (i / 2) * (s + g)
            c.drawRoundRect(a, b, a + s, b + s, s * 0.3f, s * 0.3f, fill)
        }
    }

    private fun drawDots(c: Canvas) {
        val n = layoutModel.pages.size + 1
        val gap = dp(15f)
        val x0 = (width - (n - 1) * gap) / 2
        val y = dockRect.top - dp(14f)
        val curP = scroll / max(1, width)
        for (i in 0 until n) {
            val on = (1 - abs(curP - i)).coerceIn(0f, 1f)
            fill.color = 0x40000000
            c.drawCircle(x0 + i * gap, y, dp(3.8f), fill)
            fill.color = Color.WHITE
            fill.alpha = (255 * on).toInt()
            c.drawCircle(x0 + i * gap, y, dp(3.8f), fill)
        }
    }

    private val doneRect = RectF()
    private val addRect = RectF()
    private fun drawDone(c: Canvas) {
        val y = insetTop + dp(4f)
        doneRect.set(width - dp(100f), y, width - dp(24f), y + dp(34f))
        addRect.set(dp(24f), y, dp(76f), y + dp(34f))
        for (r in arrayOf(doneRect, addRect)) Glass.draw(c, r, dp(17f), 0x50FFFFFF)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        text.textSize = dp(17f)
        c.drawText("完成", doneRect.centerX(), doneRect.centerY() + dp(5.3f), text)
        text.textSize = dp(24f)
        c.drawText("+", addRect.centerX(), addRect.centerY() + dp(8f), text)
    }

    private fun drawDrag(c: Canvas, it: Item) {
        val x = dragX - icon / 2
        val y = dragY - icon / 2
        if (it is Item.Widget) {
            tmp2.set(dragX - dp(70f), dragY - dp(70f), dragX + dp(70f), dragY + dp(70f))
            Glass.draw(c, RectF(tmp2), dp(22f), 0x60FFFFFF)
            return
        }
        val s = c.save()
        c.scale(1.14f, 1.14f, dragX, dragY)
        drawItem(c, it, x, y, 0, false)
        c.restoreToCount(s)
    }

    // ---- hit testing ----

    private fun hit(x: Float, y: Float): Hit? {
        val p = page
        if (p >= libIndex) return null
        if (dockRect.contains(x, y)) {
            for ((i, it) in layoutModel.dock.withIndex()) {
                val ix = dockFirstX + i * dockSlot
                if (x in ix - dp(8f)..ix + icon + dp(8f)) return Hit(it, true, RectF(ix, dockIconY, ix + icon, dockIconY + icon), 0)
            }
            var ix = dockFirstX + layoutModel.dock.size * dockSlot + dp(21f)
            for (a in recents) {
                if (x in ix - dp(8f)..ix + icon + dp(8f)) return Hit(Item.App(a.key), true, RectF(ix, dockIconY, ix + icon, dockIconY + icon), 1)
                ix += dockSlot
            }
            return Hit(null, true, RectF(ix, dockIconY, ix + icon, dockIconY + icon), 2)
        }
        val list = layoutModel.pages.getOrNull(p) ?: return null
        val f = flows.getOrNull(p) ?: return null
        for ((i, it) in list.withIndex()) {
            if (i * 2 + 1 >= f.size || f[i * 2] < 0) continue
            if (it is Item.Widget) {
                if (widgetRect(p, i, it, tmp) && tmp.contains(x, y)) {
                    if (jiggle && abs(x - tmp.left) < dp(22f) && abs(y - tmp.top) < dp(22f)) return Hit(it, false, RectF(tmp), 3)
                    return Hit(it, false, RectF(tmp), 0)
                }
                continue
            }
            val ix = gridL + f[i * 2] * cellW + (cellW - icon) / 2
            val iy = gridT + f[i * 2 + 1] * cellH + dp(6f)
            if (jiggle && abs(x - ix) < dp(20f) && abs(y - iy) < dp(20f)) return Hit(it, false, RectF(ix, iy, ix + icon, iy + icon), 3)
            if (x in ix - dp(12f)..ix + icon + dp(12f) && y in iy - dp(6f)..iy + icon + dp(24f)) {
                return Hit(it, false, RectF(ix, iy, ix + icon, iy + icon), 0)
            }
        }
        return null
    }

    // ---- touch ----

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        track(e)
        return jiggle || (mode != Mode.PRESS && mode != Mode.IDLE)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        track(e)
        return true
    }

    private fun track(e: MotionEvent) {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.cancel()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                downX = x
                downY = y
                mode = Mode.PRESS
                press = hit(x, y)
                removeCallbacks(longPress)
                if (page < libIndex && !dockTapOnly(press)) postDelayed(longPress, 380)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                when (mode) {
                    Mode.PRESS -> if (abs(dx) > slop || abs(dy) > slop) {
                        removeCallbacks(longPress)
                        val h = press
                        when {
                            jiggle && h?.item != null && h.kind == 0 -> startDrag(h, x, y)
                            abs(dx) > abs(dy) -> mode = Mode.SCROLL
                            dy > 0 && !jiggle && page < libIndex -> mode = Mode.PULL
                            else -> mode = Mode.IGNORE
                        }
                    }
                    Mode.SCROLL -> {
                        val max = libIndex * width.toFloat()
                        var t = page0 - dx
                        if (t < 0) t /= 3
                        if (t > max) t = max + (t - max) / 3
                        scroll = t
                        syncWidgets()
                        invalidate()
                    }
                    Mode.PULL -> if (dy > dp(70f)) { mode = Mode.IGNORE; act.openSpotlight() }
                    Mode.MENU -> if (abs(dx) + abs(dy) > slop * 2) {
                        act.closeOverlay()
                        press?.let { startDrag(it, x, y) }
                    }
                    Mode.DRAG -> dragMove(x, y)
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val up = e.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    Mode.PRESS -> if (up) tap(x, y)
                    Mode.SCROLL -> {
                        vt?.addMovement(e)
                        vt?.computeCurrentVelocity(1000)
                        val v = vt?.xVelocity ?: 0f
                        val target = when {
                            v < -dp(350f) -> (page0 / width).roundToInt() + 1
                            v > dp(350f) -> (page0 / width).roundToInt() - 1
                            else -> page
                        }
                        snapTo(target.coerceIn(0, libIndex))
                    }
                    Mode.DRAG -> drop()
                    else -> {}
                }
                mode = Mode.IDLE
                vt?.recycle()
                vt = null
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) page0 = scroll
        vt?.addMovement(e)
    }

    private var page0 = 0f
    private fun dockTapOnly(h: Hit?) = h != null && h.kind != 0

    private fun tap(x: Float, y: Float) {
        if (jiggle) {
            when {
                doneRect.contains(x, y) -> stopJiggle()
                addRect.contains(x, y) -> act.openGallery()
                press?.kind == 3 -> press?.item?.let { act.confirmRemove(it) }
                press == null -> stopJiggle()
            }
            return
        }
        val h = press ?: return
        when (h.kind) {
            2 -> snapTo(libIndex)
            else -> when (val it = h.item) {
                is Item.App -> Apps.map[it.key]?.let { a -> act.launch(a, h.rect) }
                is Item.Folder -> act.openFolder(it, h.rect)
                else -> {}
            }
        }
    }

    fun snapTo(p: Int, fast: Boolean = false) {
        settle?.cancel()
        val from = scroll
        val to = (p * width).toFloat()
        settle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (fast) 200 else 340
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener {
                scroll = from + (to - from) * (it.animatedValue as Float)
                placeChildren()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = syncWidgets()
            })
            start()
        }
        syncWidgets()
    }

    fun startJiggle() {
        if (jiggle) return
        jiggle = true
        jiggleStart = System.currentTimeMillis()
        invalidate()
    }

    fun stopJiggle() {
        if (!jiggle) return
        jiggle = false
        cur.clear()
        if (layoutModel.pages.size > 2) {
            val it = layoutModel.pages.listIterator(1)
            while (it.hasNext()) if (it.next().isEmpty() && layoutModel.pages.size > 2) it.remove()
        }
        reflow()
        act.persist()
    }

    private fun startDrag(h: Hit, x: Float, y: Float) {
        startJiggle()
        drag = h.item
        dragInDock = h.inDock
        dragX = x
        dragY = y
        mode = Mode.DRAG
        performHapticFeedback(HapticFeedbackConstants.DRAG_START)
        invalidate()
    }

    private fun removeFromAll(it: Item) {
        layoutModel.dock.remove(it)
        layoutModel.pages.forEach { p -> p.remove(it) }
    }

    private fun dragMove(x: Float, y: Float) {
        dragX = x
        dragY = y
        val it = drag ?: return
        val now = System.currentTimeMillis()
        val edge = dp(44f)
        if (x < edge || x > width - edge) {
            if (edgeSince == 0L) edgeSince = now
            if (now - edgeSince > 550) {
                edgeSince = now
                val target = page + if (x < edge) -1 else 1
                if (target >= 0) {
                    if (target >= libIndex) layoutModel.pages.add(ArrayList())
                    reflow()
                    snapTo(target, true)
                }
            }
        } else edgeSince = 0
        if (it is Item.App && y > dockRect.top - dp(12f)) {
            val i = ((x - dockFirstX) / dockSlot).toInt().coerceIn(0, layoutModel.dock.size)
            val at = layoutModel.dock.indexOf(it)
            if (at != i && (at >= 0 || layoutModel.dock.size < 8)) {
                removeFromAll(it)
                layoutModel.dock.add(min(i, layoutModel.dock.size), it)
                dragInDock = true
                reflow()
            }
            mergeTarget = null
            invalidate()
            return
        }
        val p = page.coerceIn(0, layoutModel.pages.size - 1)
        val list = layoutModel.pages[p]
        val f = flows.getOrNull(p) ?: return
        val cx = ((x - gridL) / cellW).toInt().coerceIn(0, cols - 1)
        val cy = ((y - gridT) / cellH).toInt().coerceIn(0, rows - 1)
        var over = -1
        var idx = list.size
        for (i in list.indices) {
            if (i * 2 + 1 >= f.size || f[i * 2] < 0) continue
            val fx = f[i * 2]
            val fy = f[i * 2 + 1]
            if (fx == cx && fy == cy) over = i
            if (fy > cy || (fy == cy && fx >= cx)) { idx = i; break }
        }
        val under = list.getOrNull(over)
        val centered = under != null && abs(x - (gridL + (cx + .5f) * cellW)) < cellW * 0.27f
        if (it is Item.App && centered && under !== it && (under is Item.App || under is Item.Folder)) {
            if (mergeTarget !== under) { mergeTarget = under; mergeSince = now }
            invalidate()
            return
        }
        mergeTarget = null
        val at = list.indexOf(it)
        if (at != idx) {
            removeFromAll(it)
            val insert = if (at in 0 until idx) idx - 1 else idx
            list.add(insert.coerceIn(0, list.size), it)
            dragInDock = false
            overflow(p)
            reflow()
        }
        invalidate()
    }

    private fun overflow(p: Int) {
        var i = p
        while (i < layoutModel.pages.size) {
            val l = layoutModel.pages[i]
            val f = flow(l)
            var bad = -1
            for (n in l.indices) if (f[n * 2] < 0) { bad = n; break }
            if (bad < 0) break
            if (i + 1 >= layoutModel.pages.size) layoutModel.pages.add(ArrayList())
            val spill = ArrayList(l.subList(bad, l.size))
            l.subList(bad, l.size).clear()
            layoutModel.pages[i + 1].addAll(0, spill)
            i++
        }
    }

    private fun drop() {
        val it = drag
        val t = mergeTarget
        drag = null
        mergeTarget = null
        edgeSince = 0
        if (it is Item.App && t != null) {
            removeFromAll(it)
            if (t is Item.Folder) t.apps.add(it.key)
            else if (t is Item.App) {
                for (p in layoutModel.pages) {
                    val i = p.indexOf(t)
                    if (i >= 0) p[i] = Item.Folder("文件夹", arrayListOf(t.key, it.key))
                }
            }
        }
        reflow()
        act.persist()
        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
    }

    /** Lets system-driven changes (home press, new app) restore a clean, settled state. */
    fun goHome() {
        stopJiggle()
        if (page != 1) snapTo(1)
    }

    fun refreshAll() {
        layoutModel.reconcile(Apps.map)
        reflow()
        syncWidgets()
    }

    fun dropWidgetView(it: Item.Widget) {
        widgetViews.remove(it)?.let { v -> removeView(v) }
    }
}
