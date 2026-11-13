package app.tessera.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.LruCache
import java.text.Collator
import java.util.Locale

class AppEntry(val pkg: String, val label: String, val info: ActivityInfo)

/** Launcher apps and their icons, both produced off the main thread. */
object Apps {
    /** Icons are only row-sized (29dp), so a few MB holds every app that can be on screen plus plenty of scrollback. */
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(k: String, v: Bitmap) = v.allocationByteCount
    }
    private val waiting = HashMap<String, ArrayList<() -> Unit>>()
    private val broken = HashSet<String>()

    fun load(c: Context, cb: (List<AppEntry>) -> Unit) {
        val pm = c.applicationContext.packageManager
        Ui.bg {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val collator = Collator.getInstance(Locale.CHINA)
            val list = pm.queryIntentActivities(main, 0)
                .distinctBy { it.activityInfo.packageName }
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString().trim(), it.activityInfo) }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
            Ui.ui { cb(list) }
        }
    }

    /** Returns the cached icon, or null after queueing a render; [ready] fires once it lands. */
    fun icon(c: Context, a: AppEntry, ready: () -> Unit): Bitmap? {
        cache.get(a.pkg)?.let { return it }
        if (a.pkg in broken) return null
        waiting[a.pkg]?.let { it += ready; return null }
        waiting[a.pkg] = arrayListOf(ready)
        val pm = c.applicationContext.packageManager
        Ui.bg {
            val bmp = try { render(a, pm) } catch (_: Throwable) { null }
            Ui.ui {
                val cbs = waiting.remove(a.pkg)
                // Remembered, so an icon that cannot be drawn is not re-attempted on every frame.
                if (bmp == null) broken += a.pkg else { cache.put(a.pkg, bmp); cbs?.forEach { it() } }
            }
        }
        return null
    }

    /** iOS-style rounded square; adaptive layers are drawn at their full 108/72 bleed so the mask is ours. */
    private fun render(a: AppEntry, pm: android.content.pm.PackageManager): Bitmap {
        val s = dp(29)
        val d = a.info.loadUnbadgedIcon(pm)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = s * 0.225f
        c.clipPath(Path().apply { addRoundRect(0f, 0f, s.toFloat(), s.toFloat(), r, r, Path.Direction.CW) })
        if (d is AdaptiveIconDrawable) {
            val e = s / 4
            for (layer in listOf(d.background, d.foreground)) {
                layer?.setBounds(-e, -e, s + e, s + e)
                layer?.draw(c)
            }
        } else {
            d.setBounds(0, 0, s, s)
            d.draw(c)
        }
        return out
    }
}
