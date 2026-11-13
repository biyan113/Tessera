package app.tessera.panel

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

/** Notification centre: the clock on top, then every waiting notification in one centred column. */
class NoticeActivity : SheetActivity() {
    private var rebindAt = -5000L
    private lateinit var root: NoticeRoot
    private lateinit var column: LinearLayout
    private lateinit var clock: ClockView
    private val icons = HashMap<String, Bitmap>()
    override val blur = 240f

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = clock.invalidate()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val d = resources.displayMetrics.density
        root = NoticeRoot(this)
        clock = ClockView(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val w = min((560 * d).toInt(), resources.displayMetrics.widthPixels - (32 * d).toInt())
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock, LinearLayout.LayoutParams(-1, (210 * d).toInt()))
            addView(column, LinearLayout.LayoutParams(-1, -2))
            // Tapping the empty space under the list dismisses, like tapping outside a sheet.
            addView(View(context).apply { setOnClickListener { close() } }, LinearLayout.LayoutParams(-1, (420 * d).toInt()))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(inner, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, FrameLayout.LayoutParams(w, -1, Gravity.CENTER_HORIZONTAL))
        root.setOnApplyWindowInsetsListener { _, ins ->
            scroll.setPadding(0, ins.getInsets(WindowInsets.Type.systemBars()).top, 0, 0)
            ins
        }
        setContentView(root)
    }

    override fun onProgress(p: Float) {
        root.dim = p
        root.getChildAt(0).alpha = p
        // Drops in from above, the direction it was pulled.
        root.getChildAt(0).translationY = -(1 - p) * resources.displayMetrics.heightPixels * 0.18f
        root.invalidate()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK), RECEIVER_EXPORTED)
        NoticeService.changed = { runOnUiThread { rebuild() } }
        // ZUI's autostart manager refuses to start this process for the system, so after a reboot or a
        // kill the listener stays unbound, and requestRebind() only undoes a requestUnbind(). Now that
        // the process is up, withdrawing and granting the access again through tesserad makes the
        // system bind it.
        if (NoticeService.live == null && android.os.SystemClock.elapsedRealtime() - rebindAt > 5000) {
            rebindAt = android.os.SystemClock.elapsedRealtime()
            val c = "$packageName/${NoticeService::class.java.name}"
            Root.run("cmd notification disallow_listener $c; cmd notification allow_listener $c")
        }
        rebuild()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(tick)
        NoticeService.changed = null
    }

    private fun dark() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun rebuild() {
        val d = resources.displayMetrics.density
        column.removeAllViews()
        val svc = NoticeService.live
        val list = try {
            svc?.activeNotifications?.filter(::worth)?.sortedByDescending { it.postTime } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        column.addView(HeaderView(this, list.isNotEmpty(), if (svc == null) "正在读取通知" else "没有较早的通知") {
            try { svc?.cancelAllNotifications() } catch (_: Exception) {}
        }, LinearLayout.LayoutParams(-1, (if (list.isEmpty()) 90 * d else 48 * d).toInt()))
        for (n in list) {
            column.addView(CardView(this, n, dark(), iconOf(n.packageName)), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() })
        }
    }

    /** Ongoing items (players, VPN, downloads) and bare group headers are status, not messages. */
    private fun worth(n: StatusBarNotification): Boolean {
        if (n.isOngoing || n.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val x = n.notification.extras
        return !x.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() || !x.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    private fun iconOf(pkg: String): Bitmap? = icons[pkg] ?: try {
        val s = (38 * resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val dr = packageManager.getApplicationIcon(pkg)
        dr.setBounds(0, 0, s, s)
        val cv = Canvas(b)
        // Old square artwork gets the same rounded corners as everything else.
        cv.clipPath(android.graphics.Path().apply { addRoundRect(0f, 0f, s.toFloat(), s.toFloat(), s * 0.2237f, s * 0.2237f, android.graphics.Path.Direction.CW) })
        dr.draw(cv)
        icons[pkg] = b
        b
    } catch (_: Exception) {
        null
    }

    fun open(n: StatusBarNotification) {
        val pi = n.notification.contentIntent
        close {
            try {
                // Since Android 14 the sender must opt in before a PendingIntent may start an activity.
                val o = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi?.send(this, 0, null, null, null, null, o.toBundle())
                if (n.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) NoticeService.live?.cancelNotification(n.key)
            } catch (_: Exception) {
            }
        }
    }

    fun dismiss(n: StatusBarNotification) {
        try { NoticeService.live?.cancelNotification(n.key) } catch (_: Exception) {}
    }
}

/** Dims the blurred screen behind, and closes on a swipe up from the bottom edge. */
class NoticeRoot(private val act: NoticeActivity) : FrameLayout(act) {
    var dim = 0f
    private var fromEdge = false
    private var downY = 0f

    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) = c.drawColor(((0x66 * dim).toInt() shl 24))

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; fromEdge = e.y > height - 70 * d }
            MotionEvent.ACTION_MOVE -> if (fromEdge && downY - e.y > 36 * d) { fromEdge = false; act.close(); return true }
        }
        return super.dispatchTouchEvent(e)
    }
}

class ClockView(c: Context) : View(c) {
    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; textAlign = Paint.Align.CENTER }
    private val week = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cal = Calendar.getInstance()
        p.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        p.textSize = 21 * d
        p.alpha = 235
        c.drawText("${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 ${week[cal.get(Calendar.DAY_OF_WEEK) - 1]}", width / 2f, 52 * d, p)
        p.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        p.textSize = 104 * d
        p.alpha = 255
        c.drawText("%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)), width / 2f, 152 * d, p)
    }
}

@SuppressLint("ViewConstructor")
class HeaderView(c: Context, private val any: Boolean, private val empty: String, private val clear: () -> Unit) : View(c) {
    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        p.style = Paint.Style.FILL
        if (!any) {
            p.color = 0xB3FFFFFF.toInt()
            p.textSize = 17 * d
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT
            c.drawText(empty, width / 2f, 50 * d, p)
            return
        }
        p.color = -1
        p.textSize = 20 * d
        p.textAlign = Paint.Align.LEFT
        p.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        c.drawText("通知中心", 8 * d, 28 * d, p)
        val cx = width - 20 * d
        val cy = 21 * d
        p.color = 0x47FFFFFF
        c.drawCircle(cx, cy, 15 * d, p)
        p.color = -1
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx - 5 * d, cy - 5 * d, cx + 5 * d, cy + 5 * d, p)
        c.drawLine(cx - 5 * d, cy + 5 * d, cx + 5 * d, cy - 5 * d, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!any) return false
        if (e.actionMasked == MotionEvent.ACTION_UP && e.x > width - 52 * d) clear()
        return e.x > width - 52 * d
    }
}

/** One notification, drawn as a single frosted card: app icon, bold title, body, age. */
@SuppressLint("ViewConstructor")
class CardView(private val act: NoticeActivity, private val n: StatusBarNotification, private val dark: Boolean, private val icon: Bitmap?) : View(act) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val title: String
    private val text: CharSequence
    private var layout: StaticLayout? = null
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        val x = n.notification.extras
        title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: try { act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(n.packageName, 0)).toString() } catch (_: Exception) { "" }
        text = x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        body.textSize = 15 * d
        body.color = if (dark) 0xF0FFFFFF.toInt() else 0xF0111114.toInt()
    }

    private fun age(): String {
        val m = (System.currentTimeMillis() - n.postTime) / 60000
        return when {
            m < 1 -> "现在"
            m < 60 -> "${m}分钟前"
            m < 1440 -> "${m / 60}小时前"
            else -> "${m / 1440}天前"
        }
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val tw = (w - 78 * d).toInt().coerceAtLeast(1)
        layout = if (text.isEmpty()) null else StaticLayout.Builder.obtain(text, 0, text.length, body, tw)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(2 * d, 1f).build()
        val h = 14 * d + 20 * d + (layout?.height ?: 0) + 14 * d
        setMeasuredDimension(w, maxOf(h, 66 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        fill.color = if (dark) 0xB82A2A2E.toInt() else 0xD1F6F6F8.toInt()
        c.drawRoundRect(r, 22 * d, 22 * d, fill)
        icon?.let { c.drawBitmap(it, 14 * d, 14 * d, bmp) }
        val x = 64 * d
        tp.typeface = Typeface.DEFAULT
        tp.textSize = 13 * d
        tp.color = if (dark) 0x99FFFFFF.toInt() else 0x993C3C43.toInt()
        tp.textAlign = Paint.Align.RIGHT
        val age = age()
        c.drawText(age, width - 16 * d, 29 * d, tp)
        val room = width - x - 24 * d - tp.measureText(age)
        tp.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        tp.textSize = 15 * d
        tp.color = if (dark) -1 else 0xFF000000.toInt()
        tp.textAlign = Paint.Align.LEFT
        c.drawText(TextUtils.ellipsize(title, tp, room, TextUtils.TruncateAt.END).toString(), x, 30 * d, tp)
        layout?.let {
            c.save()
            c.translate(x, 37 * d)
            it.draw(c)
            c.restore()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                if (!dragging && abs(dx) > slop && abs(dx) > abs(e.rawY - downY) && n.isClearable) {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) { translationX = dx; alpha = 1 - min(1f, abs(dx) / width) * 0.7f }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    if (abs(translationX) > width * 0.3f) {
                        animate().translationX(if (translationX > 0) width * 1.2f else -width * 1.2f).alpha(0f).setDuration(160)
                            .withEndAction { act.dismiss(n); visibility = GONE }.start()
                    } else animate().translationX(0f).alpha(1f).setDuration(200).start()
                } else if (abs(e.rawX - downX) < slop && abs(e.rawY - downY) < slop) act.open(n)
            }
            MotionEvent.ACTION_CANCEL -> animate().translationX(0f).alpha(1f).setDuration(200).start()
        }
        return true
    }
}
