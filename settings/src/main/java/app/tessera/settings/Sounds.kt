package app.tessera.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/** A notification sound in the media library. */
class Tone(val uri: String, val title: String)

/** One of an app's notification channels, as tesserad reports it. */
class Channel(val id: String, val importance: Int, val sound: String, val eligible: Boolean, val name: String)

/**
 * Per-app notification sounds. Reading and writing another app's channels is system-only, so both
 * go through tesserad ("tessera sound" commands, tab-separated); the sounds themselves come from
 * the media library, where imported files land in Notifications/ like any ringtone app's would.
 */
object Tones {
    const val DEFAULT = "content://settings/system/notification_sound"
    const val NONE = "none"
    private const val READ = "android.permission.READ_MEDIA_AUDIO"

    var list: List<Tone> = emptyList()
        private set
    private var titles = HashMap<String, String>()
    private var playing: Ringtone? = null

    fun cmd(vararg args: String) = (listOf("tessera sound") + args).joinToString("\t")

    fun name(uri: String?): String = when (uri) {
        null, "-" -> "—"
        "*" -> "多种"
        DEFAULT -> "默认"
        NONE -> "无"
        else -> titles[uri] ?: titles[uri.replace("/external_primary/", "/external/")] ?: "其他"
    }

    /**
     * Built-in sounds are always readable; imported ones live on shared storage, which needs the
     * audio permission. Root grants it once rather than prompting for a setting the user is already in.
     */
    fun load(c: Context, cb: () -> Unit) {
        val app = c.applicationContext
        Ui.bg {
            if (app.checkSelfPermission(READ) != PackageManager.PERMISSION_GRANTED) {
                Root.sh("pm grant ${app.packageName} $READ")
            }
            val out = ArrayList<Tone>()
            val map = HashMap<String, String>()
            for (vol in listOf(MediaStore.VOLUME_INTERNAL, MediaStore.VOLUME_EXTERNAL)) {
                val base = MediaStore.Audio.Media.getContentUri(vol)
                try {
                    app.contentResolver.query(
                        base, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE),
                        "${MediaStore.Audio.Media.IS_NOTIFICATION}=1", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
                    )?.use { k ->
                        while (k.moveToNext()) {
                            val t = Tone(Uri.withAppendedPath(base, k.getLong(0).toString()).toString(), k.getString(1) ?: "?")
                            out += t
                            map[t.uri] = t.title
                        }
                    }
                } catch (_: Exception) {}
            }
            Ui.ui { list = out; titles = map; cb() }
        }
    }

    /** Plays [uri] the way a notification would, cutting off whatever preview was still playing. */
    fun preview(c: Context, uri: String) {
        stop()
        if (uri == NONE) return
        playing = try {
            RingtoneManager.getRingtone(c, Uri.parse(uri))?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        } catch (_: Exception) { null }
    }

    fun stop() { runCatching { playing?.stop() }; playing = null }

    /**
     * Copies a picked audio file into Notifications/ through MediaStore, which needs no permission
     * for files we contribute ourselves. Returns the new sound's uri, or null.
     */
    fun import(c: Context, src: Uri): String? {
        val cr = c.contentResolver
        var name = "提示音"
        try {
            cr.query(src, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        } catch (_: Exception) {}
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, name.substringBeforeLast('.'))
            put(MediaStore.Audio.Media.MIME_TYPE, cr.getType(src) ?: "audio/*")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Notifications/")
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val dst = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        return try {
            cr.openInputStream(src)!!.use { i -> cr.openOutputStream(dst)!!.use { o -> i.copyTo(o) } }
            cr.update(dst, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            // Channels store what the media provider calls the file, so ask it rather than trust our insert uri.
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL).buildUpon()
                .appendPath(dst.lastPathSegment).build().toString()
        } catch (_: Exception) {
            runCatching { cr.delete(dst, null, null) }
            null
        }
    }

    fun channels(out: String?): List<Channel>? = out?.takeIf { !it.startsWith("error") && !it.startsWith("?") }?.lines()?.mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 6) null else Channel(f[0], f[1].toIntOrNull() ?: 0, f[2], f[4] == "1", f[5])
    }
}

/**
 * The shared picker body: default, silence, every library sound, and import. [current] is
 * re-read on refresh so the check mark follows the change; [pick] receives the chosen uri.
 */
private fun Page.tonePicker(header: String, footer: String?, current: () -> String?, pick: (String) -> Unit) {
    card(header, footer) {
        choice("默认", { current() == Tones.DEFAULT }) { pick(Tones.DEFAULT) }
        choice("无", { current() == Tones.NONE }) { pick(Tones.NONE) }
        for (t in Tones.list) choice(t.title, { current() == t.uri }) { pick(t.uri) }
    }
    card(footer = "文件会复制到“Notifications”文件夹，之后在所有 App 里都能选。") {
        row("导入音频…").apply {
            tone = { Theme.tint }
            onTap = {
                act.pickAudio { src ->
                    Ui.bg {
                        val uri = Tones.import(act, src)
                        Ui.ui {
                            if (uri == null) return@ui
                            Tones.load(act) { rebuild(); pick(uri) }
                        }
                    }
                }
            }
        }
    }
}

/** 声音 › App 通知声音: every app that has a channel which can ring, with its current sound. */
fun appSoundsPage(act: SettingsActivity): Page {
    var apps: List<AppEntry>? = null
    var sums: Map<String, String>? = null
    lateinit var page: Page
    fun summarize() {
        val list = apps ?: return
        Root.async(Tones.cmd("sum", *list.map { it.pkg }.toTypedArray()), 15000) { out ->
            sums = out?.lines()?.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: emptyMap()
            page.rebuild()
        }
    }
    page = Page(act, "App 通知声音") {
        val list = apps
        val s = sums
        if (list == null || s == null) { card { row("正在载入…").tone = { Theme.secondary } }; return@Page }
        if (s.isEmpty()) { card { row("需要 Tessera 后台服务").tone = { Theme.secondary } }; return@Page }
        card(footer = "只列出已经建立过通知类别的 App。还没发过通知的 App 会在发出第一条通知后出现。") {
            for (a in list) {
                val v = s[a.pkg] ?: continue
                if (v == "-") continue
                row(a.label, Tones.name(v), Acc.CHEVRON) { act.detail.push(appSoundPage(act, a)) }.app = a
            }
        }
    }
    // Back from an app's page lands here with its new sound; reload once per return.
    page.onShow = { if (apps != null && sums != null) summarize() }
    Tones.load(act) { Apps.load(act) { apps = it; summarize() } }
    return page
}

/** One app: an app-wide sound plus per-channel overrides. */
fun appSoundPage(act: SettingsActivity, a: AppEntry): Page {
    var chans: List<Channel>? = null
    lateinit var page: Page
    fun reload() = Root.async(Tones.cmd("list", a.pkg)) { out ->
        val old = chans
        chans = Tones.channels(out) ?: emptyList()
        if (old == null || old.map { it.id } != chans!!.map { it.id }) page.rebuild() else page.refresh()
    }
    /** The sound shared by every channel the app-wide choice covers, or "*" when they differ. */
    fun common(): String? {
        val e = chans?.filter { it.eligible } ?: return null
        return e.map { it.sound }.distinct().let { if (it.size == 1) it[0] else "*" }
    }
    page = Page(act, a.label) {
        val cs = chans
        if (cs == null) { card { row("正在载入…").tone = { Theme.secondary } }; return@Page }
        tonePicker("提示音", "用于这个 App 所有会响铃的通知，之后新建的通知类别也会自动跟着改。单独设置过的类别保持不变。", ::common) { uri ->
            Tones.preview(act, uri)
            Root.async(Tones.cmd("set", a.pkg, "*", uri)) { reload() }
        }
        val ringing = cs.filter { it.importance >= 3 }
        if (ringing.isNotEmpty()) card("通知类别", "这些类别会发出声音，可以分别设置。") {
            for (c in ringing) nav(c.name, { Tones.name(chans?.firstOrNull { it.id == c.id }?.sound) }) {
                channelSoundPage(act, a, c.id, c.name) { reload() }
            }
        }
    }
    page.onShow = { if (chans != null) reload() }
    reload()
    return page
}

fun channelSoundPage(act: SettingsActivity, a: AppEntry, id: String, name: String, changed: () -> Unit): Page {
    var sound: String? = null
    lateinit var page: Page
    fun reload() = Root.async(Tones.cmd("list", a.pkg)) { out ->
        sound = Tones.channels(out)?.firstOrNull { it.id == id }?.sound
        page.refresh()
    }
    page = Page(act, name) {
        tonePicker("提示音", "${a.label} · $name", { sound }) { uri ->
            Tones.preview(act, uri)
            Root.async(Tones.cmd("set", a.pkg, id, uri)) { reload(); changed() }
        }
    }
    reload()
    return page
}
