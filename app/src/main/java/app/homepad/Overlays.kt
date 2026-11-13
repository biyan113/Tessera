package app.homepad

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.min

private fun Context.px(v: Float) = v * resources.displayMetrics.density

/** Rounded frosted panel used by every overlay. */
open class GlassPanel(c: Context, private val radius: Float, private val tint: Int) : LinearLayout(c) {
    private val r = RectF()
    private val at = IntArray(2)
    init { setWillNotDraw(false); orientation = VERTICAL }
    override fun onDraw(cv: Canvas) {
        getLocationOnScreen(at)
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        Glass.draw(cv, r, radius, tint, 255, at[0].toFloat(), at[1].toFloat())
    }
}

/** An overlay that grew out of something on the home screen and can shrink back into it. */
interface Morph { fun leave() }

/** Full-screen frosted backdrop; tapping it dismisses the overlay. */
open class Backdrop(protected val act: HomeActivity) : FrameLayout(act) {
    private val r = RectF()
    init {
        setWillNotDraw(false)
        isClickable = true
        setOnClickListener { act.closeOverlay() }
    }
    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        Glass.draw(c, r, 0f, 0x38000000, Glass.veil, border = false)
    }
}

/** Canvas icon grid: one view however many apps it shows. */
@SuppressLint("ViewConstructor")
class IconGrid(
    private val home: HomeView,
    private val colsWanted: Int,
    private val onTap: (AppInfo, RectF) -> Unit,
    private val onLong: ((AppInfo) -> Unit)? = null,
) : View(home.context) {
    var apps: List<AppInfo> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    private val icon = context.px(64f)
    private val cellH = context.px(104f)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = context.px(12f); textAlign = Paint.Align.CENTER
    }

    private fun index(e: MotionEvent) = (e.y / cellH).toInt() * colsWanted + (e.x / (width / colsWanted.toFloat())).toInt()

    private fun at(e: MotionEvent): AppInfo? = apps.getOrNull(index(e))

    /** Where icon [i] sits on screen, so a launch can grow out of it. */
    private fun rectOf(i: Int): RectF {
        val cw = width / colsWanted.toFloat()
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val x = loc[0] + (i % colsWanted) * cw + (cw - icon) / 2
        val y = loc[1] + (i / colsWanted) * cellH + context.px(8f)
        return RectF(x, y, x + icon, y + icon)
    }

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { at(e)?.let { onTap(it, rectOf(index(e))) }; return true }
        override fun onLongPress(e: MotionEvent) { at(e)?.let { onLong?.invoke(it) } }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent) = gd.onTouchEvent(e)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); home.grids += this }
    override fun onDetachedFromWindow() { home.grids -= this; super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        val rows = ceil(apps.size / colsWanted.toFloat()).toInt()
        setMeasuredDimension(MeasureSpec.getSize(w), (rows * cellH).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cw = width / colsWanted.toFloat()
        for ((i, a) in apps.withIndex()) {
            val x = (i % colsWanted) * cw + (cw - icon) / 2
            val y = (i / colsWanted) * cellH + context.px(8f)
            home.drawIcon(c, a, x, y, icon)
            val s = TextUtils.ellipsize(a.label, paint, cw - context.px(8f), TextUtils.TruncateAt.END).toString()
            c.drawText(s, x + icon / 2, y + icon + context.px(18f), paint)
            home.badge(c, BadgeService.of(a), x + icon, y)
        }
    }
}

@SuppressLint("ViewConstructor")
class FolderView(act: HomeActivity, folder: Item.Folder?, fixed: Pair<String, List<AppInfo>>?, private val source: RectF?) : Backdrop(act), Morph {
    private lateinit var panelView: View
    private lateinit var titleView: View
    private lateinit var gridView: View
    private val at = IntArray(2)

    /** Places the panel exactly over the icon it belongs to, so it can grow from there and return to it. */
    private fun toSource() {
        val src = source
        val p = panelView
        p.pivotX = 0f
        p.pivotY = 0f
        if (src == null || p.width == 0) {
            p.pivotX = p.width / 2f
            p.pivotY = p.height / 2f
            p.scaleX = 0.82f
            p.scaleY = 0.82f
            return
        }
        p.translationX = 0f
        p.translationY = 0f
        p.scaleX = 1f
        p.scaleY = 1f
        p.getLocationOnScreen(at)
        p.scaleX = src.width() / p.width
        p.scaleY = src.height() / p.height
        p.translationX = src.left - at[0]
        p.translationY = src.top - at[1]
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!entered && panelView.width > 0) {
            entered = true
            toSource()
            val s = Spring(0.84f, 0.40f)
            panelView.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(s.millis).setInterpolator(s).start()
            gridView.alpha = 0f
            gridView.animate().alpha(1f).setStartDelay(40).setDuration(180).start()
            titleView.alpha = 0f
            titleView.translationY = context.px(10f)
            titleView.animate().alpha(1f).translationY(0f).setStartDelay(110).setDuration(220).start()
        }
    }

    private var entered = false

    override fun leave() {
        val p = panelView
        val sx = p.scaleX; val sy = p.scaleY; val tx = p.translationX; val ty = p.translationY
        p.animate().cancel()
        toSource()
        val ex = p.scaleX; val ey = p.scaleY; val etx = p.translationX; val ety = p.translationY
        p.scaleX = sx; p.scaleY = sy; p.translationX = tx; p.translationY = ty
        p.animate().scaleX(ex).scaleY(ey).translationX(etx).translationY(ety).setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        gridView.animate().alpha(0f).setStartDelay(0).setDuration(120).start()
        titleView.animate().alpha(0f).setStartDelay(0).setDuration(90).start()
    }

    init {
        val c = act
        val name = folder?.name ?: fixed!!.first
        val apps = folder?.apps?.mapNotNull { Apps.map[it] } ?: fixed!!.second
        val cols = min(5, maxOf(3, apps.size))
        val title: TextView = if (folder != null) EditText(c).apply {
            setText(name)
            background = null
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) { folder.name = s.toString().ifBlank { "文件夹" }; act.home.invalidate(); act.persist() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        } else TextView(c).apply { text = name }
        title.setTextColor(Color.WHITE)
        title.textSize = 34f
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        val w = (c.px(116f) * cols + c.px(36f)).toInt()
        val grid = IconGrid(act.home, cols, { a, r ->
            act.closeOverlay()
            act.launch(a, r)
        }, { a ->
            if (folder != null) { act.takeOutOfFolder(folder, a.key); act.closeOverlay() }
        })
        grid.apps = apps
        val scroll = ScrollView(c).apply { addView(grid); isVerticalScrollBarEnabled = false }
        val panel = GlassPanel(c, c.px(38f), 0x38FFFFFF).apply {
            setPadding(c.px(18f).toInt(), c.px(20f).toInt(), c.px(18f).toInt(), c.px(20f).toInt())
            isClickable = true
            addView(scroll, LinearLayout.LayoutParams(-1, min(c.px(104f) * ceil(apps.size / cols.toFloat()), c.px(430f)).toInt()))
        }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.px(14f).toInt() })
            addView(panel, LinearLayout.LayoutParams(w, -2))
        }
        panelView = panel
        titleView = title
        gridView = scroll
        addView(col, LayoutParams(w, -2, Gravity.CENTER))
    }
}

const val G_SEARCH = 0
const val G_EDIT = 1
const val G_INFO = 2
const val G_REMOVE = 3

/** The few line symbols the launcher needs, drawn as strokes so no icon font or bitmap ships. */
class Glyph(private val kind: Int, color: Int, private val size: Float) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun getIntrinsicWidth() = size.toInt()
    override fun getIntrinsicHeight() = size.toInt()
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun draw(c: Canvas) {
        val b = bounds
        val u = minOf(b.width(), b.height()) / 22f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        p.strokeWidth = 1.7f * u
        p.style = Paint.Style.STROKE
        when (kind) {
            G_SEARCH -> {
                c.drawCircle(cx - 1.5f * u, cy - 1.5f * u, 6.2f * u, p)
                c.drawLine(cx + 3.2f * u, cy + 3.2f * u, cx + 8f * u, cy + 8f * u, p)
            }
            G_EDIT -> {
                c.drawRoundRect(cx - 6.5f * u, cy - 9f * u, cx + 6.5f * u, cy + 9f * u, 3.2f * u, 3.2f * u, p)
                p.style = Paint.Style.FILL
                for (i in 0 until 4) {
                    val x = cx + (if (i % 2 == 0) -3.6f else 0.8f) * u
                    val y = cy + (if (i < 2) -5.6f else -1.2f) * u
                    c.drawRoundRect(x, y, x + 2.8f * u, y + 2.8f * u, 0.8f * u, 0.8f * u, p)
                }
            }
            G_INFO -> {
                c.drawCircle(cx, cy, 8.6f * u, p)
                c.drawLine(cx, cy - 0.6f * u, cx, cy + 4.2f * u, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy - 4.2f * u, 1.15f * u, p)
            }
            G_REMOVE -> {
                c.drawCircle(cx, cy, 8.6f * u, p)
                c.drawLine(cx - 4f * u, cy, cx + 4f * u, cy, p)
            }
        }
    }
}

class MenuRow(val text: String, val icon: android.graphics.drawable.Drawable?, val red: Boolean, val group: Boolean, val go: () -> Unit)

/** Context menu body: every row on one canvas, clipped to a rounded outline that also casts the shadow. */
@SuppressLint("ViewConstructor")
class MenuList(private val act: HomeActivity, private val rows: List<MenuRow>, private val dark: Boolean) : View(act) {
    private val rowH = context.px(46f)
    private val gapH = context.px(8f)
    private val r = RectF()
    private val at = IntArray(2)
    private var down = -1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = if (dark) Color.WHITE else 0xFF111114.toInt()
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.px(17f) }

    val total get() = (rows.size * rowH + rows.count { it.group } * gapH).toInt()

    init {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setPath(Smooth.path(android.graphics.Path(), 0f, 0f, v.width.toFloat(), v.height.toFloat(), context.px(16f)))
        }
        clipToOutline = true
        elevation = context.px(18f)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), total)

    private fun top(i: Int): Float {
        var y = 0f
        for (n in 0 until i) y += rowH
        for (n in 0..i) if (rows[n].group) y += gapH
        return y
    }

    private fun rowAt(y: Float): Int {
        for (i in rows.indices) { val t = top(i); if (y >= t && y < t + rowH) return i }
        return -1
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(at)
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        Glass.draw(c, r, 0f, if (dark) 0xD01E1E21.toInt() else 0xDCF6F6F8.toInt(), 255, at[0].toFloat(), at[1].toFloat(), border = false)
        val pad = context.px(16f)
        val g = context.px(22f).toInt()
        for ((i, row) in rows.withIndex()) {
            val t = top(i)
            if (row.group) {
                fill.color = if (dark) 0x40000000 else 0x14000000
                c.drawRect(0f, t - gapH, width.toFloat(), t, fill)
            } else if (i > 0) {
                fill.color = if (dark) 0x30FFFFFF else 0x243C3C43
                c.drawRect(0f, t, width.toFloat(), t + 1f, fill)
            }
            if (i == down) {
                fill.color = if (dark) 0x26FFFFFF else 0x1A000000
                c.drawRect(0f, t, width.toFloat(), t + rowH, fill)
            }
            paint.color = if (row.red) (if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()) else ink
            val room = width - pad * 2 - (if (row.icon != null) g + pad / 2 else 0f)
            val s = TextUtils.ellipsize(row.text, paint, room, TextUtils.TruncateAt.END).toString()
            c.drawText(s, pad, t + rowH / 2 - (paint.ascent() + paint.descent()) / 2, paint)
            row.icon?.let {
                val x = (width - pad - g).toInt()
                val y = (t + (rowH - g) / 2).toInt()
                it.setBounds(x, y, x + g, y + g)
                it.draw(c)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val i = if (e.x < 0 || e.x > width) -1 else rowAt(e.y)
                if (i != down) { down = i; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val i = down
                down = -1
                invalidate()
                if (i >= 0) { act.closeOverlay(); rows[i].go() }
            }
            MotionEvent.ACTION_CANCEL -> { down = -1; invalidate() }
        }
        return true
    }
}

@SuppressLint("ViewConstructor")
class MenuView(act: HomeActivity, private val item: Item, private val r: RectF) : FrameLayout(act) {
    private val home = act.home
    private val full = RectF()

    init {
        setWillNotDraw(false)
        isClickable = true
        setOnClickListener { act.closeOverlay() }
        val c = act
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) Color.WHITE else 0xFF111114.toInt()
        val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
        val g = c.px(22f)
        val rows = ArrayList<MenuRow>()
        val app = (item as? Item.App)?.let { Apps.map[it.key] }
        if (app != null) for (s in Apps.shortcuts(c, app)) {
            val icon = try { Apps.launcher(c).getShortcutIconDrawable(s, resources.displayMetrics.densityDpi) } catch (_: Exception) { null }
            rows.add(MenuRow(s.shortLabel?.toString() ?: "", icon, false, false) {
                try { Apps.launcher(c).startShortcut(s, null, null) } catch (_: Exception) {}
            })
        }
        val first = rows.size
        rows.add(MenuRow("编辑主屏幕", Glyph(G_EDIT, ink, g), false, first > 0) { act.home.startJiggle() })
        if (app != null) rows.add(MenuRow("应用信息", Glyph(G_INFO, ink, g), false, false) { act.appInfoScreen(app) })
        val gone = if (item is Item.Widget) "移除小组件" else if (item is Item.Folder) "解散文件夹" else "移除 App"
        rows.add(MenuRow(gone, Glyph(G_REMOVE, red, g), true, false) { act.confirmRemove(item) })

        val list = MenuList(act, rows, dark)
        val w = c.px(260f).toInt()
        val h = list.total
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val edge = c.px(16f).toInt()
        val gap = c.px(14f).toInt()
        val lp = LayoutParams(w, h)
        // Hangs from the icon's own side, so it opens towards the middle of the screen.
        val fromLeft = r.centerX() < sw / 2f
        lp.leftMargin = (if (fromLeft) r.left.toInt() else (r.right - w).toInt()).coerceIn(edge, sw - w - edge)
        val below = r.bottom + gap + h + edge < sh
        lp.topMargin = if (below) (r.bottom + gap).toInt() else (r.top - gap - h).toInt().coerceAtLeast(edge)
        addView(list, lp)
        list.pivotX = if (fromLeft) c.px(32f) else w - c.px(32f)
        list.pivotY = if (below) 0f else h.toFloat()
        list.scaleX = 0.25f
        list.scaleY = 0.25f
        list.alpha = 0f
        list.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
    }

    override fun onDraw(c: Canvas) {
        full.set(0f, 0f, width.toFloat(), height.toFloat())
        Glass.draw(c, full, 0f, 0x2E000000, 150, border = false)
        if (item !is Item.Widget) home.drawLifted(c, item, r)
    }
}

@SuppressLint("ViewConstructor")
class ConfirmView(act: HomeActivity, item: Item) : Backdrop(act) {
    init {
        val c = act
        val panel = GlassPanel(c, c.px(20f), 0x60FFFFFF).apply {
            isClickable = true
            setPadding(0, c.px(20f).toInt(), 0, c.px(6f).toInt())
        }
        val name = when (item) {
            is Item.App -> Apps.map[item.key]?.label ?: "App"
            is Item.Folder -> item.name
            else -> "小组件"
        }
        panel.addView(TextView(c).apply {
            text = "移除“$name”？"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, c.px(12f).toInt())
        })
        fun btn(t: String, col: Int, go: () -> Unit) = panel.addView(TextView(c).apply {
            text = t
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(col)
            setPadding(0, c.px(12f).toInt(), 0, c.px(12f).toInt())
            setOnClickListener { act.closeOverlay(); go() }
        })
        if (item is Item.App) {
            btn("卸载 App", 0xFFFF453A.toInt()) { act.removeItem(item, true) }
            btn("从主屏幕移除", Color.WHITE) { act.removeItem(item, false) }
        } else btn("移除", 0xFFFF453A.toInt()) { act.removeItem(item, false) }
        btn("取消", 0xFF64D2FF.toInt()) {}
        addView(panel, LayoutParams(c.px(300f).toInt(), -2, Gravity.CENTER))
    }
}

private fun searchField(c: Context, hint: String) = EditText(c).apply {
    this.hint = hint
    setHintTextColor(0xB3FFFFFF.toInt())
    setTextColor(Color.WHITE)
    textSize = 17f
    background = null
    setSingleLine()
    imeOptions = EditorInfo.IME_ACTION_SEARCH
    setPadding(c.px(16f).toInt(), c.px(12f).toInt(), c.px(20f).toInt(), c.px(12f).toInt())
    val g = c.px(20f).toInt()
    setCompoundDrawables(Glyph(G_SEARCH, 0xB3FFFFFF.toInt(), g.toFloat()).apply { setBounds(0, 0, g, g) }, null, null, null)
    compoundDrawablePadding = c.px(8f).toInt()
}

@SuppressLint("ViewConstructor")
class SpotlightView(act: HomeActivity) : Backdrop(act) {
    init {
        val c = act
        val field = searchField(c, "搜索")
        val top = act.home.insetTop + c.px(70f).toInt()
        val w = min(c.px(640f).toInt(), resources.displayMetrics.widthPixels - c.px(32f).toInt())
        val grid = IconGrid(act.home, 6, { a, r ->
            act.closeOverlay()
            act.launch(a, r)
        })
        grid.apps = suggestions(act)
        val box = GlassPanel(c, c.px(24f), 0x48FFFFFF).apply { isClickable = true; addView(field) }
        val pane = ScrollView(c).apply { addView(grid); isVerticalScrollBarEnabled = false }
        val results = GlassPanel(c, c.px(28f), 0x38FFFFFF).apply {
            isClickable = true
            setPadding(0, c.px(10f).toInt(), 0, c.px(6f).toInt())
            addView(pane)
        }
        // The panel hugs its rows, so a short list does not leave an empty slab under the field.
        fun fit() {
            val rows = ceil(grid.apps.size / 6f).toInt()
            results.visibility = if (rows == 0) GONE else VISIBLE
            pane.layoutParams = LinearLayout.LayoutParams(-1, min(rows * c.px(104f), c.px(424f)).toInt())
        }
        fit()
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(box, LinearLayout.LayoutParams(-1, -2))
            addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.px(12f).toInt() })
        }
        addView(col, LayoutParams(w, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = top })
        Bg.run { Apps.buildSearch() }
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                grid.apps = if (s.isBlank()) suggestions(act) else Apps.find(s.toString())
                fit()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
        field.setOnEditorActionListener { v, _, _ ->
            val q = v.text.toString()
            val hit = Apps.find(q).firstOrNull()
            act.closeOverlay()
            if (hit != null) act.launch(hit, act.centerRect()) else if (q.isNotBlank()) act.webSearch(q)
            true
        }
        field.requestFocus()
        field.postDelayed({
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0)
        }, 120)
    }

    private fun suggestions(a: HomeActivity) = a.home.recents + a.home.layoutModel.dock.mapNotNull { Apps.map[it.key] }
}

/** App library page: every app, grouped automatically, one canvas view for all tiles. */
@SuppressLint("ViewConstructor")
class LibraryView(private val act: HomeActivity) : FrameLayout(act) {
    private val cats = Frequent.top.let { top ->
        if (top.isEmpty()) Apps.categories() else listOf("高频" to top) + Apps.categories()
    }
    private val tiles = Tiles()
    private val results = IconGrid(act.home, 6, { a, r -> act.launch(a, r) })
    private val pane = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(tiles) }
    private val found = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(results); visibility = GONE }

    init {
        val c = act
        val field = searchField(c, "资源库")
        val box = GlassPanel(c, c.px(22f), 0x40FFFFFF).apply { addView(field) }
        val top = act.home.insetTop + c.px(14f).toInt()
        val w = min(c.px(520f).toInt(), resources.displayMetrics.widthPixels - c.px(32f).toInt())
        addView(box, LayoutParams(w, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = top })
        val lp = { LayoutParams(-1, -1).apply { topMargin = top + c.px(62f).toInt(); leftMargin = c.px(40f).toInt(); rightMargin = c.px(40f).toInt() } }
        addView(pane, lp())
        addView(found, lp())
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                Apps.buildSearch()
                val on = s.isNotBlank()
                results.apps = if (on) Apps.find(s.toString()) else emptyList()
                found.visibility = if (on) VISIBLE else GONE
                pane.visibility = if (on) GONE else VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
    }

    fun refresh() { tiles.invalidate(); results.invalidate() }

    private inner class Tiles : View(act) {
        private val perRow get() = (measuredWidth / context.px(190f)).toInt().coerceIn(3, 6)
        private val pad = context.px(14f)
        private val labelH = context.px(32f)
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = context.px(12f); textAlign = Paint.Align.CENTER
        }
        private val r = RectF()
        private val at = IntArray(2)
        private fun size() = (width - pad * (perRow - 1)) / perRow

        private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val s = size()
                val i = (e.y / (s + labelH + pad)).toInt() * perRow + (e.x / (s + pad)).toInt()
                val cat = cats.getOrNull(i) ?: return true
                val inX = e.x % (s + pad) > s / 2
                val inY = e.y % (s + labelH + pad) > s / 2
                val slot = (if (inY) 2 else 0) + if (inX) 1 else 0
                if (slot == 3 && cat.second.size > 4) {
                    getLocationOnScreen(at)
                    val tx = at[0] + (i % perRow) * (s + pad)
                    val ty = at[1] + (i / perRow) * (s + labelH + pad)
                    act.openCategory(cat.first, cat.second, RectF(tx, ty, tx + s, ty + s))
                }
                else cat.second.getOrNull(slot)?.let {
                    val g = context.px(12f)
                    val big = (s - g * 3) / 2
                    getLocationOnScreen(at)
                    val x = at[0] + (i % perRow) * (s + pad) + g + (slot % 2) * (big + g)
                    val y = at[1] + (i / perRow) * (s + labelH + pad) + g + (slot / 2) * (big + g)
                    act.launch(it, RectF(x, y, x + big, y + big))
                }
                return true
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = gd.onTouchEvent(e)

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val cols = (width / context.px(190f)).toInt().coerceIn(3, 6)
            val s = (width - pad * (cols - 1)) / cols
            val rows = ceil(cats.size / cols.toFloat()).toInt()
            setMeasuredDimension(width, (rows * (s + labelH + pad)).toInt())
        }

        override fun onDraw(c: Canvas) {
            val s = size()
            getLocationOnScreen(at)
            val g = context.px(12f)
            val big = (s - g * 3) / 2
            for ((i, cat) in cats.withIndex()) {
                val x = (i % perRow) * (s + pad)
                val y = (i / perRow) * (s + labelH + pad)
                r.set(x, y, x + s, y + s)
                Glass.draw(c, r, context.px(26f), 0x38FFFFFF, 255, at[0].toFloat(), at[1].toFloat())
                val list = cat.second
                for (n in 0 until min(4, list.size)) {
                    val ix = x + g + (n % 2) * (big + g)
                    val iy = y + g + (n / 2) * (big + g)
                    if (n == 3 && list.size > 4) {
                        val m = (big - context.px(6f)) / 2
                        for (k in 0 until min(4, list.size - 3)) {
                            act.home.drawIcon(c, list[3 + k], ix + (k % 2) * (m + context.px(6f)), iy + (k / 2) * (m + context.px(6f)), m)
                        }
                        act.home.badge(c, list.drop(3).sumOf(BadgeService::of), ix + big, iy)
                    } else {
                        act.home.drawIcon(c, list[n], ix, iy, big)
                        act.home.badge(c, BadgeService.of(list[n]), ix + big, iy)
                    }
                }
                c.drawText(cat.first, x + s / 2, y + s + context.px(21f), paint)
            }
        }
    }
}

@SuppressLint("ViewConstructor")
class GalleryView(act: HomeActivity) : Backdrop(act) {
    init {
        val c = act
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        fun head(t: String) = list.addView(TextView(c).apply {
            text = t
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(c.px(4f).toInt(), c.px(16f).toInt(), 0, c.px(6f).toInt())
        })
        fun row(t: String, go: () -> Unit) = list.addView(TextView(c).apply {
            text = t
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(c.px(4f).toInt(), c.px(11f).toInt(), 0, c.px(11f).toInt())
            setOnClickListener { go() }
        })
        head("内置")
        row("时钟") { act.addBuiltin(K_CLOCK) }
        row("日历") { act.addBuiltin(K_CAL) }
        row("电池") { act.addBuiltin(K_BATT) }
        val pm = c.packageManager
        val providers = AppWidgetManager.getInstance(c).installedProviders.groupBy { it.provider.packageName }
        for ((pkg, ps) in providers.toSortedMap()) {
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            head(name)
            for (p in ps) {
                val sx = ceil(p.minWidth / (c.px(150f))).toInt().coerceIn(2, 4)
                val sy = ceil(p.minHeight / (c.px(100f))).toInt().coerceIn(2, 4)
                row("${p.loadLabel(pm)}  ·  $sx×$sy") { act.addHosted(p, sx, sy) }
            }
        }
        val panel = GlassPanel(c, c.px(30f), 0x38FFFFFF).apply {
            isClickable = true
            setPadding(c.px(22f).toInt(), c.px(12f).toInt(), c.px(22f).toInt(), c.px(12f).toInt())
            addView(TextView(c).apply {
                text = "添加小组件"
                textSize = 34f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(c.px(4f).toInt(), c.px(6f).toInt(), 0, c.px(6f).toInt())
            })
            addView(ScrollView(c).apply { addView(list); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, -1))
        }
        val h = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        addView(panel, LayoutParams(min(c.px(520f).toInt(), resources.displayMetrics.widthPixels - c.px(32f).toInt()), h, Gravity.CENTER))
    }
}
