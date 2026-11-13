package app.tessera.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a [Card] needs to know to rule a hairline under one of its children. */
interface Sep {
    /** Where the separator below this row starts: iOS insets it to the text, not the icon. */
    val sepInset: Float
    /** A filled (selected) row swallows the hairlines on both of its sides. */
    val solid: Boolean get() = false
}

private fun hair(c: Canvas, x0: Float, x1: Float, y: Float, p: Paint) {
    p.style = Paint.Style.FILL
    p.color = Theme.separator
    c.drawRect(x0, y - 1f, x1, y, p)
}

/** Inset grouped card: clips its rows to a 10dp radius and rules the hairlines between them. */
@SuppressLint("ViewConstructor")
class Card(c: Context, private val side: Boolean) : LinearLayout(c) {
    private val p = Paint()
    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(10f))
        }
    }

    override fun onDraw(c: Canvas) = c.drawColor(if (side) Theme.cardSide else Theme.card)

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        var prev: View? = null
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility != VISIBLE) continue
            val a = prev as? Sep
            if (prev != null && a?.solid != true && (v as? Sep)?.solid != true) {
                hair(c, a?.sepInset ?: dp(16f), width.toFloat(), prev.bottom.toFloat(), p)
            }
            prev = v
        }
    }
}

enum class Acc { NONE, CHEVRON, SWITCH, CHECK }

/**
 * The one list row: optional icon tile or app icon, title, optional value, and an accessory.
 * A single self-drawing view per row keeps a 200-app list cheap; the switch is drawn here too,
 * so a row never needs child views.
 */
@SuppressLint("ViewConstructor")
class Row(c: Context, var title: String) : View(c), Sep {
    var value: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var acc = Acc.NONE
    var tile = 0
    var glyph: Drawable? = null
    var app: AppEntry? = null
    /** Theme-dependent title colour (tint or destructive rows); null is the normal text colour. */
    var tone: (() -> Int)? = null
    var center = false
    /** Non-null reserves the leading checkmark column of a Wi-Fi style list; true draws the mark. */
    var leadCheck: Boolean? = null
    var lock = false
    var signal = -1
    var selectedRow = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** False draws the row as unavailable and ignores input: a permission or the daemon is missing. */
    var usable = true
        set(v) { if (field != v) { field = v; invalidate() } }
    var on = false
        private set
    var onTap: (() -> Unit)? = null
    var onToggle: ((Boolean) -> Unit)? = null

    private var pos = 0f
    private var anim: ValueAnimator? = null
    private var down = false
    private var inSwitch = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(17f) }
    private val vp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(17f) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(3f), 0f, dp(2f), 0x30000000)
    }
    init { contentDescription = title }

    /** Delayed like iOS, so starting a scroll on a row does not flash it. */
    private val press = Runnable { down = true; invalidate() }
    private val release = Runnable { down = false; invalidate() }

    fun setOn(v: Boolean, animate: Boolean) {
        if (on == v && anim == null) { pos = if (v) 1f else 0f; return }
        on = v
        anim?.cancel(); anim = null
        val to = if (v) 1f else 0f
        if (!animate || !isAttachedToWindow) { pos = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(pos, to).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun lead(): Float {
        var x = dp(16f)
        if (leadCheck != null) x += dp(24f)
        if (tile != 0 || app != null) x += dp(41f)
        return x
    }

    override val sepInset get() = lead()
    override val solid get() = selectedRow

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(44))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2
        p.style = Paint.Style.FILL
        if (selectedRow) {
            p.color = Theme.tint
            c.drawRoundRect(0f, 0f, w, h, dp(10f), dp(10f), p)
        } else if (down) {
            c.drawColor(Theme.pressed)
        }
        val fg = if (selectedRow) Color.WHITE else Theme.text
        val sub = if (selectedRow) 0xD9FFFFFF.toInt() else Theme.secondary

        var x = dp(16f)
        leadCheck?.let { if (it) G.check(c, x + dp(7f), cy, Theme.tint); x += dp(24f) }
        if (tile != 0) {
            p.color = tile
            c.drawRoundRect(x, cy - dp(14.5f), x + dp(29f), cy + dp(14.5f), dp(7f), dp(7f), p)
            glyph?.let {
                val g = dp(9.5f)
                it.setBounds((x + dp(14.5f) - g).roundToInt(), (cy - g).roundToInt(), (x + dp(14.5f) + g).roundToInt(), (cy + g).roundToInt())
                it.draw(c)
            }
        } else app?.let { a ->
            Apps.icon(context, a) { invalidate() }?.let { c.drawBitmap(it, x, cy - it.height / 2f, null) }
        }
        x = lead()

        var right = w - dp(16f)
        when (acc) {
            Acc.CHEVRON -> { G.chevron(c, right, cy, if (selectedRow) 0x99FFFFFF.toInt() else Theme.chevron); right -= dp(14f) }
            Acc.SWITCH -> { drawSwitch(c, right - dp(51f), cy - dp(15.5f)); right -= dp(59f) }
            Acc.CHECK -> { if (on) G.check(c, right - dp(8f), cy, Theme.tint); right -= dp(24f) }
            Acc.NONE -> {}
        }
        if (signal >= 0) { G.wifi(c, right - dp(9f), cy, signal, fg); right -= dp(27f) }
        if (lock) { G.lock(c, right - dp(5f), cy - dp(1f), fg); right -= dp(18f) }
        value?.let {
            vp.color = sub
            val s = TextUtils.ellipsize(it, vp, (right - x) * 0.62f, TextUtils.TruncateAt.END).toString()
            val vw = vp.measureText(s)
            c.drawText(s, right - vw, base(vp, cy), vp)
            right -= vw + dp(8f)
        }
        tp.color = tone?.invoke() ?: fg
        if (!usable) tp.alpha = 110
        if (center) {
            tp.textAlign = Paint.Align.CENTER
            c.drawText(title, w / 2, base(tp, cy), tp)
        } else {
            tp.textAlign = Paint.Align.LEFT
            c.drawText(TextUtils.ellipsize(title, tp, max(0f, right - x), TextUtils.TruncateAt.END).toString(), x, base(tp, cy), tp)
        }
    }

    private fun drawSwitch(c: Canvas, x: Float, y: Float) {
        val h = dp(31f)
        p.color = mix(Theme.switchOff, Theme.green, pos)
        if (!usable) p.alpha = 90
        c.drawRoundRect(x, y, x + dp(51f), y + h, h / 2, h / 2, p)
        p.alpha = 255
        c.drawCircle(x + dp(15.5f) + pos * dp(20f), y + h / 2, dp(13.5f), knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val canToggle = acc == Acc.SWITCH && onToggle != null
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!usable) return false
                inSwitch = canToggle && e.x > width - dp(83f)
                if (!inSwitch && onTap == null) return false
                downX = e.x; downY = e.y
                if (!inSwitch) postDelayed(press, 70)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                removeCallbacks(press)
                if (down) { down = false; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(press)
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) return true
                if (inSwitch) onToggle?.invoke(!on)
                else {
                    down = true; invalidate()
                    postDelayed(release, 160)
                    onTap?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(press); down = false; invalidate() }
        }
        return true
    }
}

/**
 * Wires a switch row to a setting: the knob moves at once, the write happens elsewhere, and the
 * row then follows whatever the system reports. A refresh that still shows the old state is
 * ignored for a few seconds (radios take a while), but a failed write snaps back immediately.
 * Returns the sync function to call on every refresh.
 */
fun bindToggle(r: Row, read: () -> Boolean?, write: (Boolean, (Boolean) -> Unit) -> Unit): () -> Unit {
    var target: Boolean? = null
    var until = 0L
    fun sync() {
        val v = read()
        r.usable = v != null
        if (v == null) { r.setOn(false, false); return }
        if (target != null && v != target && SystemClock.uptimeMillis() < until) return
        target = null
        r.setOn(v, true)
    }
    r.acc = Acc.SWITCH
    r.onToggle = { want ->
        r.setOn(want, true)
        target = want
        until = SystemClock.uptimeMillis() + 3500
        write(want) { ok ->
            if (!ok) { target = null; sync() } else { r.postDelayed({ sync() }, 700); r.postDelayed({ sync() }, 3600) }
        }
    }
    return ::sync
}

/** iOS slider as a card row. Drags are relative to the knob, so a touch never makes the value jump. */
@SuppressLint("ViewConstructor")
class SliderRow(c: Context, private val ends: Ends, private val steps: Int = 0) : View(c), Sep {
    enum class Ends { NONE, SUN, SPEAKER, LETTER }

    /** 0..1; with [steps] it only ever rests on one of the evenly spaced stops. */
    var value = 0f
        private set
    /** Called while dragging (final=false) and once on release (final=true). */
    var onChange: ((Float, Boolean) -> Unit)? = null
    var tracking = false
        private set
    var usable = true
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(4f), 0f, dp(2f), 0x38000000)
    }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Ui.semibold }
    private val quiet = if (ends == Ends.SPEAKER) c.getDrawable(R.drawable.ic_mute) else null
    private val loud = if (ends == Ends.SPEAKER) c.getDrawable(R.drawable.ic_sound) else null
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f
    private var downV = 0f
    private var armed = false

    override val sepInset get() = dp(16f)
    private val x0 get() = if (ends == Ends.NONE) dp(16f) + dp(14f) else dp(62f)
    private val x1 get() = width - x0

    /** Programmatic updates lose to the finger: a volume broadcast must not fight an ongoing drag. */
    fun set(v: Float) {
        if (tracking) return
        val n = snap(v)
        if (n != value) { value = n; invalidate() }
    }

    private fun snap(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (steps > 1) (c * (steps - 1)).roundToInt() / (steps - 1f) else c
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(if (steps in 2..12) 56 else 48))

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val a = x0
        val b = x1
        val kx = a + (b - a) * value
        val grey = Theme.secondary
        when (ends) {
            Ends.SUN -> { G.sun(c, dp(30f), cy, dp(7f), grey); G.sun(c, width - dp(30f), cy, dp(11f), grey) }
            Ends.SPEAKER -> {
                val s = dp(11)
                quiet?.run { setTint(grey); setBounds(dp(30) - s, cy.toInt() - s, dp(30) + s, cy.toInt() + s); draw(c) }
                loud?.run { setTint(grey); setBounds(width - dp(30) - s, cy.toInt() - s, width - dp(30) + s, cy.toInt() + s); draw(c) }
            }
            Ends.LETTER -> {
                letter.color = Theme.text
                letter.textSize = dp(13f); c.drawText("A", dp(30f), base(letter, cy), letter)
                letter.textSize = dp(22f); c.drawText("A", width - dp(30f), base(letter, cy), letter)
            }
            Ends.NONE -> {}
        }
        p.style = Paint.Style.FILL
        p.color = Theme.track
        c.drawRoundRect(a, cy - dp(2f), b, cy + dp(2f), dp(2f), dp(2f), p)
        if (steps in 2..12) for (i in 0 until steps) {
            val tx = a + (b - a) * i / (steps - 1f)
            c.drawRect(tx - dp(1f), cy - dp(5f), tx + dp(1f), cy + dp(5f), p)
        } else {
            p.color = Theme.tint
            c.drawRoundRect(a, cy - dp(2f), kx, cy + dp(2f), dp(2f), dp(2f), p)
        }
        if (!usable) knob.alpha = 150
        c.drawCircle(kx, cy, dp(14f), knob)
        knob.alpha = 255
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!usable) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downV = value; armed = false }
            MotionEvent.ACTION_MOVE -> {
                // Only a clearly horizontal drag takes the gesture away from the scrolling page.
                if (!armed && abs(e.x - downX) > slop) { armed = true; tracking = true; parent.requestDisallowInterceptTouchEvent(true) }
                if (armed) move(snap(downV + (e.x - downX) / max(1f, x1 - x0)), false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // A plain tap on a stepped slider jumps to the nearest stop, which is how its ticks read.
                val tapped = !armed && steps in 2..12 && e.actionMasked == MotionEvent.ACTION_UP
                if (tapped) value = snap((e.x - x0) / max(1f, x1 - x0))
                val moved = armed || tapped
                // Cleared before the callback: its follow-up refresh must be able to correct the knob.
                armed = false
                tracking = false
                if (moved) { invalidate(); onChange?.invoke(value, true) }
            }
        }
        return true
    }

    private fun move(v: Float, final: Boolean) {
        if (v == value) return
        value = v
        invalidate()
        onChange?.invoke(v, final)
    }
}

/** Grey 13sp text above or below a card. */
@SuppressLint("ViewConstructor")
class Label(c: Context, s: String) : TextView(c), Themed {
    init {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(13f))
        setLineSpacing(dp(2f), 1f)
        restyle()
    }
    override fun restyle() = setTextColor(Theme.secondary)
}

/** Detail-pane navigation bar: centred title and, on nested pages, the tinted way back. */
@SuppressLint("ViewConstructor")
class NavBar(c: Context, private val title: String, private val back: String?, private val onBack: () -> Unit) : View(c) {
    /** True once content has scrolled underneath, which is when iOS draws the bar's hairline. */
    var lifted = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(17f); typeface = Ui.semibold; textAlign = Paint.Align.CENTER }
    private val bp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(17f) }
    private val p = Paint()
    private var backW = 0f
    private var down = false

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(44))

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        c.drawColor(Theme.bg)
        tp.color = Theme.text
        val tw = tp.measureText(title)
        backW = 0f
        if (back != null) {
            val color = if (down) Theme.tint and 0xFFFFFF or 0x66000000 else Theme.tint
            G.back(c, dp(12f), cy, color)
            bp.color = color
            val room = (width - tw) / 2 - dp(36f)
            val s = TextUtils.ellipsize(back, bp, max(0f, room), TextUtils.TruncateAt.END).toString()
            c.drawText(s, dp(26f), base(bp, cy), bp)
            backW = dp(26f) + bp.measureText(s)
        }
        val t = TextUtils.ellipsize(title, tp, width - 2 * (backW + dp(12f)).coerceAtLeast(dp(16f)), TextUtils.TruncateAt.END).toString()
        c.drawText(t, width / 2f, base(tp, cy), tp)
        if (lifted) hair(c, 0f, width.toFloat(), height.toFloat(), p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (back == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (e.x > max(backW + dp(16f), dp(72f))) return false; down = true; invalidate() }
            MotionEvent.ACTION_UP -> { if (down) onBack(); down = false; invalidate() }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }
}

/** Search pill. The EditText is stock for the IME's sake; the pill, magnifier and clear button are drawn. */
@SuppressLint("ViewConstructor")
class SearchField(c: Context, private val onQuery: (String) -> Unit) : FrameLayout(c), Themed {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val edit = EditText(c).apply {
        background = null
        hint = "搜索"
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(17f))
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(30), 0, dp(30), 0)
        includeFontPadding = false
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) { onQuery(s.toString().trim()); this@SearchField.invalidate() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
    }

    init {
        setWillNotDraw(false)
        addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        restyle()
    }

    override fun restyle() {
        edit.setTextColor(Theme.text)
        edit.setHintTextColor(Theme.secondary)
    }

    override fun onDraw(c: Canvas) {
        p.color = Theme.searchFill
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(10f), dp(10f), p)
        G.magnifier(c, dp(16f), height / 2f, Theme.secondary)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (edit.length() > 0) G.clear(c, width - dp(16f), height / 2f, Theme.secondary, Theme.sidebar)
    }

    /** The clear button sits over the EditText's padding, so it has to claim its touches first. */
    override fun onInterceptTouchEvent(e: MotionEvent) = edit.length() > 0 && e.x > width - dp(32f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) edit.setText("")
        return true
    }
}

/** The "Apple ID" slot of the sidebar, repurposed for the device itself. */
@SuppressLint("ViewConstructor")
class DeviceCard(c: Context, private val name: String, private val detail: String, private val onTap: () -> Unit) : View(c), Sep {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(20f) }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(13f) }
    private var down = false
    override val sepInset get() = dp(16f)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(80))

    override fun onDraw(c: Canvas) {
        if (down) c.drawColor(Theme.pressed)
        val cy = height / 2f
        val r = dp(28f)
        val cx = dp(14f) + r
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFB4B4BC.toInt(), 0xFF7C7C84.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        // A tablet outline as the "avatar": bezel, then screen knocked back in.
        p.color = Color.WHITE
        c.drawRoundRect(cx - dp(11f), cy - dp(15f), cx + dp(11f), cy + dp(15f), dp(3.5f), dp(3.5f), p)
        p.color = 0xFF8E8E96.toInt()
        c.drawRoundRect(cx - dp(8.5f), cy - dp(12.5f), cx + dp(8.5f), cy + dp(12.5f), dp(1.5f), dp(1.5f), p)
        val x = cx + r + dp(12f)
        val room = width - x - dp(12f)
        tp.color = Theme.text
        sub.color = Theme.secondary
        c.drawText(TextUtils.ellipsize(name, tp, room, TextUtils.TruncateAt.END).toString(), x, cy - dp(3f), tp)
        c.drawText(TextUtils.ellipsize(detail, sub, room, TextUtils.TruncateAt.END).toString(), x, cy + dp(17f), sub)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP -> { down = false; invalidate(); onTap() }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }
}

/** Light/Dark chooser: two miniature screens with a radio under each, as in iPadOS Display & Brightness. */
@SuppressLint("ViewConstructor")
class AppearancePicker(c: Context, private val onPick: (Boolean) -> Unit) : View(c), Sep {
    /** Which thumbnail carries the radio mark; null while the mode cannot be read. */
    var dark: Boolean? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    override val sepInset get() = dp(16f)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(208))

    private fun cx(night: Boolean) = width / 2f + dp(if (night) 76f else -76f)

    private fun thumb(c: Canvas, night: Boolean) {
        val x = cx(night)
        val w = dp(74f)
        val top = dp(18f)
        val h = dp(110f)
        p.style = Paint.Style.FILL
        p.color = if (night) 0xFF1C1C1E.toInt() else 0xFFE9EEF8.toInt()
        c.drawRoundRect(x - w / 2, top, x + w / 2, top + h, dp(9f), dp(9f), p)
        tp.typeface = Ui.semibold
        tp.textSize = dp(17f)
        tp.color = if (night) Color.WHITE else Color.BLACK
        c.drawText("9:41", x, top + dp(34f), tp)
        // Two fake cards, enough to read as "a screen in this mode".
        p.color = if (night) 0xFF3A3A3C.toInt() else Color.WHITE
        c.drawRoundRect(x - w / 2 + dp(8f), top + dp(50f), x + w / 2 - dp(8f), top + dp(72f), dp(5f), dp(5f), p)
        c.drawRoundRect(x - w / 2 + dp(8f), top + dp(78f), x + w / 2 - dp(8f), top + dp(100f), dp(5f), dp(5f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1f)
        p.color = Theme.separator
        c.drawRoundRect(x - w / 2, top, x + w / 2, top + h, dp(9f), dp(9f), p)

        tp.typeface = Typeface.DEFAULT
        tp.textSize = sp(15f)
        tp.color = Theme.text
        c.drawText(if (night) "深色" else "浅色", x, top + h + dp(24f), tp)
        val ry = top + h + dp(48f)
        if (dark == night) {
            p.style = Paint.Style.FILL
            p.color = Theme.tint
            c.drawCircle(x, ry, dp(11f), p)
            G.check(c, x, ry, Color.WHITE, 0.72f)
        } else {
            p.strokeWidth = dp(1.5f)
            p.color = Theme.chevron
            c.drawCircle(x, ry, dp(10.25f), p)
        }
    }

    override fun onDraw(c: Canvas) { thumb(c, false); thumb(c, true) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.x - width / 2f) < dp(140f)) {
            val night = e.x > width / 2f
            if (dark != night) { dark = night; onPick(night) }
        }
        return true
    }
}

/** Battery pane hero: a battery pictogram, the percentage, and one line of state. */
class BatteryHeader(c: Context) : View(c), Sep {
    var level = -1
    var state = ""
    var charging = false
    var saver = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.semibold }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    override val sepInset get() = dp(16f)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(128))

    override fun onDraw(c: Canvas) {
        val s = if (level < 0) "--" else "$level%"
        tp.textSize = sp(44f)
        tp.color = Theme.text
        val bw = dp(58f)
        val bh = dp(27f)
        val total = bw + dp(18f) + tp.measureText(s)
        val x = (width - total) / 2
        val cy = dp(52f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.6f)
        p.color = Theme.secondary
        c.drawRoundRect(x, cy - bh / 2, x + bw, cy + bh / 2, dp(7f), dp(7f), p)
        p.style = Paint.Style.FILL
        c.drawRoundRect(x + bw + dp(2.5f), cy - dp(5f), x + bw + dp(5.5f), cy + dp(5f), dp(1.5f), dp(1.5f), p)
        if (level >= 0) {
            p.color = when { saver -> 0xFFFFCC00.toInt(); charging || level > 20 -> Theme.green; else -> Theme.red }
            val g = dp(3.2f)
            val fw = max(dp(6f), (bw - 2 * g) * level / 100f)
            c.drawRoundRect(x + g, cy - bh / 2 + g, x + g + fw, cy + bh / 2 - g, dp(4f), dp(4f), p)
        }
        c.drawText(s, x + bw + dp(18f), base(tp, cy), tp)
        sub.textSize = sp(15f)
        sub.color = Theme.secondary
        c.drawText(state, width / 2f, dp(104f), sub)
    }
}

/** Storage usage: one rounded bar cut into coloured categories, with a wrapping legend underneath. */
class StorageBar(c: Context) : View(c), Sep {
    class Part(val label: String, val color: Int, val bytes: Long)

    var parts: List<Part> = emptyList()
    var total = 0L
    var caption = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f) }
    private val lp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f) }
    override val sepInset get() = dp(16f)

    fun set(parts: List<Part>, total: Long, caption: String) {
        this.parts = parts; this.total = total; this.caption = caption
        requestLayout(); invalidate()
    }

    /** Lays the legend out left to right, wrapping; draws it when [c] is given, and returns the bottom edge. */
    private fun legend(w: Int, c: Canvas?): Float {
        var x = dp(16f)
        var y = dp(86f)
        for (part in parts) {
            val iw = dp(14f) + lp.measureText(part.label) + dp(14f)
            if (x + iw > w - dp(8f) && x > dp(16f)) { x = dp(16f); y += dp(22f) }
            if (c != null) {
                p.color = part.color
                c.drawCircle(x + dp(4f), y - dp(4f), dp(4f), p)
                lp.color = Theme.secondary
                c.drawText(part.label, x + dp(14f), y, lp)
            }
            x += iw
        }
        return y + dp(14f)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, if (parts.isEmpty()) dp(78) else legend(width, null).toInt())
    }

    override fun onDraw(c: Canvas) {
        tp.color = Theme.text
        c.drawText(caption, dp(16f), dp(30f), tp)
        val l = dp(16f)
        val r = width - dp(16f)
        val top = dp(44f)
        val bot = dp(64f)
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(l, top, r, bot, dp(5f), dp(5f), android.graphics.Path.Direction.CW) })
        c.drawColor(Theme.track)
        var x = l
        if (total > 0) for (part in parts) {
            val w = (r - l) * part.bytes / total.toFloat()
            p.color = part.color
            c.drawRect(x, top, min(r, x + w), bot, p)
            // A sliver of card colour between segments, as on iOS.
            p.color = Theme.card
            c.drawRect(x + w - 1f, top, x + w, bot, p)
            x += w
        }
        c.restore()
        legend(width, c)
    }
}
