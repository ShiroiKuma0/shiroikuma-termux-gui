package com.termux.gui.shiroikuma

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.termux.gui.R

/**
 * shiroikuma-termux-gui fork: the house look (verified against kxkb / denwa / arcanechat /
 * raikidoban) as plain view builders — black `#000000`, yellow ink `#FFFF00`, dim yellow `#C8C800`
 * for summaries, warning red `#FFFF5252`; never material yellow.
 *
 * Section heading: 20 sp bold yellow, a text-wide 2.5 dp underline 2 dp below, a 1 px full-width
 * hairline above every section but the first. Indent ladder 36 dp (section title) → 72 dp (rows
 * under a section). Rows: `minHeight 0`, 5 dp vertical padding, title 16 sp, summary 13 sp dim.
 * Dialogs: black, 2 dp yellow border, 16 dp radius, pill buttons (50 dp radius, 1.5 dp stroke).
 */
object ShiroikumaUi {

    const val BLACK = 0xFF000000.toInt()
    const val YELLOW = 0xFFFFFF00.toInt()
    const val DIM = 0xFFC8C800.toInt()
    const val WARN = 0xFFFF5252.toInt()
    const val SUMMARY_ALPHA = 0.78f

    const val INDENT_SECTION = 36
    const val INDENT_ROW = 72
    private const val ROW_PAD_V = 5
    private const val ROW_PAD_END = 16

    fun dp(context: Context, v: Float): Int = Math.round(v * context.resources.displayMetrics.density)
    fun dp(context: Context, v: Int): Int = dp(context, v.toFloat())

    // ---- text -----------------------------------------------------------------------------------

    fun text(context: Context, s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        AppCompatTextView(context).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun textWidth(label: TextView): Int {
        label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return label.measuredWidth
    }

    fun selectableBackground(context: Context): Drawable? {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getDrawable(context, tv.resourceId) else null
    }

    // ---- sections and rows ----------------------------------------------------------------------

    /** 1 px full-width hairline — above every section except the first. */
    fun addHairline(holder: LinearLayout) {
        val c = holder.context
        holder.addView(View(c).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also {
                it.topMargin = dp(c, 14)
            }
            setBackgroundColor(0x59FFFFFF)
        })
    }

    fun addSectionHeader(holder: LinearLayout, title: String) {
        val c = holder.context
        if (holder.childCount > 0) addHairline(holder)
        val label = text(c, title, 20f, YELLOW, bold = true).apply { maxLines = 1 }
        val underline = View(c).apply {
            layoutParams = LinearLayout.LayoutParams(textWidth(label), dp(c, 2.5f)).also {
                it.topMargin = dp(c, 2)
            }
            setBackgroundColor(YELLOW)
        }
        holder.addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, INDENT_SECTION), dp(c, 14), dp(c, ROW_PAD_END), dp(c, 6))
            addView(label)
            addView(underline)
        })
    }

    /** A title over a smaller value line; the whole row is the tap target. Returns the value view. */
    fun addValueRow(
        holder: LinearLayout, title: String, value: CharSequence?, valueColor: Int = DIM,
        onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null
    ): TextView {
        val c = holder.context
        val valueView = text(c, value ?: "", 13f, valueColor).apply {
            alpha = if (valueColor == DIM) SUMMARY_ALPHA else 1f
            setPadding(0, dp(c, 2), 0, 0)
            visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        holder.addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = 0
            setPadding(dp(c, INDENT_ROW), dp(c, ROW_PAD_V), dp(c, ROW_PAD_END), dp(c, ROW_PAD_V))
            addView(text(c, title, 16f, YELLOW))
            addView(valueView)
            if (onClick != null || onLongClick != null) {
                background = selectableBackground(c)
                isClickable = true
                onClick?.let { l -> setOnClickListener { l() } }
                onLongClick?.let { l -> setOnLongClickListener { l(); true } }
            }
        })
        return valueView
    }

    /** Title + description on the left, a yellow switch on the right; the row toggles it too. */
    fun addSwitchRow(
        holder: LinearLayout, title: String, description: String?, checked: Boolean, onToggle: (Boolean) -> Unit
    ): SwitchCompat {
        val c = holder.context
        val labels = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(c, title, 16f, YELLOW))
            if (!description.isNullOrEmpty()) {
                addView(text(c, description, 13f, DIM).apply {
                    alpha = SUMMARY_ALPHA
                    setPadding(0, dp(c, 2), dp(c, 8), 0)
                })
            }
        }
        val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val toggle = SwitchCompat(c).apply {
            isChecked = checked
            thumbTintList = ColorStateList(checkedStates, intArrayOf(YELLOW, 0xFF9A9A00.toInt()))
            trackTintList = ColorStateList(checkedStates, intArrayOf(0x80C8C800.toInt(), 0xFF3A3A00.toInt()))
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        holder.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 0
            setPadding(dp(c, INDENT_ROW), dp(c, ROW_PAD_V), dp(c, ROW_PAD_END), dp(c, ROW_PAD_V))
            background = selectableBackground(c)
            isClickable = true
            addView(labels)
            addView(toggle)
            setOnClickListener { toggle.toggle() }
        })
        return toggle
    }

    /** A row container with the row padding, for bespoke contents (the token row). */
    fun rowContainer(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 0
        setPadding(dp(context, INDENT_ROW), dp(context, ROW_PAD_V), dp(context, ROW_PAD_END), dp(context, ROW_PAD_V))
        background = selectableBackground(context)
        isClickable = true
    }

    // ---- pills and dialogs ----------------------------------------------------------------------

    /** The bordered rounded black panel every dialog surface is drawn on. */
    fun panelBackground(context: Context, radiusDp: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(BLACK)
        setStroke(dp(context, 2), YELLOW)
        cornerRadius = dp(context, radiusDp).toFloat()
    }

    /** A round pill: black fill, 1.5 dp yellow stroke, yellow text, yellow ripple, no all-caps. */
    fun pill(context: Context, label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(YELLOW)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val bg = GradientDrawable().apply {
            setColor(BLACK)
            setStroke(dp(context, 1.5f), YELLOW)
            cornerRadius = dp(context, 50).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf((YELLOW and 0x00FFFFFF) or 0x33000000), bg, null)
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Title + body in a bordered box; the caller appends its button row. */
    fun infoBox(context: Context, title: String, body: CharSequence?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 16))
        background = panelBackground(context)
        addView(text(context, title, 19f, YELLOW, bold = true))
        if (!body.isNullOrEmpty()) {
            addView(text(context, body, 14f, YELLOW).apply { setPadding(0, dp(context, 10), 0, 0) })
        }
    }

    /** A transparent-window dialog whose content is one bordered box (scrollable). */
    fun boxDialog(activity: Activity, content: View, cancelable: Boolean): AlertDialog {
        val scroll = ScrollView(activity).apply {
            val m = dp(activity, 10)
            setPadding(m, m, m, m)
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity).setView(scroll).create()
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    fun buttonRow(context: Context, vararg buttons: Button): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(context, 16), 0, 0)
        buttons.forEachIndexed { i, b ->
            if (i > 0) (b.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(context, 10)
            addView(b)
        }
    }

    /** One OK pill, right-aligned. [onOk] runs after the dialog is dismissed. */
    fun showInfo(activity: Activity, title: String, body: CharSequence?, cancelable: Boolean = true, onOk: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return
        val box = infoBox(activity, title, body)
        val dialog = boxDialog(activity, box, cancelable)
        box.addView(buttonRow(activity, pill(activity, activity.getString(R.string.shiroikuma_ok)) {
            dialog.dismiss()
            onOk?.invoke()
        }))
        dialog.show()
    }

    /** Cancel + one action pill. */
    fun showConfirm(activity: Activity, title: String, body: CharSequence?, action: String, onConfirm: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val box = infoBox(activity, title, body)
        val dialog = boxDialog(activity, box, true)
        box.addView(buttonRow(
            activity,
            pill(activity, activity.getString(R.string.shiroikuma_cancel)) { dialog.dismiss() },
            pill(activity, action) { dialog.dismiss(); onConfirm() }
        ))
        dialog.show()
    }
}
