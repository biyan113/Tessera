package app.homepad

import android.app.Activity
import android.app.ActivityOptions
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher

class HomeActivity : Activity() {

    lateinit var home: HomeView
    private lateinit var root: FrameLayout
    private lateinit var host: AppWidgetHost
    private lateinit var awm: AppWidgetManager
    var overlay: View? = null
        private set
    private var pendingWidget: Triple<Int, Int, Int>? = null
    private var stopped = false

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_WALLPAPER_CHANGED) Glass.load(c) { wallpaperReady() }
            else home.invalidate()
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setDecorFitsSystemWindows(false)
        Icons.size = (64 * resources.displayMetrics.density).toInt()
        awm = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(applicationContext, 1024)
        root = FrameLayout(this)
        setContentView(root)
        home = HomeView(this)

        Apps.map.clear()
        Apps.map.putAll(Apps.load(this))
        val saved = Layout.load(this)
        val first = saved == null
        home.layoutModel = (saved ?: Layout.seed(Apps.map)).also { it.reconcile(Apps.map) }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            home.insetTop = b.top
            home.insetBottom = b.bottom
            home.metrics()
            ins
        }
        if (first && Apps.map.isNotEmpty()) {
            seedWeather()
            persist()
        }

        Apps.onChanged = { home.refreshAll(); persist() }
        Apps.watch(this)
        val repaint = { home.invalidate(); home.grids.forEach { it.invalidate() }; home.library?.refresh(); Unit }
        Icons.onLoaded = repaint
        BadgeService.onChanged = repaint
        Glass.load(this) { wallpaperReady() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (overlay != null) closeOverlay() else home.goHome()
        }
        home.post { home.scroll = home.width.toFloat(); home.syncWidgets(); home.invalidate() }
    }

    private fun seedWeather() {
        val info = awm.installedProviders.firstOrNull { it.provider == Layout.weatherProvider } ?: return
        val id = host.allocateAppWidgetId()
        if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) {
            home.layoutModel.pages[0].add(Item.Widget(K_HOST, id, 4, 2))
        } else host.deleteAppWidgetId(id)
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
        Icons.restore()
    }

    override fun onResume() {
        super.onResume()
        // A zoom still on screen means the launch never covered us (cancelled, or bounced straight back).
        if (clearZoom() || stopped) settleIn()
        stopped = false
        val f = IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
        }
        registerReceiver(ticker, f, RECEIVER_EXPORTED)
        loadRecents()
        home.invalidate()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(ticker)
    }

    override fun onStop() {
        super.onStop()
        host.stopListening()
        clearZoom()
        stopped = true
        Icons.shrink()
        Icons.release()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Icons.shrink()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        // Coming back from an app keeps the page it was opened from; a press on home itself goes home.
        if (i.hasCategory(Intent.CATEGORY_HOME) && !stopped) {
            if (overlay != null) closeOverlay() else home.goHome()
        }
    }

    private fun loadRecents() {
        Bg.run {
            Frequent.refresh(this)
            val out = try {
                val us = getSystemService(UsageStatsManager::class.java)!!
                val now = System.currentTimeMillis()
                val used = us.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now)
                    .filter { it.totalTimeInForeground > 0 }
                    .sortedByDescending { it.lastTimeUsed }
                    .map { it.packageName }
                val docked = home.layoutModel.dock.map { it.key }.toSet()
                used.mapNotNull { p -> Apps.map.values.firstOrNull { it.pkg == p && it.key !in docked } }
                    .distinctBy { it.pkg }.take(3)
            } catch (_: Exception) {
                emptyList()
            }
            Bg.ui { home.recents = out; home.reflow() }
        }
    }

    /** An empty app list means the OS is withholding it, never that the owner has no apps. */
    fun persist() {
        if (Apps.map.isNotEmpty()) home.layoutModel.save(this)
    }

    private var zoom: LaunchView? = null

    fun launch(a: AppInfo, r: RectF) {
        val b = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        val o = ActivityOptions.makeCustomAnimation(this, R.anim.app_in, R.anim.home_hold).toBundle()
        if (!Apps.launch(this, a, b, o)) return
        clearZoom()
        zoom = LaunchView(this, Icons.get(a), RectF(r)).also {
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
            it.start()
        }
        // The rest of the home screen falls away past the viewer as the app comes forward.
        home.pivotX = r.centerX()
        home.pivotY = r.centerY()
        home.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.3f).setDuration(360)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    private fun clearZoom(): Boolean {
        val v = zoom ?: return false
        zoom = null
        root.removeView(v)
        return true
    }

    /** Coming back: the icons arrive from slightly too large and settle, on a spring with a little life in it. */
    private fun settleIn() {
        val s = Spring(0.86f, 0.42f)
        home.animate().cancel()
        home.alpha = 0f
        home.scaleX = 1.12f
        home.scaleY = 1.12f
        home.pivotX = home.width / 2f
        home.pivotY = home.height / 2f
        home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(s.millis).setInterpolator(s).start()
    }

    /** Where a launch with no icon on screen, such as search's enter key, grows from. */
    fun centerRect(): RectF {
        val s = 64 * resources.displayMetrics.density
        val x = (home.width - s) / 2
        val y = (home.height - s) / 2
        return RectF(x, y, x + s, y + s)
    }

    // ---- overlays: built on open, dropped on close, so they cost nothing while idle ----

    private fun wallpaperReady() {
        home.invalidate()
        applyBars()
    }

    /** System icons follow what is really behind them: dark on bright wallpaper, white otherwise. */
    private fun applyBars() {
        val dim = overlay != null
        val flags = (if (!dim && Glass.topLuma > BRIGHT) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
            (if (!dim && Glass.bottomLuma > BRIGHT) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)
        window.insetsController?.setSystemBarsAppearance(
            flags,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    private var blurAnim: android.animation.ValueAnimator? = null
    private var blurNow = 0f

    /**
     * Blurs the icons inside this window. The wallpaper is a different window, which this device
     * cannot blur, so overlays still lay the frosted wallpaper sample over it.
     */
    private fun blurTo(target: Float) {
        blurAnim?.cancel()
        blurAnim = android.animation.ValueAnimator.ofFloat(blurNow, target).apply {
            duration = 220
            addUpdateListener {
                blurNow = it.animatedValue as Float
                val r = blurNow * resources.displayMetrics.density
                home.setRenderEffect(
                    if (r < 1f) null
                    else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)
                )
            }
            start()
        }
    }

    fun show(v: View) {
        closeOverlay()
        overlay = v
        applyBars()
        blurTo(26f)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(170).start()
    }

    fun closeOverlay() {
        val v = overlay ?: return
        overlay = null
        applyBars()
        blurTo(0f)
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
        val end = Runnable { root.removeView(v); Icons.release() }
        if (v is Morph) {
            // Goes back into the icon it came out of, and only then fades.
            v.leave()
            v.animate().alpha(0f).setStartDelay(90).setDuration(170).withEndAction(end).start()
        } else v.animate().alpha(0f).setDuration(140).withEndAction(end).start()
    }

    fun openSpotlight() = show(SpotlightView(this))
    fun openFolder(f: Item.Folder, r: RectF) { show(FolderView(this, f, null, r)); Sfx.pop() }
    fun openCategory(name: String, list: List<AppInfo>, r: RectF? = null) { show(FolderView(this, null, name to list, r)); Sfx.pop() }
    fun openGallery() = show(GalleryView(this))
    fun showMenu(it: Item, r: RectF, inDock: Boolean) { show(MenuView(this, it, r)); Sfx.pop() }

    fun confirmRemove(it: Item) = show(ConfirmView(this, it))

    fun removeItem(it: Item, uninstall: Boolean) {
        val m = home.layoutModel
        if (it is Item.App) {
            if (uninstall) Apps.map[it.key]?.let { a ->
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                return
            }
            m.removed.add(it.key)
        }
        if (it is Item.Folder) m.pages.lastOrNull()?.let { last ->
            it.apps.forEach { k -> last.add(Item.App(k)) }
        }
        if (it is Item.Widget) {
            home.dropWidgetView(it)
            if (it.kind == K_HOST) host.deleteAppWidgetId(it.id)
        }
        m.dock.remove(it)
        m.pages.forEach { p -> p.remove(it) }
        home.reflow()
        persist()
    }

    fun takeOutOfFolder(f: Item.Folder, key: String) {
        f.apps.remove(key)
        home.layoutModel.pages.lastOrNull()?.add(Item.App(key))
        home.layoutModel.reconcile(Apps.map)
        home.reflow()
        persist()
    }

    fun appInfoScreen(a: AppInfo) = Apps.launcher(this).startAppDetailsActivity(a.info.componentName, a.info.user, null, null)

    // ---- widgets ----

    fun widgetView(it: Item.Widget): AppWidgetHostView? {
        val info = awm.getAppWidgetInfo(it.id) ?: return null
        return host.createView(applicationContext, it.id, info).apply { setPadding(0, 0, 0, 0) }
    }

    fun addBuiltin(kind: String) {
        target().add(Item.Widget(kind, 0, 2, 2))
        afterAdd()
    }

    fun addHosted(p: AppWidgetProviderInfo, sx: Int, sy: Int) {
        val id = host.allocateAppWidgetId()
        pendingWidget = Triple(id, sx, sy)
        if (awm.bindAppWidgetIdIfAllowed(id, p.profile, p.provider, null)) configure(id, p)
        else startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, p.profile),
            REQ_BIND,
        )
    }

    private fun configure(id: Int, p: AppWidgetProviderInfo) {
        if (p.configure != null) host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
        else commitWidget()
    }

    private fun commitWidget() {
        val (id, sx, sy) = pendingWidget ?: return
        pendingWidget = null
        target().add(Item.Widget(K_HOST, id, sx, sy))
        afterAdd()
    }

    private fun target() = home.layoutModel.pages[home.page.coerceIn(0, home.layoutModel.pages.size - 1)]

    private fun afterAdd() {
        closeOverlay()
        home.reflow()
        home.syncWidgets()
        persist()
    }

    @Deprecated("Activity result API needs AndroidX, which this app avoids to stay small")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val pending = pendingWidget ?: return
        if (res != RESULT_OK) {
            host.deleteAppWidgetId(pending.first)
            pendingWidget = null
            return
        }
        if (req == REQ_BIND) awm.getAppWidgetInfo(pending.first)?.let { configure(pending.first, it) }
        else if (req == REQ_CONFIG) commitWidget()
    }

    fun openSettings() = startActivity(Intent(Settings.ACTION_SETTINGS))

    fun webSearch(q: String) {
        try {
            startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
    }

    companion object {
        private const val BRIGHT = 0.5f
        private const val REQ_BIND = 11
        private const val REQ_CONFIG = 12
    }
}
