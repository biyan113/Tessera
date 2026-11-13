package app.tessera.settings

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings

/** Wi-Fi state: public WifiManager for everything readable, the root helper for what it hides or forbids. */
object Wifi {
    /** [kind] is the `cmd wifi connect-network` security word, or "other" for types it cannot join. */
    class Net(val ssid: String, val bars: Int, val kind: String)

    private lateinit var app: Context
    private lateinit var wm: WifiManager
    private lateinit var cm: ConnectivityManager
    /** SSID as reported by `cmd wifi status`, for when the API redacts it (no location access). */
    private var statusSsid: String? = null
    private var lastPoll = 0L
    private val later = Runnable { poll() }

    /** Saved SSIDs mapped to their stored passphrase where there is one. Memory only, never persisted. */
    var saved: Map<String, String?> = emptyMap()
        private set
    /** SSID of a join in flight, so its row can say so. */
    var joining: String? = null

    fun init(c: Context) {
        app = c.applicationContext
        wm = app.getSystemService(WifiManager::class.java)
        cm = app.getSystemService(ConnectivityManager::class.java)
    }

    val enabled: Boolean? get() = try { wm.isWifiEnabled } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private val info get() = try { wm.connectionInfo } catch (_: Exception) { null }

    fun connected(): Boolean {
        if (enabled != true) return false
        if (info?.supplicantState == SupplicantState.COMPLETED) return true
        return try {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) { false }
    }

    fun current(): String? {
        if (!connected()) return null
        val s = info?.ssid
        return if (s.isNullOrEmpty() || s == WifiManager.UNKNOWN_SSID) statusSsid else s.removeSurrounding("\"")
    }

    private fun bars(rssi: Int) = when (wm.calculateSignalLevel(rssi) * 4 / wm.maxSignalLevel.coerceAtLeast(1)) {
        0, 1 -> 1
        2 -> 2
        else -> 3
    }

    fun currentBars() = info?.let { bars(it.rssi) } ?: 3

    fun label() = when {
        enabled != true -> "关闭"
        !connected() -> "未连接"
        else -> current() ?: "已连接"
    }

    /** Scan results need fine location and the location master switch; without either the list is just empty. */
    fun canScan() = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        app.getSystemService(LocationManager::class.java).isLocationEnabled

    /** One entry per SSID (strongest AP wins), alphabetical like iOS so rows do not reshuffle with signal. */
    @Suppress("DEPRECATION")
    fun scan(): List<Net> = try {
        wm.scanResults.filter { !it.SSID.isNullOrEmpty() }.groupBy { it.SSID }.map { (ssid, aps) ->
            val best = aps.maxBy { it.level }
            val caps = best.capabilities ?: ""
            val kind = when {
                "EAP" in caps || "WEP" in caps -> "other"
                "PSK" in caps -> "wpa2"
                "SAE" in caps -> "wpa3"
                "OWE" in caps && "OWE_TRANSITION" !in caps -> "owe"
                else -> "open"
            }
            Net(ssid, bars(best.level), kind)
        }.sortedBy { it.ssid.lowercase() }
    } catch (_: Exception) { emptyList() }

    @Suppress("DEPRECATION")
    fun startScan() {
        val ok = try { wm.startScan() } catch (_: Exception) { false }
        // The app-facing call is throttled to a few scans per two minutes; the shell one is not.
        if (!ok) Root.async("cmd wifi start-scan")
    }

    /** Asks root for what the API will not tell: the redacted SSID and the saved-network list. */
    fun poll() {
        val now = SystemClock.uptimeMillis()
        Ui.main.removeCallbacks(later)
        // Rate-limited, but with a trailing run: the broadcast that matters is usually the last of a burst.
        if (now - lastPoll < 2000) { Ui.main.postDelayed(later, 2000 - (now - lastPoll)); return }
        lastPoll = now
        if (connected() && info?.ssid.let { it.isNullOrEmpty() || it == WifiManager.UNKNOWN_SSID }) {
            Root.async("cmd wifi status") { out ->
                val s = out?.let { Regex("Wifi is connected to \"(.*)\"").find(it)?.groupValues?.get(1) }
                if (s != statusSsid) { statusSsid = s; Sys.onChange?.invoke() }
            }
        }
        val stores = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml /data/misc_ce/0/apexdata/com.android.wifi/WifiConfigStore.xml"
        Root.async("cmd wifi list-networks; echo ----; cat $stores 2>/dev/null") { out ->
            if (out == null) return@async
            val m = parseSaved(out)
            if (m != saved) { saved = m; Sys.onChange?.invoke() }
        }
    }

    /**
     * `list-networks` names what is saved; the config store adds each passphrase. `connect-network`
     * insists on being given the passphrase again even for a saved network, hence reading it back.
     */
    private fun parseSaved(out: String): Map<String, String?> {
        val m = HashMap<String, String?>()
        val row = Regex("^\\d+\\s+(.*\\S)\\s+\\S+\\s*$")
        for (line in out.substringBefore("----").lines()) row.find(line)?.let { m[it.groupValues[1]] = null }
        fun unxml(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val ssid = Regex("<string name=\"SSID\">(.*?)</string>")
        val psk = Regex("<string name=\"PreSharedKey\">(.*?)</string>")
        for (block in out.substringAfter("----", "").split("<WifiConfiguration>").drop(1)) {
            val name = ssid.find(block)?.let { unxml(it.groupValues[1]) } ?: continue
            if (!name.startsWith("\"")) continue   // hex-encoded SSIDs cannot be passed to the shell command anyway
            val key = psk.find(block)?.let { unxml(it.groupValues[1]) }?.takeIf { it.startsWith("\"") }
            m[name.removeSurrounding("\"")] = key?.removeSurrounding("\"")
        }
        return m
    }

    fun join(act: SettingsActivity, n: Net) {
        when (n.kind) {
            // Enterprise and WEP need fields the shell command has no arguments for.
            "other" -> act.open(Intent(Settings.ACTION_WIFI_SETTINGS))
            "open", "owe" -> connect(act, n, null)
            else -> {
                val known = saved[n.ssid]
                if (known != null) connect(act, n, known)
                else Alert.show(act, "输入“${n.ssid}”的密码", hint = "密码", secret = true, ok = "加入") {
                    if (it.length >= 8) connect(act, n, it)
                    else Alert.show(act, "密码不正确", "无线局域网密码至少需要 8 个字符。", cancel = null)
                }
            }
        }
    }

    private fun connect(act: SettingsActivity, n: Net, pass: String?) {
        joining = n.ssid
        act.refreshSoon()
        val cmd = "cmd wifi connect-network ${Root.quote(n.ssid)} ${n.kind}" + (pass?.let { " " + Root.quote(it) } ?: "")
        fun failed(why: String) {
            if (joining == n.ssid) joining = null
            if (act.isDestroyed) return
            act.refreshSoon()
            Alert.show(act, "无法加入网络“${n.ssid}”", why, cancel = null)
        }
        Root.run(cmd, 8000) { ok ->
            if (!ok) failed("未能通过 root 助手 tesserad 执行连接命令。")
            // The command only queues the attempt; a wrong passphrase shows up as never getting there.
            else Ui.main.postDelayed({
                if (joining == n.ssid) { if (current() != n.ssid) failed("请检查密码后重试。") else joining = null }
            }, 15000)
        }
    }
}

fun wifiPage(act: SettingsActivity) = Page(act, "无线局域网") {
    val on = Wifi.enabled == true
    val cur = Wifi.current()
    val linked = Wifi.connected()
    if (Wifi.joining != null && Wifi.joining == cur) Wifi.joining = null
    val nets = if (on) Wifi.scan() else emptyList()
    sig = {
        listOf(Wifi.enabled, Wifi.connected(), Wifi.current(), Wifi.joining, Wifi.saved.keys.sorted(), Wifi.currentBars(),
            Wifi.scan().map { it.ssid + "/" + it.bars + it.kind })
    }
    onShow = { Wifi.startScan(); Wifi.poll() }

    fun net(n: Wifi.Net) = row(n.ssid, if (Wifi.joining == n.ssid) "正在加入…" else null) { Wifi.join(act, n) }.apply {
        leadCheck = false
        signal = n.bars
        lock = n.kind != "open" && n.kind != "owe"
    }

    card {
        toggle("无线局域网", { Wifi.enabled }) { v, done -> Root.run("svc wifi ${if (v) "enable" else "disable"}", cb = done) }
        if (linked) row(cur ?: "已连接").apply {
            leadCheck = true
            signal = Wifi.currentBars()
            lock = nets.firstOrNull { it.ssid == cur }?.let { it.kind != "open" && it.kind != "owe" } ?: false
        }
    }
    if (on) {
        val (mine, others) = nets.filter { it.ssid != cur }.partition { it.ssid in Wifi.saved }
        card("我的网络") { mine.forEach { net(it) } }
        card("其他网络", if (Wifi.canScan()) null else "需要授予位置权限并打开定位服务，才能列出附近的网络。") {
            others.forEach { net(it) }
            if (nets.isEmpty()) row(if (Wifi.canScan()) "正在搜索网络…" else "无法列出附近的网络").tone = { Theme.secondary }
        }
    }
    card { link("其他网络设置", Settings.ACTION_WIFI_SETTINGS) }
}

/** Bluetooth state. Reading works with BLUETOOTH_CONNECT; switching the radio is root-only since Android 13. */
object Bt {
    class Dev(val name: String, val connected: Boolean)

    private var bm: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private val proxies = HashMap<Int, BluetoothProfile>()
    private val hiddenIsConnected by lazy {
        try { BluetoothDevice::class.java.getMethod("isConnected") } catch (_: Throwable) { null }
    }

    fun init(c: Context) {
        if (bm != null) return
        val app = c.applicationContext
        bm = app.getSystemService(BluetoothManager::class.java)
        adapter = bm?.adapter
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy; Sys.onChange?.invoke() }
            override fun onServiceDisconnected(profile: Int) { proxies.remove(profile) }
        }
        // 4 is HID_HOST (keyboards, mice, pens): not in the public API, so it may simply be refused.
        for (p in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4)) {
            try { adapter?.getProfileProxy(app, listener, p) } catch (_: Throwable) {}
        }
    }

    val enabled: Boolean? get() = try { adapter?.isEnabled } catch (_: Exception) { null }

    /**
     * "Connected" has no single public answer, so it is the union of every source that will talk to
     * us: the profile proxies, GATT links (which covers BLE keyboards and pens), and the hidden
     * BluetoothDevice.isConnected() when hidden-API policy lets it through.
     */
    private fun live(): Set<BluetoothDevice> {
        val s = HashSet<BluetoothDevice>()
        for (p in proxies.values) try { s += p.connectedDevices } catch (_: Throwable) {}
        try { bm?.getConnectedDevices(BluetoothProfile.GATT)?.let { s += it } } catch (_: Throwable) {}
        return s
    }

    /** Null when BLUETOOTH_CONNECT has not been granted. */
    fun devices(): List<Dev>? = try {
        val live = live()
        adapter?.bondedDevices?.map { d ->
            val hidden = try { hiddenIsConnected?.invoke(d) as? Boolean } catch (_: Throwable) { null }
            Dev(d.alias ?: d.name ?: d.address, d in live || hidden == true)
        }?.sortedWith(compareBy({ !it.connected }, { it.name.lowercase() }))
    } catch (_: SecurityException) { null }
}

fun bluetoothPage(act: SettingsActivity) = Page(act, "蓝牙") {
    val on = Bt.enabled == true
    sig = { listOf(Bt.enabled, Bt.devices()?.map { it.name to it.connected }) }
    card {
        toggle("蓝牙", { Bt.enabled }) { v, done -> Root.run("svc bluetooth ${if (v) "enable" else "disable"}", cb = done) }
    }
    if (on) card("我的设备") {
        val devs = Bt.devices()
        when {
            devs == null -> row("需要“附近的设备”权限").tone = { Theme.secondary }
            devs.isEmpty() -> row("没有已配对的设备").tone = { Theme.secondary }
            else -> devs.forEach { row(it.name, if (it.connected) "已连接" else "未连接") }
        }
    }
    card { link("其他蓝牙设置", Settings.ACTION_BLUETOOTH_SETTINGS) }
}
