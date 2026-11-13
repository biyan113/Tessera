package app.tessera.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * One screen of the detail pane, described by a small builder: headers, cards, rows.
 * [body] may run many times: list-like panes simply rebuild when [sig] says the world changed,
 * while fixed panes only re-run their [bind]ings so switches and sliders keep their animation state.
 */
class Page(val act: SettingsActivity, val title: String, private val body: Page.() -> Unit) {
    val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val binds = ArrayList<() -> Unit>()
    private var card: Card? = null
    private var afterHeader = false
    private var lastSig: Any? = null
    private var built = false

    /** Snapshot of whatever decides the page's structure; a changed value triggers a rebuild. */
    var sig: (() -> Any?)? = null
    /** Runs whenever the page comes to the front or the app resumes: the place to start scans. */
    var onShow: (() -> Unit)? = null

    fun rebuild() {
        content.removeAllViews()
        binds.clear()
        card = null
        afterHeader = false
        body()
        built = true
        lastSig = sig?.invoke()
        binds.forEach { it() }
    }

    fun refresh() {
        if (!built) return
        val s = sig
        if (s != null && s() != lastSig) rebuild() else binds.forEach { it() }
    }

    fun bind(f: () -> Unit) { binds += f }

    private fun lp(left: Int, top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        setMargins(dp(left), dp(top), dp(left), 0)
    }

    fun header(text: String) {
        content.addView(Label(act, text), lp(32, if (content.childCount == 0) 8 else 26))
        afterHeader = true
    }

    fun footer(text: String) {
        content.addView(Label(act, text), lp(32, 7))
    }

    /** A card that ends up empty (every link unresolvable, say) vanishes together with its header. */
    fun card(header: String? = null, footer: String? = null, fill: () -> Unit) {
        val c = Card(act, false)
        card = c
        fill()
        card = null
        if (c.childCount == 0) return
        header?.let { header(it) }
        content.addView(c, lp(16, if (afterHeader) 7 else if (content.childCount == 0) 8 else 30))
        afterHeader = false
        footer?.let { footer(it) }
    }

    fun add(v: View) = card!!.addView(v, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun row(title: String, value: String? = null, acc: Acc = Acc.NONE, tap: (() -> Unit)? = null): Row {
        val r = Row(act, title)
        r.value = value
        r.acc = acc
        r.onTap = tap
        add(r)
        return r
    }

    /** A read-only fact whose value is re-read on every refresh. */
    fun info(title: String, value: () -> String?): Row {
        val r = row(title)
        bind { r.value = value() ?: "—" }
        return r
    }

    /** Drills into a nested page; [value] shows the current choice next to the chevron. */
    fun nav(title: String, value: (() -> String?)? = null, page: () -> Page): Row {
        val r = row(title, acc = Acc.CHEVRON) { act.detail.push(page()) }
        if (value != null) bind { r.value = value() }
        return r
    }

    /** Hands off to a system screen. Resolved first, so screens this ROM lacks leave no dead row. */
    fun link(title: String, intent: Intent, value: (() -> String?)? = null): Row? {
        if (!act.resolves(intent)) return null
        val r = row(title, acc = Acc.CHEVRON) { act.open(intent) }
        if (value != null) bind { r.value = value() }
        return r
    }

    fun link(title: String, action: String) = link(title, Intent(action))

    fun toggle(title: String, read: () -> Boolean?, write: (Boolean, (Boolean) -> Unit) -> Unit): Row {
        val r = row(title)
        binds += bindToggle(r, read, write)
        return r
    }

    /**
     * Switch backed by a plain 0/1 settings key. [unset] is what a missing key means; leave it null
     * for vendor keys, where missing means "this ROM has no such feature" and the row goes inert.
     */
    fun toggle(title: String, table: String, key: String, unset: Boolean? = null) =
        toggle(title, { Sys.int(table, key)?.let { it != 0 } ?: unset }) { v, done -> Sys.put(table, key, if (v) "1" else "0", done) }

    /** Single-choice row; [chosen] is re-evaluated on refresh so the mark follows the setting. */
    fun choice(title: String, chosen: () -> Boolean, pick: () -> Unit): Row {
        val r = row(title, acc = Acc.CHECK, tap = pick)
        bind { r.setOn(chosen(), false); r.invalidate() }
        return r
    }
}

/** A page plus its own navigation bar, so the two slide as one. */
@SuppressLint("ViewConstructor")
class PageView(val page: Page, back: String?, onBack: () -> Unit) : LinearLayout(page.act) {
    private val bar = NavBar(context, page.title, back, onBack)
    private val scroll = ScrollView(context).apply {
        clipToPadding = false
        isVerticalScrollBarEnabled = false
        setOnScrollChangeListener { _, _, y, _, _ -> bar.lifted = y > 0 }
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        isClickable = true   // a page sliding in must not leak touches to the one beneath
        addView(bar)
        scroll.addView(page.content)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        inset()
    }

    fun inset() = scroll.setPadding(0, 0, 0, Ui.bottom + dp(28))

    /** Opaque, so it covers the page it slides over. */
    override fun onDraw(c: Canvas) = c.drawColor(Theme.bg)
}

/** Right-hand column: a stack of pages that push and pop with the iOS horizontal slide. */
@SuppressLint("ViewConstructor")
class DetailPane(private val act: SettingsActivity) : FrameLayout(act) {
    private val stack = ArrayList<PageView>()
    private val p = Paint()
    private var busy = false
    private val ease = DecelerateInterpolator(1.8f)

    val depth get() = stack.size
    val top get() = stack.lastOrNull()?.page

    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) = c.drawColor(Theme.bg)

    /** The column divider is drawn over the pages so a sliding page cannot wipe it. */
    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        p.color = Theme.separator
        c.drawRect(0f, 0f, 1f, height.toFloat(), p)
    }

    private fun wrap(page: Page, back: String?) = PageView(page, back) { pop() }

    fun setRoot(page: Page) {
        stack.forEach { it.animate().cancel() }
        removeAllViews()
        stack.clear()
        busy = false
        page.rebuild()
        val v = wrap(page, null)
        stack += v
        addView(v)
        page.onShow?.invoke()
        act.syncBack()
    }

    fun push(page: Page) {
        val under = stack.lastOrNull() ?: return
        if (busy) return
        page.rebuild()
        val v = wrap(page, under.page.title)
        stack += v
        addView(v)
        page.onShow?.invoke()
        act.syncBack()
        val w = width.toFloat()
        if (w <= 0f) { under.visibility = INVISIBLE; return }
        busy = true
        v.translationX = w
        v.animate().translationX(0f).setDuration(350).setInterpolator(ease).withEndAction {
            busy = false
            under.visibility = INVISIBLE
            under.translationX = 0f
        }.start()
        under.animate().translationX(-w * 0.3f).setDuration(350).setInterpolator(ease).start()
    }

    fun pop(): Boolean {
        if (stack.size < 2) return false
        if (busy) return true
        val v = stack.removeAt(stack.size - 1)
        val under = stack.last()
        val w = width.toFloat()
        under.visibility = VISIBLE
        under.page.refresh()
        under.page.onShow?.invoke()
        act.syncBack()
        busy = true
        under.translationX = -w * 0.3f
        under.animate().translationX(0f).setDuration(350).setInterpolator(ease).start()
        v.animate().translationX(w).setDuration(350).setInterpolator(ease).withEndAction {
            busy = false
            removeView(v)
        }.start()
        return true
    }

    fun inset() = stack.forEach { it.inset() }
}
