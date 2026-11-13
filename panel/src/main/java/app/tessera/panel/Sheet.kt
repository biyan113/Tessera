package app.tessera.panel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.window.OnBackInvokedDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Talks to tesserad, the root helper, for the switches an ordinary app is not allowed to flip. */
object Root {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val latest = AtomicReference<String?>()

    fun sh(cmd: String): String? = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress("tesserad", LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = 8000
            s.outputStream.write(cmd.toByteArray())
            s.outputStream.flush()
            s.shutdownOutput()
            s.inputStream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    fun run(cmd: String, done: ((String?) -> Unit)? = null) {
        pool.execute {
            val r = sh(cmd)
            if (done != null) main.post { done(r) }
        }
    }

    /** For a slider being dragged: commands that pile up behind a slow one are dropped, only the newest runs. */
    fun runLatest(cmd: String) {
        if (latest.getAndSet(cmd) == null) pool.execute {
            // Read the value at run time, not at queue time.
            latest.getAndSet(null)?.let { sh(it) }
        }
    }
}

/** Counts nothing and stores nothing: it only hands the live notification list to the panel. */
class NoticeService : NotificationListenerService() {
    override fun onListenerConnected() { live = this; changed?.invoke() }
    override fun onListenerDisconnected() { live = null }
    override fun onNotificationPosted(n: StatusBarNotification?) { changed?.invoke() }
    override fun onNotificationRemoved(n: StatusBarNotification?) { changed?.invoke() }

    companion object {
        @Volatile var live: NoticeService? = null
        var changed: (() -> Unit)? = null
    }
}

/**
 * A see-through activity over whatever is on screen. The compositor blurs what lies behind it,
 * and the blur, dim and content all follow one 0..1 progress value.
 */
abstract class SheetActivity : Activity() {
    var shown = 0f
        private set
    private var anim: ValueAnimator? = null
    private var closing = false
    protected open val blur = 190f

    abstract fun onProgress(p: Float)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setDecorFitsSystemWindows(false)
        window.setBackgroundDrawable(ColorDrawable(0))
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        // The controller only exists once the decor view does.
        window.decorView.windowInsetsController?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close() }
    }

    override fun onResume() {
        super.onResume()
        closing = false
        animateTo(1f, 300)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        closing = false
        animateTo(1f, 300)
    }

    /** Leaving any other way (home gesture, another app on top) must not strand a stale sheet in the back stack. */
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finishNow()
    }

    private fun apply(p: Float) {
        shown = p
        window.setBackgroundBlurRadius((p * blur * resources.displayMetrics.density / 2.75f).toInt())
        onProgress(p)
    }

    private fun animateTo(target: Float, ms: Long, end: (() -> Unit)? = null) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { apply(it.animatedValue as Float) }
            if (end != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = end()
            })
            start()
        }
    }

    fun close(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        animateTo(0f, 200) { finishNow(); then?.invoke() }
    }

    private fun finishNow() {
        anim?.cancel()
        finish()
        overridePendingTransition(0, 0)
    }
}
