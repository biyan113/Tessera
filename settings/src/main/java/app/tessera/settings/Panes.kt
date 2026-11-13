package app.tessera.settings

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.app.admin.DevicePolicyManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.DateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------- 通知

fun notificationsPage(act: SettingsActivity): Page {
    var apps: List<AppEntry>? = null
    val pm = act.packageManager
    val perm = "android.permission.POST_NOTIFICATIONS"
    val page = Page(act, "通知") {
        card { nav("朗读通知") { speakPage(act) } }
        val list = apps
        if (list == null) card { row("正在载入…").tone = { Theme.secondary } }
        else card("通知样式", "关闭后该 App 不能再发送通知。轻点 App 名称可打开它的全部通知选项。") {
            for (a in list) {
                toggle(a.label, { pm.checkPermission(perm, a.pkg) == PackageManager.PERMISSION_GRANTED }) { v, done ->
                    Root.run("pm ${if (v) "grant" else "revoke"} ${a.pkg} $perm", cb = done)
                }.apply {
                    app = a
                    onTap = { act.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.pkg)) }
                }
            }
        }
        card { link("系统通知设置", "android.settings.NOTIFICATION_SETTINGS") }
    }
    Apps.load(act) { apps = it; page.rebuild() }
    return page
}

// ---------------------------------------------------------------- 声音

@Suppress("DEPRECATION")   // HAPTIC_FEEDBACK_ENABLED: deprecated upstream, but still the key the brief names
fun soundPage(act: SettingsActivity) = Page(act, "声音") {
    val am = act.getSystemService(AudioManager::class.java)

    fun volume(stream: Int) {
        val lo = am.getStreamMinVolume(stream)
        val hi = am.getStreamMaxVolume(stream)
        val s = SliderRow(act, SliderRow.Ends.SPEAKER, hi - lo + 1)
        add(s)
        s.onChange = { v, done ->
            // Dropping ring volume to zero can count as changing Do Not Disturb, which needs policy access.
            try { am.setStreamVolume(stream, lo + (v * (hi - lo)).roundToInt(), 0) } catch (_: SecurityException) {}
            if (done) act.refreshSoon()
        }
        bind { s.set((am.getStreamVolume(stream) - lo) / max(1, hi - lo).toFloat()) }
    }

    card("媒体") { volume(AudioManager.STREAM_MUSIC) }
    card("通知与铃声") { volume(AudioManager.STREAM_NOTIFICATION) }
    card("闹钟") { volume(AudioManager.STREAM_ALARM) }
    card {
        toggle("静音模式", { am.ringerMode != AudioManager.RINGER_MODE_NORMAL }) { v, done ->
            try {
                am.ringerMode = if (v) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                done(true)
            } catch (_: SecurityException) {
                Root.run("cmd audio set-ringer-mode ${if (v) "SILENT" else "NORMAL"}", cb = done)
            }
        }
    }
    card {
        toggle("触感反馈", "system", Settings.System.HAPTIC_FEEDBACK_ENABLED, true)
        toggle("按键音", { Sys.int("system", Settings.System.SOUND_EFFECTS_ENABLED)?.let { it != 0 } ?: true }) { v, done ->
            Sys.put("system", Settings.System.SOUND_EFFECTS_ENABLED, if (v) "1" else "0") { ok ->
                // The setting alone is only read at boot; this is what the stock app does to apply it now.
                if (ok) if (v) am.loadSoundEffects() else am.unloadSoundEffects()
                done(ok)
            }
        }
    }
    card { nav("App 通知声音") { appSoundsPage(act) } }
    card { link("更多声音设置", Settings.ACTION_SOUND_SETTINGS) }
}

// ---------------------------------------------------------------- 专注模式

fun focusPage(act: SettingsActivity) = Page(act, "专注模式") {
    val nm = act.getSystemService(NotificationManager::class.java)
    card(footer = "勿扰模式开启时，来电与通知将被静音。") {
        toggle("勿扰模式", {
            Sys.int("global", "zen_mode")?.let { it != 0 }
                ?: nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        }) { v, done -> Root.run("cmd notification set_dnd ${if (v) "on" else "off"}", cb = done) }
    }
    card {
        link("勿扰模式设置", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) ?: link("勿扰模式设置", "android.settings.ZEN_MODE_SETTINGS")
        link("勿扰权限", Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }
}

// ---------------------------------------------------------------- 通用

private object Disk {
    fun total(c: Context): Long = try {
        c.getSystemService(StorageStatsManager::class.java).getTotalBytes(StorageManager.UUID_DEFAULT)
    } catch (_: Exception) { StatFs(Environment.getDataDirectory().path).totalBytes }

    fun free(c: Context): Long = try {
        c.getSystemService(StorageStatsManager::class.java).getFreeBytes(StorageManager.UUID_DEFAULT)
    } catch (_: Exception) { StatFs(Environment.getDataDirectory().path).availableBytes }
}

/** Asks the daemon whether it is there before a power command, whose own reply dies with the system. */
private fun power(act: SettingsActivity, title: String, question: String, cmd: String, destructive: Boolean) {
    Alert.show(act, title, question, ok = title, destructive = destructive) {
        Root.async("echo ok") { pong ->
            if (pong?.trim() == "ok") Root.async(cmd, 10000)
            else if (!act.isDestroyed) Alert.show(act, "无法$title", "没有连接到 root 助手 tesserad。", cancel = null)
        }
    }
}

fun generalPage(act: SettingsActivity) = Page(act, "通用") {
    card {
        nav("关于本机") { aboutPage(act) }
        val update = listOf(Intent("android.settings.SYSTEM_UPDATE_SETTINGS"))
            .plus(listOf("com.lenovo.ota", "com.zui.ota", "com.lenovo.tbengine").mapNotNull { act.packageManager.getLaunchIntentForPackage(it) })
            .firstOrNull { act.resolves(it) }
        if (update != null) link("软件更新", update)
    }
    card { nav("储存空间") { storagePage(act) } }
    card {
        nav("日期与时间") { dateTimePage(act) }
        link("键盘", Settings.ACTION_INPUT_METHOD_SETTINGS)
        link("语言与地区", Settings.ACTION_LOCALE_SETTINGS)
    }
    card { link("开发者选项", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
    card {
        row("重新启动") { power(act, "重新启动", "确定要重新启动这台平板电脑吗？", "svc power reboot", false) }.tone = { Theme.tint }
        row("关机") { power(act, "关机", "确定要将这台平板电脑关机吗？", "svc power shutdown", true) }.tone = { Theme.red }
    }
}

fun aboutPage(act: SettingsActivity): Page {
    var serial: String? = null
    val page = Page(act, "关于本机") {
        card {
            info("名称") { Settings.Global.getString(act.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL }
            info("型号") { Build.MODEL }
            info("Android 版本") { Build.VERSION.RELEASE }
            info("系统版本") { Build.DISPLAY }
            info("安全补丁") { Build.VERSION.SECURITY_PATCH }
            info("序列号") { serial }
        }
        card {
            info("处理器") { Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } }
            info("内存") {
                val mi = ActivityManager.MemoryInfo()
                act.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
                Sys.size(mi.totalMem)
            }
            info("总容量") { Sys.size(Disk.total(act)) }
            info("可用容量") { Sys.size(Disk.free(act)) }
            info("内核版本") { System.getProperty("os.version")?.substringBefore('-') }
        }
    }
    Root.async("getprop ro.serialno") { serial = it?.trim()?.ifEmpty { null }; page.refresh() }
    return page
}

fun storagePage(act: SettingsActivity): Page {
    var parts: List<StorageBar.Part> = emptyList()
    var total = 0L
    var free = 0L
    val page = Page(act, "储存空间") {
        card {
            val bar = StorageBar(act)
            add(bar)
            bar.set(parts, total, if (total == 0L) "正在计算…" else "已使用 ${Sys.size(total - free)}（共 ${Sys.size(total)}）")
        }
        card { for (p in parts) row(p.label, Sys.size(p.bytes)) }
        card { link("管理储存空间", Settings.ACTION_INTERNAL_STORAGE_SETTINGS) }
    }
    Ui.bg {
        val t = Disk.total(act)
        val f = Disk.free(act)
        val used = t - f
        // The per-category breakdown needs usage access; without it the bar is simply used versus free.
        val list = try {
            val ssm = act.getSystemService(StorageStatsManager::class.java)
            val me = Process.myUserHandle()
            val a = ssm.queryStatsForUser(StorageManager.UUID_DEFAULT, me)
            val e = ssm.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, me)
            val apps = a.appBytes + a.dataBytes
            val known = apps + e.imageBytes + e.videoBytes + e.audioBytes
            listOf(
                StorageBar.Part("App", 0xFF007AFF.toInt(), apps),
                StorageBar.Part("照片", 0xFFFFCC00.toInt(), e.imageBytes),
                StorageBar.Part("视频", 0xFFAF52DE.toInt(), e.videoBytes),
                StorageBar.Part("音频", 0xFFFF2D55.toInt(), e.audioBytes),
                StorageBar.Part("系统与其他", 0xFF8E8E93.toInt(), max(0L, used - known)),
            )
        } catch (_: Exception) {
            listOf(StorageBar.Part("已使用", 0xFF007AFF.toInt(), used))
        }
        Ui.ui { parts = list; total = t; free = f; page.rebuild() }
    }
    return page
}

fun dateTimePage(act: SettingsActivity) = Page(act, "日期与时间") {
    card {
        toggle("24 小时制", {
            Sys.get("system", Settings.System.TIME_12_24)?.let { it == "24" } ?: DateFormat.is24HourFormat(act)
        }) { v, done -> Sys.put("system", Settings.System.TIME_12_24, if (v) "24" else "12", done) }
    }
    card {
        toggle("自动设置", "global", Settings.Global.AUTO_TIME, true)
        info("时区") { TimeZone.getDefault().let { it.getDisplayName(it.inDaylightTime(java.util.Date()), TimeZone.LONG, Locale.CHINA) } }
    }
    card { link("更多日期与时间设置", Settings.ACTION_DATE_SETTINGS) }
}

// ---------------------------------------------------------------- 显示与亮度

private val lockTimes = listOf(30_000 to "30 秒", 60_000 to "1 分钟", 120_000 to "2 分钟", 300_000 to "5 分钟", 600_000 to "10 分钟", 1_800_000 to "30 分钟")
private val fontScales = listOf(0.85f, 1f, 1.15f, 1.3f)

fun displayPage(act: SettingsActivity): Page {
    val ui = act.getSystemService(UiModeManager::class.java)
    /** Bumped whenever the user moves the brightness knob, so a slow read from before cannot undo it. */
    var gen = 0
    lateinit var page: Page

    fun override(v: Float) { act.window.attributes = act.window.attributes.apply { screenBrightness = v } }

    page = Page(act, "显示与亮度") {
        card("外观") {
            val picker = AppearancePicker(act) { night ->
                // Success arrives as a uiMode change a moment later; only failure needs the snap back now.
                Root.run("cmd uimode night ${if (night) "yes" else "no"}") { ok -> Ui.main.postDelayed({ page.refresh() }, if (ok) 900 else 0) }
            }
            add(picker)
            bind { picker.dark = Theme.dark }
            toggle("自动", {
                try { ui.nightMode.let { it == UiModeManager.MODE_NIGHT_AUTO || it == UiModeManager.MODE_NIGHT_CUSTOM } } catch (_: Exception) { null }
            }) { v, done -> Root.run("cmd uimode night ${if (v) "auto" else if (Theme.dark) "yes" else "no"}", cb = done) }
        }
        card("亮度") {
            val s = SliderRow(act, SliderRow.Ends.SUN)
            add(s)
            // The knob is perceptual (square law): linear brightness crams everything useful into the first third.
            s.onChange = { v, done ->
                gen++
                val b = v * v
                // A window-level override is instant and free, so the drag stays live without a root call per frame.
                override(b)
                if (done) {
                    val text = String.format(Locale.US, "%.4f", b)
                    fun settle() = Ui.main.postDelayed({ override(-1f); page.refresh() }, 350)
                    Root.run("cmd display set-brightness $text") { ok ->
                        if (ok) settle()
                        else Sys.put("system", Settings.System.SCREEN_BRIGHTNESS, (b * 255).roundToInt().coerceIn(1, 255).toString()) { settle() }
                    }
                }
            }
            bind {
                if (!s.tracking) {
                    val g = gen
                    // First paint only: the legacy 0..255 setting is a rough stand-in until root reports the real float.
                    if (s.value == 0f) Sys.int("system", Settings.System.SCREEN_BRIGHTNESS)?.let { s.set(sqrt(it / 255f)) }
                    Root.async("cmd display get-brightness 0 2>/dev/null || cmd display get-brightness") { out ->
                        val f = out?.let { Regex("[0-9]*\\.?[0-9]+").find(it)?.value?.toFloatOrNull() }
                        if (f != null && g == gen) s.set(sqrt(f.coerceIn(0f, 1f)))
                        else if (out == null && g == gen) Sys.int("system", Settings.System.SCREEN_BRIGHTNESS)?.let { s.set(sqrt(it / 255f)) }
                    }
                }
            }
            toggle("自动亮度", "system", Settings.System.SCREEN_BRIGHTNESS_MODE, false)
        }
        card {
            nav("自动锁定", {
                val ms = Sys.int("system", Settings.System.SCREEN_OFF_TIMEOUT)
                lockTimes.firstOrNull { it.first == ms }?.second ?: ms?.let { "${it / 1000} 秒" }
            }) { autoLockPage(act) }
        }
        card("字体大小", "拖移滑块以调整系统字体大小。") {
            val s = SliderRow(act, SliderRow.Ends.LETTER, fontScales.size)
            add(s)
            s.onChange = { v, done ->
                // Applied on release only: each change makes the system rebuild every visible activity, ours included.
                if (done) Sys.put("system", Settings.System.FONT_SCALE, fontScales[(v * (fontScales.size - 1)).roundToInt()].toString()) { ok ->
                    if (!ok) page.refresh()
                }
            }
            bind {
                val now = Sys.get("system", Settings.System.FONT_SCALE)?.toFloatOrNull() ?: act.resources.configuration.fontScale
                val i = fontScales.indices.minBy { abs(fontScales[it] - now) }
                s.set(i / (fontScales.size - 1f))
            }
        }
        card {
            link("护眼模式", Settings.ACTION_NIGHT_DISPLAY_SETTINGS)
            link("更多显示设置", Settings.ACTION_DISPLAY_SETTINGS)
        }
    }
    return page
}

fun autoLockPage(act: SettingsActivity): Page {
    lateinit var page: Page
    page = Page(act, "自动锁定") {
        card {
            for ((ms, label) in lockTimes) choice(label, { Sys.int("system", Settings.System.SCREEN_OFF_TIMEOUT) == ms }) {
                Sys.put("system", Settings.System.SCREEN_OFF_TIMEOUT, ms.toString()) { page.refresh() }
            }
        }
    }
    return page
}

// ---------------------------------------------------------------- 电池

/** Last ACTION_BATTERY_CHANGED, kept so every pane reads the same snapshot. */
object Batt {
    var level = -1
    var status = BatteryManager.BATTERY_STATUS_UNKNOWN
    var plugged = 0
    var tenths = Int.MIN_VALUE
    var health = 0
    var millivolts = 0
    var cycles = 0

    fun update(i: Intent?) {
        if (i?.action != Intent.ACTION_BATTERY_CHANGED) return
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).let { if (it < 0) -1 else it * 100 / scale }
        status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)
        millivolts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        cycles = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, 0)
    }

    val charging get() = status == BatteryManager.BATTERY_STATUS_CHARGING

    fun state() = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        // Plugged in but holding: this is what charge protection looks like from the outside.
        else -> if (plugged != 0) "已接通电源，未充电" else "未充电"
    }

    fun healthText() = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
        BatteryManager.BATTERY_HEALTH_DEAD -> "已损坏"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
        BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "故障"
        else -> null
    }

    fun source() = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "电源适配器"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "底座"
        else -> "电池"
    }
}

fun batteryPage(act: SettingsActivity) = Page(act, "电池") {
    val pm = act.getSystemService(PowerManager::class.java)
    card {
        val h = BatteryHeader(act)
        add(h)
        bind { h.level = Batt.level; h.charging = Batt.charging; h.saver = pm.isPowerSaveMode; h.state = Batt.state(); h.invalidate() }
    }
    card(footer = "低电量模式会暂时减少后台活动，直到充电为止。接通电源时系统不允许开启。") {
        toggle("低电量模式", { pm.isPowerSaveMode }) { v, done -> Sys.put("global", "low_power", if (v) "1" else "0", done) }
    }
    card(footer = "开启后电量充至约 60% 停止充电") {
        toggle("充电保护", "system", "longtime_charging_protect_enable")
    }
    card("电池信息") {
        info("温度") { Batt.tenths.takeIf { it != Int.MIN_VALUE }?.let { String.format(Locale.US, "%.1f ℃", it / 10f) } }
        info("健康状况") { Batt.healthText() }
        info("电压") { Batt.millivolts.takeIf { it > 0 }?.let { String.format(Locale.US, "%.2f V", it / 1000f) } }
        info("供电来源") { Batt.source() }
        if (Batt.cycles > 0) info("循环次数") { Batt.cycles.toString() }
    }
    card {
        link("电池用量", Intent.ACTION_POWER_USAGE_SUMMARY)
        link("省电模式设置", Settings.ACTION_BATTERY_SAVER_SETTINGS)
    }
}

// ---------------------------------------------------------------- App

fun appsPage(act: SettingsActivity): Page {
    var apps: List<AppEntry>? = null
    val page = Page(act, "App") {
        val list = apps
        card {
            if (list == null) row("正在载入…").tone = { Theme.secondary }
            else for (a in list) row(a.label, acc = Acc.CHEVRON) {
                act.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.pkg, null)))
            }.app = a
        }
        card { link("默认 App", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) }
    }
    Apps.load(act) { apps = it; page.rebuild() }
    return page
}

// ---------------------------------------------------------------- 仅跳转的面板

fun homePage(act: SettingsActivity) = Page(act, "主屏幕与多任务") {
    card("主屏幕") {
        link("默认主屏幕 App", Settings.ACTION_HOME_SETTINGS)
        link("默认 App", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }
    card("多任务与手势") {
        link("系统导航方式", "com.android.settings.GESTURE_NAVIGATION_SETTINGS")
        link("画中画", "android.settings.PICTURE_IN_PICTURE_SETTINGS")
        link("显示在其他 App 上层", Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    }
    footer("这些选项由系统设置提供。")
}

fun accessibilityPage(act: SettingsActivity) = Page(act, "辅助功能") {
    card {
        link("辅助功能", Settings.ACTION_ACCESSIBILITY_SETTINGS)
        link("显示大小与文字", "android.settings.TEXT_READING_SETTINGS")
        link("字幕", Settings.ACTION_CAPTIONING_SETTINGS)
        link("色彩校正", "com.android.settings.ACCESSIBILITY_COLOR_SPACE_SETTINGS")
        link("极暗", "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS")
    }
}

fun wallpaperPage(act: SettingsActivity) = Page(act, "墙纸") {
    card {
        link("选取新墙纸", Intent(Intent.ACTION_SET_WALLPAPER))
        link("墙纸与个性化", "android.settings.WALLPAPER_SETTINGS")
    }
    card { link("屏幕保护程序", Settings.ACTION_DREAM_SETTINGS) }
}

fun penPage(act: SettingsActivity) = Page(act, "手写笔") {
    card {
        link("手写笔设置", "android.settings.STYLUS_USI_DETAILS_SETTINGS")
        link("配对手写笔", Settings.ACTION_BLUETOOTH_SETTINGS)
        link("实体键盘", Settings.ACTION_HARD_KEYBOARD_SETTINGS)
        link("默认笔记 App", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }
    footer("手写笔通过蓝牙配对；其余选项由系统设置提供。")
}

@Suppress("DEPRECATION")   // ACTION_FINGERPRINT_ENROLL still resolves on ROMs that predate BIOMETRIC_ENROLL handling
fun securityPage(act: SettingsActivity) = Page(act, "生物识别与密码") {
    card {
        link("安全", Settings.ACTION_SECURITY_SETTINGS)
        link("更改锁屏密码", DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
    }
    card("生物识别") {
        link("添加指纹", Settings.ACTION_FINGERPRINT_ENROLL)
        link("设置生物识别", Settings.ACTION_BIOMETRIC_ENROLL)
    }
}

fun privacyPage(act: SettingsActivity) = Page(act, "隐私与安全性") {
    val lm = act.getSystemService(LocationManager::class.java)
    card {
        link("定位服务", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) { if (lm.isLocationEnabled) "打开" else "关闭" }
        link("隐私", Settings.ACTION_PRIVACY_SETTINGS)
        link("权限管理", "android.intent.action.MANAGE_PERMISSIONS")
    }
    card("特殊访问权限") {
        link("使用情况访问权限", Settings.ACTION_USAGE_ACCESS_SETTINGS)
        link("所有文件访问权限", Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        link("安装未知 App", Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        link("通知读取权限", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        link("修改系统设置", Settings.ACTION_MANAGE_WRITE_SETTINGS)
    }
    card { link("安全", Settings.ACTION_SECURITY_SETTINGS) }
}
