package app.tessera.settings

import android.app.Activity
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.max

/**
 * The whole app: an iPadOS-style two-column Settings. The sidebar picks a section, the detail
 * pane holds that section's page stack, and alerts are views layered on top of both.
 */
class SettingsActivity : Activity() {
    lateinit var root: FrameLayout
        private set
    lateinit var detail: DetailPane
        private set
    private lateinit var sidebar: Sidebar
    private lateinit var columns: LinearLayout
    var alert: Alert? = null
    private var selected = "general"
    private var backHooked = false

    /** Only registered while there is something of ours to pop; otherwise Back is the system's and finishes us. */
    private val onBack = OnBackInvokedCallback {
        val a = alert
        if (a != null) a.dismiss() else detail.pop()
    }

    /** Kept as a value so onDestroy can tell whether the global hook is still this instance's. */
    private val lateArrival: () -> Unit = { refreshSoon() }

    private val refresher = Runnable { sidebar.refresh(); detail.top?.refresh() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> Batt.update(i)
                WifiManager.WIFI_STATE_CHANGED_ACTION, WifiManager.NETWORK_STATE_CHANGED_ACTION -> Wifi.poll()
            }
            refreshSoon()
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.init(this)
        Theme.dark = isNight(resources.configuration)
        Sys.app = applicationContext
        Sys.onChange = lateArrival
        Wifi.init(this)
        Bt.init(this)
        @Suppress("DEPRECATION")   // already the default at targetSdk 36; kept explicit for clarity
        window.setDecorFitsSystemWindows(false)

        sidebar = Sidebar(this)
        detail = DetailPane(this)
        columns = LinearLayout(this).apply {
            addView(sidebar, LinearLayout.LayoutParams(sidebarWidth(resources.configuration), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(detail, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        root = FrameLayout(this).apply { addView(columns) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            columns.setPadding(bars.left, 0, bars.right, 0)
            // Padding rather than scrolling under the status bar: without iOS's blur, text behind the clock just looks broken.
            sidebar.setPadding(0, bars.top, 0, 0)
            detail.setPadding(0, bars.top, 0, 0)
            Ui.bottom = max(bars.bottom, ime.bottom)
            sidebar.inset()
            detail.inset()
            // Keeps an alert's text field clear of the keyboard.
            alert?.setPadding(0, bars.top, 0, ime.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        styleBars()
        select(b?.getString("sel") ?: "general")
    }

    private fun isNight(c: Configuration) = c.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** iPad proportions: a narrower sidebar in portrait so the detail pane keeps a usable width. */
    private fun sidebarWidth(c: Configuration) = dp(if (c.orientation == Configuration.ORIENTATION_LANDSCAPE) 340 else 280)

    private fun styleBars() {
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (Theme.dark) 0 else light, light)
        window.decorView.setBackgroundColor(Theme.bg)
    }

    /** Light/dark and rotation are handled in place, so page stacks, scroll positions and half-typed searches survive. */
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val night = isNight(c)
        if (night != Theme.dark) {
            Theme.dark = night
            styleBars()
            restyle(root)
        }
        sidebar.layoutParams = sidebar.layoutParams.apply { width = sidebarWidth(c) }
        refreshSoon()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("sel", selected)
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // Exported because the Bluetooth broadcasts come from the Bluetooth app's uid, not from system_server.
        registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        // Read the sticky battery intent directly, so the pane is right on first draw.
        Batt.update(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        Wifi.poll()
        detail.top?.onShow?.invoke()
        refreshSoon()
    }

    override fun onPause() {
        super.onPause()
        Tones.stop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        Ui.main.removeCallbacks(refresher)
        if (Sys.onChange === lateArrival) Sys.onChange = null
        if (backHooked) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBack)
    }

    /** Coalesces bursts (a scan fires several broadcasts at once) into one pass over the visible rows. */
    fun refreshSoon() {
        Ui.main.removeCallbacks(refresher)
        Ui.main.postDelayed(refresher, 60)
    }

    /** Shows a section's root page. Tapping the current section again pops it back to that root, as on iPad. */
    fun select(id: String) {
        val s = sections.flatten().firstOrNull { it.id == id } ?: return
        val page = s.page ?: return
        selected = id
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        sidebar.mark(id)
        detail.setRoot(page(this))
    }

    private var picked: ((android.net.Uri) -> Unit)? = null

    /** Opens the system file picker for one audio file; [cb] runs only if the user picks something. */
    fun pickAudio(cb: (android.net.Uri) -> Unit) {
        picked = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        try { @Suppress("DEPRECATION") startActivityForResult(i, 1) } catch (_: Exception) { picked = null }
    }

    @Deprecated("Activity result API pulls in AndroidX; this app stays framework-only")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val cb = picked ?: return
        picked = null
        data?.data?.takeIf { res == RESULT_OK }?.let(cb)
    }

    fun syncBack() {
        val want = alert != null || detail.depth > 1
        if (want == backHooked) return
        backHooked = want
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, onBack)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBack)
    }
}
