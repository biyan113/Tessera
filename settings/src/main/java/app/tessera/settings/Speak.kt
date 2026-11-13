package app.tessera.settings

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 通知 › 朗读通知. The reading itself happens in the launcher's notification listener (app.homepad Speaker); this page only
 * edits /data/adb/tessera/speak (format documented there) through tesserad.
 */
private const val SPEAK = "/data/adb/tessera/speak"
private const val GOOGLE_TTS = "com.google.android.tts"

fun speakPage(act: SettingsActivity): Page {
    var loaded = false
    var on = false
    var full = true
    val muted = LinkedHashSet<String>()
    var apps: List<AppEntry>? = null
    lateinit var page: Page

    fun hasEngine() = act.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()

    fun save(done: (Boolean) -> Unit = {}) {
        val body = buildString {
            append(if (on) "1" else "0").append('\n')
            append(if (full) "full" else "brief").append('\n')
            muted.forEach { append('-').append(it).append('\n') }
        }
        Root.run("printf '%s' ${Root.quote(body)} > $SPEAK", cb = done)
    }

    page = Page(act, "朗读通知") {
        if (!loaded) { card { row("正在载入…").tone = { Theme.secondary } }; return@Page }
        card(footer = "新通知到达时用语音简短读出。静音、勿扰模式下不读，不会响铃的通知也不读。") {
            toggle("朗读通知", { on }) { v, done -> on = v; save(done) }
        }
        if (!hasEngine()) {
            card("语音引擎", "这台设备还没有语音引擎。安装「Google 语音服务」后就能朗读中文，装好回到这里即可。") {
                row("安装 Google 语音服务").apply {
                    tone = { Theme.tint }
                    onTap = {
                        act.open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_TTS")))
                            || act.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_TTS")))
                    }
                }
            }
        } else {
            card {
                row("试听").apply { tone = { Theme.tint }; onTap = { sample(act, if (full) "微信，张三：晚上一起吃饭吗" else "微信，张三") } }
                link("语音引擎设置", Intent("com.android.settings.TTS_SETTINGS"))
            }
        }
        card("朗读内容", "内容超过 40 个字会截断。") {
            choice("App 和标题", { !full }) { full = false; save(); page.refresh() }
            choice("App、标题和内容", { full }) { full = true; save(); page.refresh() }
        }
        val list = apps
        if (list != null) card("App", "关闭后该 App 的通知不朗读。") {
            for (a in list) toggle(a.label, { a.pkg !in muted }) { v, done ->
                if (v) muted -= a.pkg else muted += a.pkg
                save(done)
            }.app = a
        }
    }
    // Rebuild when an engine appears (the user comes back from the Play Store).
    page.sig = { hasEngine() }
    Root.async("cat $SPEAK 2>/dev/null") { out ->
        val lines = out?.lines().orEmpty()
        on = lines.getOrNull(0)?.trim() == "1"
        full = lines.getOrNull(1)?.trim() != "brief"
        lines.filter { it.startsWith("-") }.mapTo(muted) { it.substring(1).trim() }
        loaded = true
        page.rebuild()
    }
    Apps.load(act) { apps = it; if (loaded) page.rebuild() }
    return page
}

/** Speaks [line] once with the default engine and releases it when done. */
private fun sample(act: SettingsActivity, line: String) {
    var t: TextToSpeech? = null
    t = TextToSpeech(act.applicationContext) { status ->
        val e = t ?: return@TextToSpeech
        if (status != TextToSpeech.SUCCESS) { e.shutdown(); return@TextToSpeech }
        e.language = Locale.CHINA
        e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { Ui.ui { e.shutdown() } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { Ui.ui { e.shutdown() } }
        })
        e.speak(line, TextToSpeech.QUEUE_FLUSH, null, "sample")
    }
}
