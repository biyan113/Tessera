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
        Glass.draw(c, r, 0f, 0x38000000, 245, border = false)
    }
}

/** Canvas icon grid: one view however many apps it shows. */
@SuppressLint("ViewConstructor")
class IconGrid(
    private val home: HomeView,
    private val colsWanted: Int,
    private val onTap: (AppInfo) -> Unit,
    private val onLong: ((AppInfo) -> Unit)? = null,
) : View(home.context) {
    var apps: List<AppInfo> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    private val icon = context.px(64f)
    private val cellH = context.px(104f)
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = context.px(12f); textAlign = Paint.Align.CENTER
    }

    private fun at(e: MotionEvent): AppInfo? {
        val cw = width / colsWanted.toFloat()
        val i = (e.y / cellH).toInt() * colsWanted + (e.x / cw).toInt()
        return apps.getOrNull(i)
    }

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { at(e)?.let(onTap); return true }
        override fun onLongPress(e: MotionEvent) { at(e)?.let { onLong?.invoke(it) } }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent) = gd.onTouchEvent(e)

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
        }
    }
}

@SuppressLint("ViewConstructor")
class FolderView(act: HomeActivity, folder: Item.Folder?, fixed: Pair<String, List<AppInfo>>?) : Backdrop(act) {
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
        val grid = IconGrid(act.home, cols, { a ->
            act.closeOverlay()
            act.launch(a, RectF(width / 2f, height / 2f, width / 2f + 1, height / 2f + 1))
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
        addView(col, LayoutParams(w, -2, Gravity.CENTER))
    }
}

@SuppressLint("ViewConstructor")
class MenuView(act: HomeActivity, item: Item, r: RectF) : FrameLayout(act) {
    init {
        isClickable = true
        setOnClickListener { act.closeOverlay() }
        setBackgroundColor(0x55000000)
        val c = act
        val panel = GlassPanel(c, c.px(16f), 0x70FFFFFF)
        val app = (item as? Item.App)?.let { Apps.map[it.key] }
        fun row(t: String, red: Boolean = false, go: () -> Unit) {
            panel.addView(TextView(c).apply {
                text = t
                textSize = 17f
                setTextColor(if (red) 0xFFFF453A.toInt() else Color.WHITE)
                setPadding(c.px(18f).toInt(), c.px(13f).toInt(), c.px(18f).toInt(), c.px(13f).toInt())
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { act.closeOverlay(); go() }
            })
        }
        if (app != null) for (s in Apps.shortcuts(c, app)) row(s.shortLabel?.toString() ?: "") {
            try { Apps.launcher(c).startShortcut(s, null, null) } catch (_: Exception) {}
        }
        row("编辑主屏幕") { act.home.startJiggle() }
        if (app != null) row("应用信息") { act.appInfoScreen(app) }
        row(if (item is Item.Widget) "移除小组件" else if (item is Item.Folder) "解散文件夹" else "移除 App", true) { act.confirmRemove(item) }
        val w = c.px(250f).toInt()
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val lp = LayoutParams(w, -2)
        lp.leftMargin = r.left.toInt().coerceIn(c.px(16f).toInt(), sw - w - c.px(16f).toInt())
        val rows = panel.childCount
        val guess = (c.px(47f) * rows).toInt()
        lp.topMargin = if (r.bottom + guess + c.px(32f) < sh) (r.bottom + c.px(26f)).toInt() else (r.top - guess - c.px(12f)).toInt()
        addView(panel, lp)
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
    setPadding(c.px(20f).toInt(), c.px(12f).toInt(), c.px(20f).toInt(), c.px(12f).toInt())
}

@SuppressLint("ViewConstructor")
class SpotlightView(act: HomeActivity) : Backdrop(act) {
    init {
        val c = act
        val field = searchField(c, "搜索")
        val top = act.home.insetTop + c.px(70f).toInt()
        val w = min(c.px(640f).toInt(), resources.displayMetrics.widthPixels - c.px(32f).toInt())
        val grid = IconGrid(act.home, 6, { a ->
            act.closeOverlay()
            act.launch(a, RectF(0f, 0f, 1f, 1f))
        })
        grid.apps = suggestions(act)
        val col = GlassPanel(c, c.px(24f), 0x40FFFFFF).apply {
            isClickable = true
            addView(field)
            addView(ScrollView(c).apply { addView(grid); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, c.px(320f).toInt()))
        }
        addView(col, LayoutParams(w, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = top })
        Bg.run { Apps.buildSearch() }
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                grid.apps = if (s.isBlank()) suggestions(act) else Apps.find(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
        field.setOnEditorActionListener { v, _, _ ->
            val q = v.text.toString()
            val hit = Apps.find(q).firstOrNull()
            act.closeOverlay()
            if (hit != null) act.launch(hit, RectF(0f, 0f, 1f, 1f)) else if (q.isNotBlank()) act.webSearch(q)
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
    private val results = IconGrid(act.home, 6, { a -> act.launch(a, RectF(0f, 0f, 1f, 1f)) })
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
        private val perRow get() = if (width > height * 0 && resources.configuration.orientation == 2) 5 else 3
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
                if (slot == 3 && cat.second.size > 4) act.openCategory(cat.first, cat.second)
                else cat.second.getOrNull(slot)?.let { act.launch(it, RectF(e.rawX, e.rawY, e.rawX + 1, e.rawY + 1)) }
                return true
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent) = gd.onTouchEvent(e)

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val s = (width - pad * (perRow - 1)) / perRow
            val rows = ceil(cats.size / perRow.toFloat()).toInt()
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
                    } else act.home.drawIcon(c, list[n], ix, iy, big)
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
