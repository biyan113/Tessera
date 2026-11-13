package app.homepad

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Reads new notifications aloud, briefly: "微信，张三：晚上吃什么". The settings app writes the
 * choices to /data/adb/tessera/speak through tesserad, and we read them back the same way:
 *   line 1  "1" on, anything else off
 *   line 2  "brief" (app and title only) or "full" (plus the text)
 *   "-pkg"  an app that is never read
 * It lives in the launcher because ZUI's autostart manager keeps the system from starting other
 * processes to bind a listener, while home is always running. The speech engine is created on the
 * first notification worth reading and released after a minute of quiet, so idle costs nothing.
 */
object Speaker {
    private const val CONFIG = "/data/adb/tessera/speak"
    private const val MAX_TEXT = 40
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class Config(val on: Boolean, val full: Boolean, val muted: Set<String>)
    @Volatile private var config: Config? = null
    @Volatile private var readAt = 0L

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    /** Last thing read per notification key, so progress updates and reposts stay silent. */
    private val spoken = LinkedHashMap<String, String>()
    private val release = Runnable { tts?.shutdown(); tts = null; ready = false }

    private fun load(): Config {
        val now = System.currentTimeMillis()
        config?.let { if (now - readAt < 10_000) return it }
        val lines = root("cat $CONFIG 2>/dev/null")?.lines().orEmpty()
        val c = Config(
            on = lines.getOrNull(0)?.trim() == "1",
            full = lines.getOrNull(1)?.trim() != "brief",
            muted = lines.filter { it.startsWith("-") }.map { it.substring(1).trim() }.toSet(),
        )
        config = c
        readAt = now
        return c
    }

    /** One command to tesserad, the root helper; null when it is not running. */
    private fun root(cmd: String): String? = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress("tesserad", LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = 4000
            s.outputStream.write((cmd + "\n").toByteArray())
            s.shutdownOutput()
            s.inputStream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (_: Exception) { null }

    fun posted(s: NotificationListenerService, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?) {
        val n = sbn.notification ?: return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.packageName in setOf("android", "com.android.systemui", s.packageName)) return
        // Only notifications that would make a sound: low-priority and silenced channels stay quiet.
        val r = NotificationListenerService.Ranking()
        if (ranking?.getRanking(sbn.key, r) == true && (r.importance < NotificationManager.IMPORTANCE_DEFAULT || r.isSuspended)) return
        if (s.currentInterruptionFilter > NotificationListenerService.INTERRUPTION_FILTER_ALL) return
        val am = s.getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val ctx = s.applicationContext
        io.execute {
            val c = load()
            if (!c.on || sbn.packageName in c.muted) return@execute
            val line = phrase(ctx, sbn, c.full) ?: return@execute
            main.post {
                val before = spoken[sbn.key]
                if (before == line) return@post
                if (before != null && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return@post
                spoken[sbn.key] = line
                if (spoken.size > 200) spoken.remove(spoken.keys.first())
                say(ctx, line)
            }
        }
    }

    private fun phrase(c: Context, sbn: StatusBarNotification, full: Boolean): String? {
        val x = sbn.notification.extras
        val app = try {
            c.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }
        } catch (_: Exception) { sbn.packageName }
        var title = clean(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE))
        var text = clean(x.getCharSequence(Notification.EXTRA_TEXT) ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT))
        // Chat apps: the newest message carries its own sender.
        @Suppress("DEPRECATION")
        val msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(msgs).lastOrNull()
            if (last != null) {
                val who = clean(last.senderPerson?.name ?: last.sender)
                text = clean(last.text)
                if (who.isNotEmpty() && who != title) text = "$who：$text"
            }
        }
        if (title == app) title = ""
        // "张三: 晚上吃什么" under the title "张三" would name the sender twice.
        if (title.isNotEmpty()) text = text.removePrefix("$title:").removePrefix("$title：").trim()
        if (text.length > MAX_TEXT) text = text.take(MAX_TEXT) + "等"
        val head = if (title.isEmpty()) app else "$app，$title"
        return when {
            title.isEmpty() && text.isEmpty() -> null
            !full || text.isEmpty() -> head
            else -> "$head：$text"
        }
    }

    /** Strips what is noise when spoken: counters like "[3条]", links, and runs of whitespace. */
    private fun clean(s: CharSequence?): String = (s ?: "").toString()
        .replace(Regex("^\\[\\d+条]\\s*"), "")
        .replace(Regex("https?://\\S+"), "链接")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun say(c: Context, line: String) {
        main.removeCallbacks(release)
        main.postDelayed(release, 60_000)
        val t = tts
        if (t != null && ready) { speak(t, line); return }
        if (pending.size < 3) pending += line
        if (t != null) return
        tts = TextToSpeech(c) { status ->
            val e = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { pending.clear(); e.shutdown(); tts = null; return@TextToSpeech }
            e.language = Locale.CHINA
            e.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            ready = true
            pending.forEach { speak(e, it) }
            pending.clear()
        }
    }

    private var seq = 0
    private fun speak(t: TextToSpeech, line: String) {
        t.speak(line, TextToSpeech.QUEUE_ADD, null, "n${seq++}")
    }
}
