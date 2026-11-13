package app.tessera.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * iOS-style alert drawn inside the activity's own view tree, so it follows our palette and
 * needs no dialog window. One instance at a time; the activity routes Back to [dismiss].
 */
@SuppressLint("ViewConstructor")
class Alert private constructor(
    private val act: SettingsActivity,
    title: String,
    message: String?,
    hint: String?,
    secret: Boolean,
    ok: String,
    destructive: Boolean,
    cancel: String?,
    private val onOk: (String) -> Unit,
) : FrameLayout(act), Themed {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var field: EditText? = null
    private val texts = ArrayList<TextView>()

    private val card = object : LinearLayout(act) {
        init { orientation = VERTICAL; setWillNotDraw(false) }
        override fun onDraw(c: Canvas) {
            p.color = Theme.alert
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(14f), dp(14f), p)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true   // swallow touches meant for the panes underneath
        fun text(s: String, size: Float, face: Typeface, top: Int) = card.addView(TextView(act).apply {
            text = s
            gravity = Gravity.CENTER
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size))
            setPadding(dp(16), top, dp(16), 0)
            texts += this
        })
        text(title, 17f, Ui.semibold, dp(19))
        if (message != null) text(message, 13f, Typeface.DEFAULT, dp(3))
        if (hint != null) {
            val e = EditText(act).apply {
                this.hint = hint
                setSingleLine()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(15f))
                inputType = InputType.TYPE_CLASS_TEXT or
                    (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                background = object : android.graphics.drawable.ColorDrawable() {
                    override fun draw(c: Canvas) {
                        p.color = Theme.field
                        c.drawRoundRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), dp(7f), dp(7f), p)
                    }
                }
                setPadding(dp(8), dp(7), dp(8), dp(7))
                setOnEditorActionListener { _, _, _ -> finish(true); true }
            }
            field = e
            texts += e
            card.addView(e, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(14), dp(16), 0)
            })
        }
        card.addView(Buttons(act, cancel, ok, destructive) { finish(it) }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(18) })
        addView(card, LayoutParams(dp(270), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        restyle()
    }

    override fun restyle() {
        texts.forEach { it.setTextColor(Theme.text) }
        field?.setHintTextColor(Theme.secondary)
    }

    override fun onDraw(c: Canvas) = c.drawColor(Theme.dim)

    private fun finish(confirmed: Boolean) {
        val typed = field?.text?.toString() ?: ""
        dismiss()
        if (confirmed) onOk(typed)
    }

    fun dismiss() {
        if (act.alert !== this) return
        act.alert = null
        field?.let { act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        animate().alpha(0f).setDuration(150).withEndAction { (parent as? FrameLayout)?.removeView(this) }.start()
        act.syncBack()
    }

    private fun present() {
        act.alert?.dismiss()
        act.alert = this
        act.root.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        alpha = 0f
        card.scaleX = 1.12f; card.scaleY = 1.12f
        animate().alpha(1f).setDuration(180).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        field?.let { e ->
            e.requestFocus()
            e.post { act.getSystemService(InputMethodManager::class.java).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT) }
        }
        act.syncBack()
    }

    /** The button strip: hairlines above and between, the confirming action in semibold. */
    @SuppressLint("ViewConstructor")
    private class Buttons(
        c: Context, private val cancel: String?, private val ok: String,
        private val destructive: Boolean, private val done: (Boolean) -> Unit,
    ) : View(c) {
        private val p = Paint()
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(17f); textAlign = Paint.Align.CENTER }
        private var down = -1

        private fun slot(x: Float) = if (cancel == null) 1 else if (x < width / 2f) 0 else 1

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val split = if (cancel == null) 0f else w / 2
            p.color = Theme.pressed
            if (down == 0) c.drawRect(0f, 0f, split, h, p)
            if (down == 1) c.drawRect(split, 0f, w, h, p)
            p.color = Theme.separator
            c.drawRect(0f, 0f, w, 1f, p)
            if (cancel != null) {
                c.drawRect(split - 0.5f, 0f, split + 0.5f, h, p)
                tp.color = Theme.tint; tp.typeface = Typeface.DEFAULT
                c.drawText(cancel, split / 2, base(tp, h / 2), tp)
            }
            tp.color = if (destructive) Theme.red else Theme.tint
            tp.typeface = if (destructive) Typeface.DEFAULT else Ui.semibold
            c.drawText(ok, (split + w) / 2, base(tp, h / 2), tp)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { down = slot(e.x); invalidate() }
                MotionEvent.ACTION_UP -> { val s = slot(e.x); down = -1; invalidate(); done(s == 1) }
                MotionEvent.ACTION_CANCEL -> { down = -1; invalidate() }
            }
            return true
        }
    }

    companion object {
        fun show(
            act: SettingsActivity, title: String, message: String? = null, hint: String? = null, secret: Boolean = false,
            ok: String = "好", destructive: Boolean = false, cancel: String? = "取消", onOk: (String) -> Unit = {},
        ) = Alert(act, title, message, hint, secret, ok, destructive, cancel, onOk).present()
    }
}
