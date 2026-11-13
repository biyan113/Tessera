package app.tessera.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.format.Formatter

/**
 * Settings-provider access that tries the public API first and falls back to the root helper,
 * so each key works with whichever of "permission granted" or "daemon running" happens to hold.
 */
object Sys {
    lateinit var app: Context
    /** Fired when a root-fetched value arrives late and differs from what rows were drawn with. */
    var onChange: (() -> Unit)? = null
    private val viaRoot = HashMap<String, String?>()
    private val asking = HashSet<String>()

    private fun direct(table: String, key: String): String? = when (table) {
        "system" -> Settings.System.getString(app.contentResolver, key)
        "secure" -> Settings.Secure.getString(app.contentResolver, key)
        else -> Settings.Global.getString(app.contentResolver, key)
    }

    /**
     * Null means "not readable right now". Vendor keys are hidden from third-party apps, so an
     * unreadable or missing value is re-asked through root; the answer shows up on the next refresh.
     */
    fun get(table: String, key: String): String? {
        val v = try { direct(table, key) } catch (_: Exception) { null }
        if (v != null) return v
        val k = "$table/$key"
        if (asking.add(k)) Root.async("settings get $table $key") { out ->
            asking.remove(k)
            val r = out?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            if (!viaRoot.containsKey(k) || viaRoot[k] != r) { viaRoot[k] = r; onChange?.invoke() }
        }
        return viaRoot[k]
    }

    fun int(table: String, key: String): Int? = get(table, key)?.toFloatOrNull()?.toInt()

    fun put(table: String, key: String, value: String, done: (Boolean) -> Unit = {}) {
        val ok = try {
            when (table) {
                "system" -> Settings.System.putString(app.contentResolver, key, value)
                "secure" -> Settings.Secure.putString(app.contentResolver, key, value)
                else -> Settings.Global.putString(app.contentResolver, key, value)
            }
        } catch (_: Exception) { false }
        if (ok) { done(true); return }
        Root.run("settings put $table $key ${Root.quote(value)}") {
            if (it) viaRoot["$table/$key"] = value
            done(it)
        }
    }

    fun size(bytes: Long): String = Formatter.formatShortFileSize(app, bytes)
}

fun Context.resolves(i: Intent) = try {
    packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null
} catch (_: Exception) { false }

/** Vendor settings screens come and go, and some refuse outside callers; neither may crash us. */
fun Activity.open(i: Intent): Boolean = try { startActivity(i); true } catch (_: Exception) { false }
