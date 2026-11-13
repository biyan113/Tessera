package app.tessera.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A sidebar entry. [keys] are extra search words, so "亮度" finds 显示与亮度.
 * No [page] means the row does something other than fill the detail pane.
 */
class Section(val id: String, val title: String, val color: Long, val glyph: Int, val keys: String = "", val page: ((SettingsActivity) -> Page)? = null)

private const val ORANGE = 0xFFFF9500
private const val BLUE = 0xFF007AFF
private const val RED = 0xFFFF3B30
private const val PINK = 0xFFFF2D55
private const val INDIGO = 0xFF5856D6
private const val GREY = 0xFF8E8E93
private const val CYAN = 0xFF32ADE6
private const val GREEN = 0xFF34C759

/** iPadOS order, minus what a Wi-Fi-only Android tablet has no use for. */
val sections: List<List<Section>> = listOf(
    listOf(
        Section("airplane", "飞行模式", ORANGE, R.drawable.ic_airplane),
        Section("wifi", "无线局域网", BLUE, R.drawable.ic_wifi, "wifi wlan 网络", ::wifiPage),
        Section("bluetooth", "蓝牙", BLUE, R.drawable.ic_bluetooth, "bluetooth 配对", ::bluetoothPage),
    ),
    listOf(
        Section("notifications", "通知", RED, R.drawable.ic_bell, "提醒 横幅 朗读 语音播报", ::notificationsPage),
        Section("sound", "声音", PINK, R.drawable.ic_sound, "音量 静音 铃声 触感 按键音 通知声音 提示音", ::soundPage),
        Section("focus", "专注模式", INDIGO, R.drawable.ic_moon, "勿扰", ::focusPage),
    ),
    listOf(
        Section("general", "通用", GREY, R.drawable.ic_gear, "关于本机 储存空间 日期 时间 键盘 语言 地区 软件更新 开发者 重新启动 关机", ::generalPage),
        Section("display", "显示与亮度", BLUE, R.drawable.ic_sun, "深色 浅色 外观 自动锁定 字体大小 护眼", ::displayPage),
        Section("home", "主屏幕与多任务", INDIGO, R.drawable.ic_home, "桌面 手势 导航 画中画", ::homePage),
        Section("accessibility", "辅助功能", BLUE, R.drawable.ic_access, "无障碍 字幕", ::accessibilityPage),
        Section("wallpaper", "墙纸", CYAN, R.drawable.ic_wallpaper, "壁纸 屏幕保护", ::wallpaperPage),
        Section("pen", "手写笔", GREY, R.drawable.ic_pencil, "触控笔 pencil stylus", ::penPage),
    ),
    listOf(
        Section("security", "生物识别与密码", GREEN, R.drawable.ic_lock, "指纹 面容 锁屏 密码", ::securityPage),
        Section("battery", "电池", GREEN, R.drawable.ic_battery, "电量 低电量 充电保护 省电", ::batteryPage),
        Section("privacy", "隐私与安全性", BLUE, R.drawable.ic_hand, "权限 定位", ::privacyPage),
    ),
    listOf(Section("apps", "App", BLUE, R.drawable.ic_apps, "应用 程序", ::appsPage)),
    listOf(Section("system", "系统设置", GREY, R.drawable.ic_tune, "原生 android zui")),
)

/** Left column: title, search, the device card, then the grouped sections. All of it scrolls together. */
@SuppressLint("ViewConstructor")
class Sidebar(private val act: SettingsActivity) : ScrollView(act), Themed {
    private val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val rows = LinkedHashMap<Section, Row>()
    private val cards = ArrayList<Card>()
    private val binds = ArrayList<() -> Unit>()
    private val heading = TextView(act).apply {
        text = "设置"
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(34f))
        includeFontPadding = false
    }
    private val device: Card

    init {
        setWillNotDraw(false)
        isVerticalScrollBarEnabled = false
        // Takes initial focus, so the search field does not open with a blinking cursor.
        column.isFocusableInTouchMode = true
        addView(column)
        fun lp(top: Int, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply { setMargins(dp(16), dp(top), dp(16), 0) }

        column.addView(heading, lp(10))
        column.addView(SearchField(act) { filter(it) }, lp(8, dp(36)))

        device = Card(act, true)
        device.addView(DeviceCard(act, "Legion Y700", osLine()) {
            act.select("general")
            act.detail.push(aboutPage(act))
        })
        column.addView(device, lp(20))

        for (group in sections) {
            val card = Card(act, true)
            for (s in group) card.addView(make(s))
            cards += card
            column.addView(card, lp(if (cards.size == 1) 20 else 28))
        }
        restyle()
        inset()
    }

    /** "Android 16 · ZUXOS 1.x" when the build string reveals the skin version, else the raw build id. */
    private fun osLine(): String {
        val skin = Regex("(ZUXOS|ZUI)[_ ]?(\\d+(?:\\.\\d+)*)", RegexOption.IGNORE_CASE).find(Build.DISPLAY)
        return "Android ${Build.VERSION.RELEASE} · " + (skin?.let { "${it.groupValues[1].uppercase()} ${it.groupValues[2]}" } ?: Build.DISPLAY)
    }

    private fun make(s: Section): Row {
        val r = Row(act, s.title)
        r.tile = s.color.toInt()
        r.glyph = act.getDrawable(s.glyph)
        rows[s] = r
        when (s.id) {
            "airplane" -> binds += bindToggle(r, {
                try { Settings.Global.getInt(act.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 } catch (_: Exception) { null }
            }) { v, done -> Root.run("cmd connectivity airplane-mode ${if (v) "enable" else "disable"}", cb = done) }
            "system" -> {
                r.acc = Acc.CHEVRON
                r.onTap = { act.open(act.packageManager.getLaunchIntentForPackage("com.android.settings") ?: Intent(Settings.ACTION_SETTINGS)) }
            }
            else -> {
                r.acc = Acc.CHEVRON
                r.onTap = { act.select(s.id) }
            }
        }
        if (s.id == "wifi") binds += { r.value = Wifi.label() }
        if (s.id == "bluetooth") binds += { r.value = when (Bt.enabled) { true -> "打开"; false -> "关闭"; else -> null } }
        return r
    }

    fun refresh() = binds.forEach { it() }

    fun mark(id: String) = rows.forEach { (s, r) -> r.selectedRow = s.id == id }

    /** Live filter: non-matching rows collapse, and a group with nothing left disappears whole. */
    private fun filter(q: String) {
        device.visibility = if (q.isEmpty()) VISIBLE else GONE
        for ((s, r) in rows) {
            r.visibility = if (q.isEmpty() || s.title.contains(q, true) || s.keys.contains(q, true)) VISIBLE else GONE
        }
        for (c in cards) {
            c.visibility = if ((0 until c.childCount).any { c.getChildAt(it).visibility == VISIBLE }) VISIBLE else GONE
            c.invalidate()
        }
    }

    fun inset() = column.setPadding(0, 0, 0, Ui.bottom + dp(28))

    override fun restyle() = heading.setTextColor(Theme.text)

    override fun onDraw(c: Canvas) = c.drawColor(Theme.sidebar)
}
