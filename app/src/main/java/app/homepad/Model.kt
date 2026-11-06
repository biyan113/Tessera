package app.homepad

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/** One background thread for all off-UI work: a pool would only cost more stacks. */
object Bg {
    private val thread = HandlerThread("homepad-bg").apply { start() }
    val handler = Handler(thread.looper)
    val main = Handler(Looper.getMainLooper())
    fun run(block: () -> Unit) { handler.post(block) }
    fun ui(block: () -> Unit) { main.post(block) }
}

class AppInfo(val key: String, val label: String, val info: LauncherActivityInfo) {
    val pkg: String get() = info.componentName.packageName
    var shortLabel: String? = null
    var shortWidth = 0
    var search: String? = null
}

object Apps {
    val map = LinkedHashMap<String, AppInfo>()
    var onChanged: (() -> Unit)? = null
    private var callback: LauncherApps.Callback? = null

    fun launcher(c: Context) = c.getSystemService(LauncherApps::class.java)!!

    fun load(c: Context): LinkedHashMap<String, AppInfo> {
        val um = c.getSystemService(UserManager::class.java)!!
        val la = launcher(c)
        val me = android.os.Process.myUserHandle()
        val out = LinkedHashMap<String, AppInfo>()
        for (user in um.userProfiles) {
            val serial = um.getSerialNumberForUser(user)
            for (info in la.getActivityList(null, user)) {
                val cn = info.componentName
                if (cn.packageName == c.packageName) continue
                val key = "${cn.flattenToShortString()}#$serial"
                val label = info.label.toString() + if (user == me) "" else " · 分身"
                out[key] = AppInfo(key, label, info)
            }
        }
        return out
    }

    fun watch(c: Context) {
        val app = c.applicationContext
        val reload = {
            Bg.run {
                val next = load(app)
                Bg.ui { map.clear(); map.putAll(next); onChanged?.invoke() }
            }
        }
        val cb = object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) { reload() }
            override fun onPackageAdded(p: String, u: UserHandle) { reload() }
            override fun onPackageChanged(p: String, u: UserHandle) { reload() }
            override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) { reload() }
            override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) { reload() }
        }
        callback = cb
        launcher(app).registerCallback(cb, Bg.main)
    }

    fun launch(c: Context, a: AppInfo, src: Rect?, opts: android.os.Bundle?) {
        try {
            launcher(c).startMainActivity(a.info.componentName, a.info.user, src, opts)
        } catch (_: Exception) {
        }
    }

    fun shortcuts(c: Context, a: AppInfo): List<ShortcutInfo> = try {
        val q = LauncherApps.ShortcutQuery().setPackage(a.pkg).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
        )
        launcher(c).getShortcuts(q, a.info.user)?.sortedBy { it.rank }?.take(4) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Han-to-Latin data is heavy, so it is built only when search is first used. */
    fun buildSearch() {
        val t = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
        for (a in map.values) if (a.search == null) {
            val py = t.transliterate(a.label)
            val initials = py.split(' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }
            a.search = "${a.label.lowercase()}|${py.replace(" ", "")}|$initials|${a.pkg}"
        }
    }

    fun find(q: String): List<AppInfo> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return emptyList()
        return map.values.filter { (it.search ?: it.label.lowercase()).contains(s) }
            .sortedBy { if (it.label.lowercase().startsWith(s)) 0 else 1 }
    }

    private val hand = mapOf(
        "社交" to listOf("com.tencent.mm", "com.tencent.mobileqq", "com.twitter.android", "com.instagram", "org.telegram", "com.xingin.xhs", "com.tencent.weread"),
        "影音娱乐" to listOf("tv.danmaku.bili", "com.google.android.youtube", "com.facebook.aura", "com.netease.party", "com.zui.gallery", "com.zui.camera", "com.zui.recorder"),
        "效率" to listOf("ai.x.grok", "com.google.android.apps.bard", "com.openai.chatgpt", "com.larksuite", "ai.usemagpie", "cn.wps", "com.google.android.gm", "com.zui.calendar", "com.zui.notes"),
        "开发与远程" to listOf("com.server.auditor", "dev.tty7", "com.netease.uuremote", "com.orbitlink", "top.songya", "com.android.virtualization", "app.radif"),
        "实用工具" to listOf("com.zui.", "com.xiaomi.smarthome", "com.eg.android", "app.weatherpad", "com.google.android.apps.authenticator2", "com.tencent.wetype", "app.msime", "com.android.settings", "com.android.vending", "com.google.android.googlequicksearchbox", "com.android.chrome"),
    )

    /** Ordered category name to apps, in the fixed order the library shows them. */
    fun categories(): List<Pair<String, List<AppInfo>>> {
        val out = LinkedHashMap<String, MutableList<AppInfo>>()
        hand.keys.forEach { out[it] = ArrayList() }
        out["游戏"] = ArrayList()
        out["其他"] = ArrayList()
        for (a in map.values.sortedBy { it.label }) {
            val name = hand.entries.firstOrNull { e -> e.value.any { a.pkg.startsWith(it) } }?.key
                ?: if (a.info.applicationInfo.category == ApplicationInfo.CATEGORY_GAME) "游戏" else "其他"
            out.getValue(name).add(a)
        }
        return out.filter { it.value.isNotEmpty() }.map { it.key to it.value }
    }
}

/**
 * Apps the owner keeps coming back to. Each switch into an app scores one point that halves every
 * three days, so a burst of recent use beats heavy use last month. Switches are counted rather
 * than foreground time, because one long video should not outrank an app opened twenty times.
 */
object Frequent {
    private const val WINDOW = 14 * 86_400_000L
    private const val HALF_LIFE = 3 * 86_400_000.0
    private const val REFRESH = 10 * 60_000L
    private const val LIMIT = 12

    var top: List<AppInfo> = emptyList()
        private set
    private var scoredAt = 0L

    /** Call on the background thread; it reads the usage log, which can hold many thousands of events. */
    fun refresh(c: Context) {
        val now = System.currentTimeMillis()
        if (now - scoredAt < REFRESH) return
        val score = HashMap<String, Double>()
        try {
            val log = c.getSystemService(UsageStatsManager::class.java)!!.queryEvents(now - WINDOW, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (log.hasNextEvent()) {
                log.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val p = e.packageName
                if (p == last) continue
                last = p
                score[p] = (score[p] ?: 0.0) + 0.5.pow((now - e.timeStamp) / HALF_LIFE)
            }
        } catch (_: Exception) {
            return
        }
        scoredAt = now
        Bg.ui {
            val byPkg = HashMap<String, AppInfo>()
            for (a in Apps.map.values) byPkg.putIfAbsent(a.pkg, a)
            top = score.entries.sortedByDescending { it.value }.mapNotNull { byPkg[it.key] }.take(LIMIT)
        }
    }
}

sealed class Item {
    class App(val key: String) : Item()
    class Folder(var name: String, val apps: MutableList<String>) : Item()
    class Widget(val kind: String, var id: Int, var sx: Int, var sy: Int) : Item()
}

const val K_CLOCK = "clock"
const val K_CAL = "calendar"
const val K_BATT = "battery"
const val K_HOST = "host"

class Layout(val pages: MutableList<MutableList<Item>>, val dock: MutableList<Item.App>, val removed: MutableSet<String>) {

    fun allKeys(): HashSet<String> {
        val s = HashSet<String>()
        dock.forEach { s.add(it.key) }
        for (p in pages) for (i in p) when (i) {
            is Item.App -> s.add(i.key)
            is Item.Folder -> s.addAll(i.apps)
            else -> {}
        }
        return s
    }

    /** Drops uninstalled apps and adds newly installed ones to the last page. */
    fun reconcile(apps: Map<String, AppInfo>) {
        dock.removeAll { it.key !in apps }
        for (p in pages) {
            val it = p.listIterator()
            while (it.hasNext()) when (val i = it.next()) {
                is Item.App -> if (i.key !in apps) it.remove()
                is Item.Folder -> {
                    i.apps.retainAll { k -> k in apps }
                    if (i.apps.isEmpty()) it.remove()
                    else if (i.apps.size == 1) it.set(Item.App(i.apps[0]))
                }
                else -> {}
            }
        }
        removed.retainAll { it in apps }
        val have = allKeys()
        for (k in apps.keys) if (k !in have && k !in removed) {
            if (pages.size < 2) pages.add(ArrayList())
            pages.last().add(Item.App(k))
        }
    }

    fun save(c: Context) {
        val root = JSONObject()
        root.put("dock", JSONArray(dock.map { it.key }))
        root.put("removed", JSONArray(removed.toList()))
        val ps = JSONArray()
        for (p in pages) {
            val a = JSONArray()
            for (i in p) a.put(
                when (i) {
                    is Item.App -> JSONObject().put("t", "a").put("k", i.key)
                    is Item.Folder -> JSONObject().put("t", "f").put("n", i.name).put("a", JSONArray(i.apps))
                    is Item.Widget -> JSONObject().put("t", "w").put("kind", i.kind).put("id", i.id).put("x", i.sx).put("y", i.sy)
                }
            )
            ps.put(a)
        }
        root.put("pages", ps)
        val text = root.toString()
        Bg.run {
            val tmp = File(c.filesDir, "layout.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(c.filesDir, "layout.json"))
        }
    }

    companion object {
        private fun strs(a: JSONArray?) = ArrayList<String>().apply {
            if (a != null) for (i in 0 until a.length()) add(a.getString(i))
        }

        fun load(c: Context): Layout? = try {
            val r = JSONObject(File(c.filesDir, "layout.json").readText())
            val pages = ArrayList<MutableList<Item>>()
            val ps = r.getJSONArray("pages")
            for (i in 0 until ps.length()) {
                val a = ps.getJSONArray(i)
                val p = ArrayList<Item>()
                for (j in 0 until a.length()) {
                    val o = a.getJSONObject(j)
                    p.add(
                        when (o.getString("t")) {
                            "a" -> Item.App(o.getString("k"))
                            "f" -> Item.Folder(o.getString("n"), strs(o.getJSONArray("a")))
                            else -> Item.Widget(o.getString("kind"), o.getInt("id"), o.getInt("x"), o.getInt("y"))
                        }
                    )
                }
                pages.add(p)
            }
            Layout(pages, strs(r.getJSONArray("dock")).map { Item.App(it) }.toMutableList(), strs(r.optJSONArray("removed")).toMutableSet())
        } catch (_: Exception) {
            null
        }

        private val seedDock = listOf("com.tencent.mm", "com.server.auditor.ssh.client", "com.android.chrome", "com.netease.uuremote")
        private val seedApps = listOf(
            "com.eg.android.AlipayGphone", "com.tencent.mobileqq", "com.xingin.xhs", "com.zui.gallery",
            "com.zui.camera", "com.zui.calendar", "app.weatherpad", "com.android.settings",
        )
        private val seedFolders = linkedMapOf(
            "社交" to listOf("com.tencent.weread", "tv.danmaku.bili", "com.twitter.android", "com.instagram.android", "com.instagram.barcelona", "org.telegram.messenger"),
            "影音" to listOf("com.google.android.youtube", "com.netease.party", "com.facebook.aura"),
            "远程" to listOf("com.netease.uuremote", "com.orbitlink.android", "dev.tty7.pad", "com.android.virtualization.terminal", "top.songya.y700ubuntu", "top.songya.y700proc", "app.radif.pad"),
            "工作" to listOf("ai.x.grok", "com.google.android.apps.bard", "com.openai.chatgpt", "com.larksuite.suite", "ai.usemagpie.pad", "cn.wps.moffice_eng"),
            "工具" to listOf("com.zui.calculator", "com.zui.contacts", "com.zui.notes", "com.zui.safecenter", "com.zui.filemanager", "com.zui.recorder", "com.zui.deskclock", "com.tencent.wetype", "app.msime.android", "com.google.android.apps.authenticator2", "com.xiaomi.smarthome"),
            "联想" to listOf("com.lenovo.legionzone", "com.lenovo.xbb", "com.lenovo.hybridworkspace", "com.motorola.mobiledesktop"),
            "Google" to listOf("com.google.android.gm", "com.google.android.googlequicksearchbox", "com.android.vending"),
            "Root" to listOf("com.reveny.nativecheck", "io.github.vvb2060.keyattestation", "com.byxiaorun.detector"),
        )

        /** First run: keeps the arrangement the owner already had in their previous launcher. */
        fun seed(apps: Map<String, AppInfo>): Layout {
            val byPkg = apps.values.groupBy { it.pkg }
            val used = HashSet<String>()
            val dock = ArrayList<Item.App>()
            for (p in seedDock) byPkg[p]?.firstOrNull()?.let { dock.add(Item.App(it.key)); used.add(it.key) }
            val main = ArrayList<Item>()
            for (p in seedApps) byPkg[p]?.forEach { if (used.add(it.key)) main.add(Item.App(it.key)) }
            for ((name, pkgs) in seedFolders) {
                val keys = pkgs.flatMap { byPkg[it].orEmpty() }.map { it.key }.filter { used.add(it) }
                if (keys.size > 1) main.add(Item.Folder(name, keys.toMutableList()))
                else keys.forEach { main.add(Item.App(it)) }
            }
            for (k in apps.keys) if (used.add(k)) main.add(Item.App(k))
            val today: MutableList<Item> = arrayListOf(
                Item.Widget(K_CLOCK, 0, 2, 2), Item.Widget(K_CAL, 0, 2, 2), Item.Widget(K_BATT, 0, 2, 2),
            )
            return Layout(arrayListOf(today, main), dock, HashSet())
        }

        val weatherProvider = ComponentName("app.weatherpad", "app.weatherpad.widget.MediumWidgetReceiver")
    }
}
