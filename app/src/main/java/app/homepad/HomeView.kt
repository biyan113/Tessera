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
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
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
    /** Horizontal scroll of the dock's contents; only non-zero when they are wider than the screen. */
    private var dockOff = 0f
    private var dockMaxOff = 0f
    private var dockOff0 = 0f
    private var dockFling: ValueAnimator? = null
    private val dockClip = android.graphics.Path()
    var recents: List<AppInfo> = emptyList()

    private var flows = ArrayList<IntArray>()
    var scroll = 0f
    val libIndex get() = layoutModel.pages.size
    val page get() = if (width == 0) 1 else (scroll / width).roundToInt()
    private var settle: ValueAnimator? = null
    private var settleTarget = 1

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

    private enum class Mode { IDLE, PRESS, SCROLL, DOCK, TODAY, PULL, MENU, DRAG, IGNORE }
    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var press: Hit? = null
    private var pressDim = false
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
        pressDim = false
        invalidate()
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

    /** Fading the home screen must not allocate a screen-sized layer; icons barely overlap anyway. */
    override fun hasOverlappingRendering() = false

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
        // A page turn in flight still aims at the old width, so land on its target instead.
        val p = if (settle?.isRunning == true) settleTarget else if (ow > 0) (scroll / ow).roundToInt() else 1
        settle?.cancel()
        // The library is sized at creation, so a rotation has to rebuild it.
        library?.let { removeView(it); library = null }
        metrics()
        scroll = (p * w).toFloat()
        // Views added during this layout pass would stay unmeasured, so wait for it to finish.
        post { syncWidgets() }
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
        todayMetrics()
        Glass.screenW = width
        Glass.screenH = height
        reflow()
    }

    fun reflow() {
        val m = layoutModel
        // The today column takes widgets only; an icon that ended up there goes to the first page.
        if (m.pages.size > 1) {
            val strays = m.pages[0].filter { it !is Item.Widget }
            if (strays.isNotEmpty()) { m.pages[0].removeAll(strays); m.pages[1].addAll(strays) }
        }
        val out = ArrayList<IntArray>(m.pages.size)
        for ((i, p) in m.pages.withIndex()) out.add(flowOf(i, p))
        flows = out
        measureToday()
        measureDock()
        placeChildren()
        invalidate()
    }

    // ---- today page: one widget column on the left, over the blurred first page ----

    private var todayX = 0f
    private var todayTop = 0f
    private var todayS = 1f
    private val todayGap get() = dp(16f)
    private val editRect = RectF()
    /**
     * Vertical scroll of the today column. In landscape its widgets and edit pill are taller than
     * the space above the dock, so it scrolls like iPadOS: the dock floats over whatever is below.
     */
    private var todayScroll = 0f
    private var todayMax = 0f
    private var todayScroll0 = 0f
    private var todayFling: ValueAnimator? = null

    /** Compact header (smaller time, edit pill beside it) when the full one would squeeze the widgets. */
    private var todayCompact = false
    private val headerH get() = dp(if (todayCompact) 84f else 112f)

    private fun todayMetrics() {
        todayX = dp(28f)
    }

    /**
     * Sizes the today column to the screen: widgets keep their full 158dp when everything fits
     * above the dock, and shrink (no smaller than 110dp) when it does not, first by tightening the
     * header and moving the edit pill up beside the time. Only a column that still overflows at
     * the smallest size scrolls, and then it is clipped below the status bar.
     */
    private fun measureToday() {
        val f = flows.getOrNull(0)
        val list = layoutModel.pages.getOrNull(0)
        // Height of the column in widget units: n squares and n-1 gaps.
        var units = 0
        if (f != null && list != null) for ((i, it) in list.withIndex()) {
            if (it !is Item.Widget || i * 2 + 1 >= f.size || f[i * 2] < 0) continue
            units = max(units, f[i * 2 + 1] + if (it.sy > 2) 2 else 1)
        }
        val free = height - insetBottom - dockH - dp(4f) - dp(12f)
        val widthCap = min(dp(158f), (width * 0.52f - dp(44f)) / 2)
        fun fit(header: Float, pill: Float) =
            if (units == 0) widthCap else (free - gridT - header - pill - (units - 1) * todayGap) / units
        todayCompact = fit(dp(112f), dp(72f)) < widthCap
        todayS = (if (todayCompact) fit(dp(84f), 0f) else widthCap).coerceIn(min(dp(110f), widthCap), widthCap)
        todayTop = gridT + headerH
        val bottom = todayTop + units * todayS + max(0, units - 1) * todayGap + if (todayCompact) 0f else dp(72f)
        todayMax = max(0f, bottom - free)
        todayScroll = todayScroll.coerceIn(0f, todayMax)
    }

    private fun scrollToday(to: Float) {
        todayScroll = to
        placeChildren()
        invalidate()
    }

    private fun settleToday(v: Float) {
        todayFling?.cancel()
        val from = todayScroll
        val to = if (from !in 0f..todayMax) from.coerceIn(0f, todayMax) else (from - v * 0.3f).coerceIn(0f, todayMax)
        if (abs(to - from) < 1f) return
        todayFling = ValueAnimator.ofFloat(from, to).apply {
            duration = if (from !in 0f..todayMax) 300 else 420
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { scrollToday(it.animatedValue as Float) }
            start()
        }
    }

    /** How far the today page is pulled in: 0 on the first home page, 1 when fully open. */
    private val today get() = if (width == 0) 0f else (1 - scroll / width).coerceIn(0f, 1f)

    /**
     * Horizontal offset of a page. The today column slides in over the first page, which stays
     * put underneath; every other page turns as usual.
     */
    private fun ox(p: Int): Float = when {
        p == 0 -> -(1 - today) * (todayX + todayS * 2 + todayGap + dp(12f))
        scroll < width -> (p - 1) * width.toFloat()
        else -> p * width - scroll
    }

    private fun flowOf(i: Int, p: List<Item>) = if (i == 0) flow(p, 2, 8, true) else flow(p, cols, rows, false)

    /**
     * Items fill the grid from the top left with no gaps; widgets take the first space they fit.
     * On the today page the unit is a small square widget: anything wider than two icon cells
     * spans both columns, anything taller than two spans two rows.
     */
    private fun flow(p: List<Item>, cols: Int, rows: Int, today: Boolean): IntArray {
        val used = BooleanArray(cols * rows)
        val out = IntArray(p.size * 2) { -1 }
        for ((n, it) in p.withIndex()) {
            if (today && it !is Item.Widget) continue
            val sx = if (it !is Item.Widget) 1 else if (today) (if (it.sx > 2) 2 else 1) else min(it.sx, cols)
            val sy = if (it !is Item.Widget) 1 else if (today) (if (it.sy > 2) 2 else 1) else min(it.sy, rows)
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
        val content = dp(16f) * 2 + (n + r + 1) * dockSlot - dp(16f) + dp(21f)
        // In portrait a full dock plus recents is wider than the screen: cap it and let it scroll.
        val w = min(content, width - dp(16f) * 2)
        dockMaxOff = max(0f, content - w)
        dockOff = dockOff.coerceIn(0f, dockMaxOff)
        val top = height - insetBottom - dockH - dp(4f)
        dockRect.set((width - w) / 2, top, (width + w) / 2, top + dockH)
        dockClip.reset()
        dockClip.addRoundRect(dockRect, dp(30f), dp(30f), android.graphics.Path.Direction.CW)
        dockIconY = top + (dockH - icon) / 2
        placeDock()
    }

    private fun placeDock() { dockFirstX = dockRect.left + dp(16f) - dockOff }

    private fun scrollDock(to: Float) {
        dockOff = to
        placeDock()
        invalidate()
    }

    /** A flick keeps the dock moving and eases to a stop, clamped to its ends. */
    private fun flingDock(v: Float) {
        dockFling?.cancel()
        val from = dockOff
        val to = (from - v * 0.3f).coerceIn(0f, dockMaxOff)
        if (abs(to - from) < 1f) return
        dockFling = ValueAnimator.ofFloat(from, to).apply {
            duration = 420
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { scrollDock(it.animatedValue as Float) }
            start()
        }
    }

    private fun widgetRect(p: Int, i: Int, it: Item.Widget, out: RectF): Boolean {
        val f = flows.getOrNull(p) ?: return false
        if (i * 2 + 1 >= f.size || f[i * 2] < 0) return false
        if (p == 0) {
            val step = todayS + todayGap
            val x = todayX + f[i * 2] * step
            val y = todayTop - todayScroll + f[i * 2 + 1] * step
            out.set(x, y, x + (if (it.sx > 2) todayS * 2 + todayGap else todayS), y + (if (it.sy > 2) todayS * 2 + todayGap else todayS))
            return true
        }
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
            v.translationX = ox(p) + tmp.left
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
                    override fun getOutline(view: View, o: Outline) = o.setPath(Smooth.path(android.graphics.Path(), 0f, 0f, view.width.toFloat(), view.height.toFloat(), dp(24f)))
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
        val t = today
        if (t > 0f) {
            // The first page stays where it is and goes out of focus behind the widget column.
            layoutModel.pages.getOrNull(1)?.let { list ->
                behind.setPosition(0, 0, w, height)
                drawPage(behind.beginRecording(), 1, list, 0f)
                behind.endRecording()
                val r = t * dp(22f)
                behind.setRenderEffect(if (r < 1f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                behind.alpha = 1 - 0.3f * t
                c.drawRenderNode(behind)
            }
            tmp.set(0f, 0f, w.toFloat(), height.toFloat())
            Glass.draw(c, tmp, 0f, 0x30000000, (t * 170).toInt(), border = false)
            layoutModel.pages.getOrNull(0)?.let { drawPage(c, 0, it, ox(0)) }
            return
        }
        val first = (scroll / w).toInt()
        for (p in first..first + 1) {
            val list = layoutModel.pages.getOrNull(p) ?: continue
            val ox = ox(p)
            if (ox <= -w || ox >= w) continue
            drawPage(c, p, list, ox)
        }
    }

    private val behind = RenderNode("first-page")

    private fun drawTodayHeader(c: Canvas, ox: Float) {
        val cal = Calendar.getInstance()
        val x = todayX + ox + dp(4f)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        text.setShadowLayer(dp(6f), 0f, dp(1f), 0x40000000)
        val k = if (todayCompact) 0.78f else 1f
        text.textSize = dp(54f) * k
        c.drawText("%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), x, gridT + dp(54f) * k - todayScroll, text)
        text.textSize = dp(19f) * (if (todayCompact) 0.9f else 1f)
        val week = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        c.drawText("${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $week", x + dp(2f), gridT + dp(86f) * k - todayScroll, text)
        text.clearShadowLayer()
        text.typeface = Typeface.DEFAULT

        // The pill under the last widget is the only way into editing when the column has no free spot to long-press.
        var bottom = todayTop - todayScroll
        val list = layoutModel.pages[0]
        for ((i, it) in list.withIndex()) if (it is Item.Widget && widgetRect(0, i, it, tmp)) bottom = max(bottom, tmp.bottom)
        if (todayCompact) {
            // Beside the time, flush with the column's right edge.
            val right = todayX + todayS * 2 + todayGap
            val top = gridT + dp(14f) - todayScroll
            editRect.set(right - dp(76f), top, right, top + dp(34f))
        } else {
            val cx = todayX + todayS + todayGap / 2
            editRect.set(cx - dp(38f), bottom + dp(18f), cx + dp(38f), bottom + dp(52f))
        }
        if (!jiggle) {
            tmp.set(editRect)
            tmp.offset(ox, 0f)
            Glass.draw(c, tmp, dp(17f), 0x40FFFFFF, 255, ox, 0f)
            text.textSize = dp(15f)
            text.textAlign = Paint.Align.CENTER
            c.drawText("编辑", tmp.centerX(), tmp.centerY() + dp(5.2f), text)
        }
    }

    private fun drawPage(c: Canvas, p: Int, list: List<Item>, ox: Float) {
        val f = flows.getOrNull(p) ?: return
        val clip = c.save()
        // A column that scrolls slides under the status bar's edge, never beneath its icons.
        if (p == 0 && todayMax > 0f) c.clipRect(0f, insetTop.toFloat(), width.toFloat(), height.toFloat())
        try { drawPageBody(c, p, list, ox, f) } finally { c.restoreToCount(clip) }
    }

    override fun drawChild(c: Canvas, child: View, time: Long): Boolean {
        if (todayMax <= 0f || child !in todayViews()) return super.drawChild(c, child, time)
        val s = c.save()
        c.clipRect(0f, insetTop.toFloat(), width.toFloat(), height.toFloat())
        val r = super.drawChild(c, child, time)
        c.restoreToCount(s)
        return r
    }

    private fun todayViews(): Collection<View> = layoutModel.pages.getOrNull(0)?.mapNotNull { (it as? Item.Widget)?.let { w -> widgetViews[w] } } ?: emptyList()

    private fun drawPageBody(c: Canvas, p: Int, list: List<Item>, ox: Float, f: IntArray) {
        if (p == 0) drawTodayHeader(c, ox)
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
                badge(c, BadgeService.of(a), x + icon, y)
            }
            is Item.Folder -> {
                drawFolder(c, it, x, y)
                if (withLabel) drawLabel(c, it.name, x + icon / 2, y + icon + dp(17f))
                badge(c, it.apps.sumOf { k -> Apps.map[k]?.let(BadgeService::of) ?: 0 }, x + icon, y)
            }
            else -> {}
        }
        if (jiggle && it !== drag) minus(c, x, y)
        c.restoreToCount(save)
    }

    /** The pressed icon, redrawn sharp above the blurred home screen while its menu is open. */
    fun drawLifted(c: Canvas, it: Item, r: RectF) {
        val s = c.save()
        c.scale(1.08f, 1.08f, r.centerX(), r.centerY())
        drawItem(c, it, r.left, r.top, 0, false)
        c.restoreToCount(s)
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

    /** Grids on screen; a parent invalidate does not repaint them. */
    val grids = HashSet<IconGrid>()

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

    fun badge(c: Canvas, n: Int, rx: Float, ty: Float) {
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
        Glass.draw(c, r, dp(24f), 0x30000000)
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
        when {
            a >= 255 -> { drawDock(c); drawDots(c) }
            a > 0 -> {
                // Only while sliding to the library, and only as tall as the dock: a full-screen
                // layer here costs a screen-sized GPU buffer.
                c.saveLayerAlpha(0f, dockRect.top - dp(24f), w, height.toFloat(), a)
                drawDock(c)
                drawDots(c)
                c.restore()
            }
        }
        if (jiggle) {
            for ((p, list) in layoutModel.pages.withIndex()) {
                val ox = ox(p)
                if (ox <= -w || ox >= w || (p > 0 && today >= 1f)) continue
                for ((i, it) in list.withIndex()) if (it is Item.Widget && widgetRect(p, i, it, tmp)) minus(c, tmp.left + ox, tmp.top)
            }
            drawDone(c)
        }
        drag?.let { drawDrag(c, it) }
        val h = press
        if (pressDim && h != null) {
            val s = c.save()
            c.translate(h.rect.left, h.rect.top)
            fill.color = 0x52000000
            c.drawPath(tile, fill)
            c.restoreToCount(s)
        }
        if (jiggle || moving) postInvalidateOnAnimation()
    }

    private fun drawDock(c: Canvas) {
        Glass.draw(c, dockRect, dp(30f), 0x40FFFFFF)
        val clip = c.save()
        if (dockMaxOff > 0f) c.clipPath(dockClip)
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
            badge(c, BadgeService.of(a), x + icon, dockIconY)
            x += dockSlot
        }
        drawLibraryGlyph(c, x, dockIconY)
        c.restoreToCount(clip)
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
                dockFling?.cancel()
                todayFling?.cancel()
                dockOff0 = dockOff
                todayScroll0 = todayScroll
                vt?.recycle()
                vt = VelocityTracker.obtain()
                downX = x
                downY = y
                mode = Mode.PRESS
                press = hit(x, y)
                pressDim = !jiggle && press != null && press?.item !is Item.Widget
                if (pressDim) invalidate()
                removeCallbacks(longPress)
                if (page < libIndex && !dockTapOnly(press)) postDelayed(longPress, 380)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                when (mode) {
                    Mode.PRESS -> if (abs(dx) > slop || abs(dy) > slop) {
                        removeCallbacks(longPress)
                        if (pressDim) { pressDim = false; invalidate() }
                        val h = press
                        when {
                            jiggle && h?.item != null && h.kind == 0 -> startDrag(h, x, y)
                            abs(dx) > abs(dy) && dockMaxOff > 0f && page < libIndex && dockRect.contains(downX, downY) -> mode = Mode.DOCK
                            abs(dx) > abs(dy) -> mode = Mode.SCROLL
                            // On the today page a vertical drag scrolls the column; pulling down
                            // at its top still opens search.
                            page == 0 && todayMax > 0f && (dy < 0 || todayScroll > 0f) &&
                                downX < todayX + todayS * 2 + todayGap + dp(16f) -> mode = Mode.TODAY
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
                    Mode.DOCK -> {
                        // Rubber-band past either end, like the pages.
                        var t = dockOff0 - dx
                        if (t < 0) t /= 3
                        if (t > dockMaxOff) t = dockMaxOff + (t - dockMaxOff) / 3
                        scrollDock(t)
                    }
                    Mode.TODAY -> {
                        var t = todayScroll0 - dy
                        if (t < 0) t /= 3
                        if (t > todayMax) t = todayMax + (t - todayMax) / 3
                        scrollToday(t)
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
                if (pressDim) { pressDim = false; invalidate() }
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
                    Mode.DOCK -> {
                        vt?.addMovement(e)
                        vt?.computeCurrentVelocity(1000)
                        val v = vt?.xVelocity ?: 0f
                        if (dockOff !in 0f..dockMaxOff) {
                            dockFling?.cancel()
                            dockFling = ValueAnimator.ofFloat(dockOff, dockOff.coerceIn(0f, dockMaxOff)).apply {
                                duration = 300
                                interpolator = DecelerateInterpolator()
                                addUpdateListener { scrollDock(it.animatedValue as Float) }
                                start()
                            }
                        } else flingDock(v)
                    }
                    Mode.TODAY -> {
                        vt?.addMovement(e)
                        vt?.computeCurrentVelocity(1000)
                        settleToday(vt?.yVelocity ?: 0f)
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
        if (page == 0 && editRect.contains(x, y)) { startJiggle(); return }
        // Tapping the home screen showing beside the column puts the column away.
        if (page == 0 && press == null && x > todayX + todayS * 2 + todayGap + dp(16f)) { snapTo(1); return }
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
        settleTarget = p
        val from = scroll
        val to = (p * width).toFloat()
        settle = ValueAnimator.ofFloat(0f, 1f).apply {
            val sp = Spring(1f, if (fast) 0.26f else 0.36f)
            duration = sp.millis
            interpolator = sp
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
        Sfx.tick()
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
        // The today column holds widgets only, in its own two-column grid.
        if (p == 0 && it !is Item.Widget) return
        val left = if (p == 0) todayX else gridL
        val stepX = if (p == 0) todayS + todayGap else cellW
        val cx = ((x - left) / stepX).toInt().coerceIn(0, (if (p == 0) 2 else cols) - 1)
        val cy = if (p == 0) ((y - todayTop + todayScroll) / stepX).toInt().coerceIn(0, 7) else ((y - gridT) / cellH).toInt().coerceIn(0, rows - 1)
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
        val centered = under != null && abs(x - (left + (cx + .5f) * cellW)) < cellW * 0.27f
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
            val f = flowOf(i, l)
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
