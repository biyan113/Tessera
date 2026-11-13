package app.tessera.panel

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class ControlActivity : SheetActivity() {
    private lateinit var view: ControlView
    private var controller: MediaController? = null

    private val mediaCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) = readMedia()
        override fun onMetadataChanged(m: MediaMetadata?) = readMedia()
    }

    private val states = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = view.load()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        view = ControlView(this)
        setContentView(view)
    }

    override fun onProgress(p: Float) = view.progress(p)

    override fun onResume() {
        super.onResume()
        registerReceiver(states, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }, RECEIVER_EXPORTED)
        view.load()
        try {
            val all = getSystemService(MediaSessionManager::class.java)!!
                .getActiveSessions(ComponentName(this, NoticeService::class.java))
            controller = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
            controller?.registerCallback(mediaCb)
        } catch (_: SecurityException) {
            controller = null
        }
        readMedia()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(states)
        controller?.unregisterCallback(mediaCb)
        controller = null
    }

    private fun readMedia() {
        val c = controller
        val m = c?.metadata
        view.media(
            c != null,
            m?.getString(MediaMetadata.METADATA_KEY_TITLE),
            m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            c?.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    fun transport(which: Int) {
        val t = controller?.transportControls ?: return
        when (which) {
            0 -> t.skipToPrevious()
            1 -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            2 -> t.skipToNext()
        }
    }

    /** Opens something from a tile: the sheet goes away first so the new screen is not launched underneath it. */
    fun leaveFor(i: Intent) = close {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

/**
 * The whole control centre on one canvas: a four-column grid of tiles pinned to the top right,
 * the corner it is pulled from.
 */
@SuppressLint("ViewConstructor")
class ControlView(private val act: ControlActivity) : View(act) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val u = dp(74f)
    private val gap = dp(14f)
    private val radius = dp(24f)
    private var left = 0f
    private var top = 0f
    private var p = 0f

    private val audio = act.getSystemService(AudioManager::class.java)!!
    private val wifiM = act.applicationContext.getSystemService(WifiManager::class.java)!!
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()
    private val icons = HashMap<Int, Drawable>()

    private var airplane = false
    private var wifi = false
    private var bt = false
    private var loc = false
    private var rotLock = false
    private var silent = false
    private var dnd = false
    private var dark = false
    private var recording = false
    private var bright = 0.5f
    private var volume = 0.5f
    private var hasMedia = false
    private var title: String? = null
    private var artist: String? = null
    private var playing = false

    private var pressed = -1
    private var sub = 0
    private var slide = -1
    private var slideFrom = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    init {
        setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top = b.top + dp(12f)
            left = width - b.right - dp(20f) - (u * 4 + gap * 3)
            invalidate()
            ins
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = requestApplyInsets()

    fun progress(v: Float) { p = v; invalidate() }

    fun media(has: Boolean, t: String?, a: String?, play: Boolean) {
        hasMedia = has; title = t; artist = a; playing = play
        invalidate()
    }

    /** Reads every state fresh; cheap enough to run after each tap and on each system broadcast. */
    fun load() {
        val cr = act.contentResolver
        airplane = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        wifi = wifiM.isWifiEnabled
        bt = try { act.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: Exception) { false }
        loc = act.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        rotLock = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
        silent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        dnd = Settings.Global.getInt(cr, "zen_mode", 0) != 0
        dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES ||
            act.getSystemService(UiModeManager::class.java)?.nightMode == UiModeManager.MODE_NIGHT_YES
        if (slide != VOLUME) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        // ZUI's recorder lives in SystemUI; while it records, SystemUI holds a screen-capture projection.
        Root.run("dumpsys media_projection") { s ->
            val now = s != null && s.contains("com.android.systemui") && s.contains("TYPE_SCREEN_CAPTURE")
            if (now != recording) { recording = now; invalidate() }
        }
        if (slide != BRIGHT) Root.run("cmd display get-brightness 0") { s ->
            s?.trim()?.toFloatOrNull()?.let { bright = sqrt(it.coerceIn(0f, 1f)); invalidate() }
        }
        invalidate()
    }

    // ---- layout ----

    private fun cell(cx: Int, cy: Int, sx: Int, sy: Int, out: RectF) {
        val x = left + cx * (u + gap)
        val y = top + cy * (u + gap)
        out.set(x, y, x + sx * u + (sx - 1) * gap, y + sy * u + (sy - 1) * gap)
    }

    private fun rectOf(id: Int, out: RectF) = when (id) {
        CONN -> cell(0, 0, 2, 2, out)
        MEDIA -> cell(2, 0, 2, 2, out)
        ROTATE -> cell(0, 2, 1, 1, out)
        SILENT -> cell(1, 2, 1, 1, out)
        FOCUS -> cell(0, 3, 2, 1, out)
        BRIGHT -> cell(2, 2, 1, 2, out)
        VOLUME -> cell(3, 2, 1, 2, out)
        DARK -> cell(0, 4, 1, 1, out)
        SHOT -> cell(1, 4, 1, 1, out)
        RECORD -> cell(2, 4, 1, 1, out)
        CAMERA -> cell(3, 4, 1, 1, out)
        else -> cell(0, 5, 1, 1, out)
    }

    private fun tileAt(x: Float, y: Float): Int {
        for (id in 0 until COUNT) { rectOf(id, r); if (r.contains(x, y)) return id }
        return -1
    }

    // ---- drawing ----

    private fun icon(res: Int, cx: Float, cy: Float, size: Float, tint: Int) {
        val dr = icons.getOrPut(res) { act.getDrawable(res)!!.mutate() }
        dr.setTint(tint)
        val h = (size / 2).toInt()
        dr.setBounds(cx.toInt() - h, cy.toInt() - h, cx.toInt() + h, cy.toInt() + h)
        dr.draw(canvas!!)
    }

    private var canvas: Canvas? = null

    private fun tile(c: Canvas, id: Int, on: Boolean) {
        rectOf(id, r)
        if (id == pressed && slide < 0 && id != CONN && id != MEDIA) {
            val s = 0.94f
            c.scale(s, s, r.centerX(), r.centerY())
        }
        fill.color = if (on) 0xF5FFFFFF.toInt() else 0x991C1C20.toInt()
        c.drawRoundRect(r, radius, radius, fill)
        if (!on) {
            line.color = 0x1FFFFFFF
            line.strokeWidth = 1.2f
            c.drawRoundRect(r, radius, radius, line)
        }
    }

    override fun onDraw(c: Canvas) {
        canvas = c
        c.drawColor(((0x61 * p).toInt() shl 24))
        val all = c.save()
        val w = u * 4 + gap * 3
        // Grows out of the corner it was pulled from.
        val s = 0.86f + 0.14f * p
        c.scale(s, s, left + w, top)
        c.saveLayerAlpha(left - gap, top - gap, left + w + gap, top + 6 * (u + gap), (255 * p).toInt())

        // connectivity
        var n = c.save()
        tile(c, CONN, false)
        val on = booleanArrayOf(airplane, wifi, bt, loc)
        val res = intArrayOf(R.drawable.ic_airplane, R.drawable.ic_wifi, R.drawable.ic_bluetooth, R.drawable.ic_location)
        val col = intArrayOf(0xFFFF9F0A.toInt(), 0xFF0A84FF.toInt(), 0xFF0A84FF.toInt(), 0xFF0A84FF.toInt())
        for (i in 0 until 4) {
            val cx = r.left + r.width() * (if (i % 2 == 0) 0.27f else 0.73f)
            val cy = r.top + r.height() * (if (i < 2) 0.27f else 0.73f)
            fill.color = if (on[i]) col[i] else 0x30FFFFFF
            val rad = dp(27f) * if (pressed == CONN && sub == i) 0.92f else 1f
            c.drawCircle(cx, cy, rad, fill)
            icon(res[i], cx, cy, dp(26f), -1)
        }
        c.restoreToCount(n)

        // now playing
        n = c.save()
        tile(c, MEDIA, false)
        val pad = dp(16f)
        text.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        text.textSize = dp(15f)
        text.color = if (hasMedia) -1 else 0x99FFFFFF.toInt()
        val head = if (hasMedia) (title ?: "正在播放") else "未在播放"
        c.drawText(TextUtils.ellipsize(head, text, r.width() - pad * 2, TextUtils.TruncateAt.END).toString(), r.left + pad, r.top + dp(34f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(13f)
        text.color = 0x99FFFFFF.toInt()
        artist?.let { c.drawText(TextUtils.ellipsize(it, text, r.width() - pad * 2, TextUtils.TruncateAt.END).toString(), r.left + pad, r.top + dp(54f), text) }
        val cy = r.bottom - dp(40f)
        val dim = if (hasMedia) -1 else 0x59FFFFFF
        val keys = intArrayOf(R.drawable.ic_prev, if (playing) R.drawable.ic_pause else R.drawable.ic_play, R.drawable.ic_next)
        for (i in 0 until 3) {
            val grow = if (pressed == MEDIA && sub == i) 0.86f else 1f
            icon(keys[i], r.left + r.width() * (0.2f + 0.3f * i), cy, dp(if (i == 1) 36f else 28f) * grow, dim)
        }
        c.restoreToCount(n)

        // rotation lock: a padlock inside a turning arrow
        n = c.save()
        tile(c, ROTATE, rotLock)
        val ink = if (rotLock) 0xFFFF3B30.toInt() else -1
        icon(R.drawable.ic_lock, r.centerX(), r.centerY() + dp(1f), dp(17f), ink)
        line.color = ink
        line.strokeWidth = dp(2.2f)
        val ar = dp(16f)
        r.inset(r.width() / 2 - ar, r.height() / 2 - ar)
        c.drawArc(r, -60f, 300f, false, line)
        val ax = r.centerX() + ar * cos(Math.toRadians(-60.0)).toFloat()
        val ay = r.centerY() + ar * sin(Math.toRadians(-60.0)).toFloat()
        c.drawLine(ax, ay, ax - dp(6.5f), ay - dp(0.5f), line)
        c.drawLine(ax, ay, ax + dp(1.5f), ay - dp(6.5f), line)
        c.restoreToCount(n)

        // silent
        n = c.save()
        tile(c, SILENT, silent)
        val bell = if (silent) 0xFFFF3B30.toInt() else -1
        icon(R.drawable.ic_bell, r.centerX(), r.centerY(), dp(30f), bell)
        if (silent) {
            line.color = bell
            line.strokeWidth = dp(2.4f)
            c.drawLine(r.centerX() - dp(12f), r.centerY() - dp(12f), r.centerX() + dp(12f), r.centerY() + dp(12f), line)
        }
        c.restoreToCount(n)

        // focus
        n = c.save()
        tile(c, FOCUS, dnd)
        fill.color = if (dnd) 0xFF5E5CE6.toInt() else 0x30FFFFFF
        c.drawCircle(r.left + dp(37f), r.centerY(), dp(21f), fill)
        icon(R.drawable.ic_moon, r.left + dp(37f), r.centerY(), dp(22f), -1)
        text.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        text.textSize = dp(15f)
        text.color = if (dnd) 0xFF111114.toInt() else -1
        c.drawText(if (dnd) "勿扰模式" else "专注模式", r.left + dp(68f), r.centerY() + dp(if (dnd) -2f else 5.5f), text)
        if (dnd) {
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(12.5f)
            text.color = 0x99111114.toInt()
            c.drawText("开", r.left + dp(68f), r.centerY() + dp(15f), text)
        }
        c.restoreToCount(n)

        slider(c, BRIGHT, bright)
        slider(c, VOLUME, volume)

        // shortcuts
        n = c.save()
        tile(c, DARK, dark)
        icon(R.drawable.ic_contrast, r.centerX(), r.centerY(), dp(30f), if (dark) 0xFF111114.toInt() else -1)
        c.restoreToCount(n)

        n = c.save()
        tile(c, SHOT, false)
        line.color = -1
        line.strokeWidth = dp(2.3f)
        val q = dp(12f)
        val k = dp(6f)
        for (i in 0 until 4) {
            val sx = if (i % 2 == 0) -1 else 1
            val sy = if (i < 2) -1 else 1
            val x = r.centerX() + sx * q
            val y = r.centerY() + sy * q
            c.drawLine(x, y, x - sx * k, y, line)
            c.drawLine(x, y, x, y - sy * k, line)
        }
        fill.color = -1
        c.drawCircle(r.centerX(), r.centerY(), dp(3.2f), fill)
        c.restoreToCount(n)

        // screen recording: a dot inside a ring, red while recording
        n = c.save()
        tile(c, RECORD, false)
        line.color = -1
        line.strokeWidth = dp(2.3f)
        c.drawCircle(r.centerX(), r.centerY(), dp(13f), line)
        fill.color = if (recording) 0xFFFF3B30.toInt() else -1
        c.drawCircle(r.centerX(), r.centerY(), dp(8.5f), fill)
        c.restoreToCount(n)

        n = c.save()
        tile(c, CAMERA, false)
        icon(R.drawable.ic_camera, r.centerX(), r.centerY(), dp(30f), -1)
        c.restoreToCount(n)

        n = c.save()
        tile(c, SETTINGS, false)
        icon(R.drawable.ic_gear, r.centerX(), r.centerY(), dp(30f), -1)
        c.restoreToCount(n)

        c.restoreToCount(all)
    }

    private fun slider(c: Canvas, id: Int, v: Float) {
        val n = c.save()
        rectOf(id, r)
        if (slide == id) c.scale(1.04f, 1.04f, r.centerX(), r.centerY())
        clip.rewind()
        clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.clipPath(clip)
        fill.color = 0x991C1C20.toInt()
        c.drawRect(r, fill)
        fill.color = 0xF5FFFFFF.toInt()
        c.drawRect(r.left, r.bottom - r.height() * v, r.right, r.bottom, fill)
        val gx = r.centerX()
        val gy = r.bottom - dp(36f)
        val ink = if (v > 0.26f) 0xFF6E6E73.toInt() else -1
        if (id == VOLUME) icon(R.drawable.ic_speaker, gx, gy, dp(28f), ink)
        else {
            fill.color = ink
            c.drawCircle(gx, gy, dp(5.6f), fill)
            line.color = ink
            line.strokeWidth = dp(2.2f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                c.drawLine(gx + ca * dp(9.5f), gy + sa * dp(9.5f), gx + ca * dp(12.5f), gy + sa * dp(12.5f), line)
            }
        }
        c.restoreToCount(n)
    }

    // ---- touch ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                pressed = tileAt(e.x, e.y)
                slide = -1
                if (pressed == BRIGHT || pressed == VOLUME) {
                    slide = pressed
                    slideFrom = if (slide == BRIGHT) bright else volume
                } else if (pressed == CONN || pressed == MEDIA) {
                    rectOf(pressed, r)
                    sub = if (pressed == CONN) (if (e.x > r.centerX()) 1 else 0) + (if (e.y > r.centerY()) 2 else 0)
                    else if (e.y < r.centerY()) -1 else ((e.x - r.left) / (r.width() / 3)).toInt().coerceIn(0, 2)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > dp(10f) || abs(e.y - downY) > dp(10f)) moved = true
                if (slide >= 0) {
                    rectOf(slide, r)
                    val v = (slideFrom + (downY - e.y) / r.height()).coerceIn(0f, 1f)
                    if (slide == BRIGHT) {
                        bright = v
                        Root.runLatest("cmd display set-brightness %.4f".format(v.pow(2)))
                    } else {
                        volume = v
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max + 0.5f).toInt(), 0) } catch (_: Exception) {}
                    }
                    invalidate()
                } else if (pressed < 0 && downY - e.y > dp(48f)) {
                    act.close()
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = pressed
                val wasSlide = slide >= 0
                pressed = -1
                slide = -1
                invalidate()
                if (id < 0) { if (!moved) act.close() }
                else if (!wasSlide && !moved && tileAt(e.x, e.y) == id) tap(id)
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; slide = -1; invalidate() }
        }
        return true
    }

    private fun onOff(b: Boolean) = if (b) "enable" else "disable"

    /** The tile flips at once; the real state is read back shortly after, so a refused change snaps back. */
    private fun tap(id: Int) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (id) {
            CONN -> when (sub) {
                0 -> { airplane = !airplane; Root.run("cmd connectivity airplane-mode " + onOff(airplane)) }
                1 -> { wifi = !wifi; Root.run("svc wifi " + onOff(wifi)) }
                2 -> { bt = !bt; Root.run("svc bluetooth " + onOff(bt)) }
                else -> { loc = !loc; Root.run("cmd location set-location-enabled $loc") }
            }
            MEDIA -> if (sub >= 0) act.transport(sub)
            ROTATE -> { rotLock = !rotLock; Root.run("cmd window user-rotation " + if (rotLock) "lock" else "free") }
            SILENT -> { silent = !silent; Root.run("cmd audio set-ringer-mode " + if (silent) "SILENT" else "NORMAL") }
            FOCUS -> { dnd = !dnd; Root.run("cmd notification set_dnd " + if (dnd) "on" else "off") }
            DARK -> { dark = !dark; Root.run("cmd uimode night " + if (dark) "yes" else "no") }
            SHOT -> act.close { postDelayed({ Root.run("input keyevent 120") }, 250) }
            // ZUI counts down 3 s before recording, so the sheet is gone by then. A stop sent during
            // that countdown is ignored by ZUI; the floating bar it shows can stop it as well.
            RECORD -> if (recording) {
                recording = false
                Root.run("am broadcast -a force_stop_screenrecorder.ACTION")
            } else act.close {
                Root.run("am startservice -n com.android.systemui/com.zui.screenrecorder.ScreenRecorderService")
            }
            CAMERA -> act.leaveFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            SETTINGS -> act.leaveFor(
                act.packageManager.getLaunchIntentForPackage("app.tessera.settings") ?: Intent(Settings.ACTION_SETTINGS)
            )
        }
        invalidate()
        postDelayed({ load() }, 900)
    }

    companion object {
        const val CONN = 0
        const val MEDIA = 1
        const val ROTATE = 2
        const val SILENT = 3
        const val FOCUS = 4
        const val BRIGHT = 5
        const val VOLUME = 6
        const val DARK = 7
        const val SHOT = 8
        const val CAMERA = 9
        const val SETTINGS = 10
        const val RECORD = 11
        const val COUNT = 12
    }
}
