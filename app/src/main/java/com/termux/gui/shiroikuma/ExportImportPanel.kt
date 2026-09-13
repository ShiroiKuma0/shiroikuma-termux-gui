package com.termux.gui.shiroikuma

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import com.termux.gui.GUIConfigActivity
import com.termux.gui.R
import java.io.ByteArrayOutputStream

/**
 * shiroikuma-termux-gui fork: the Export / Import window — a port of raikidoban's
 * `ExportImportPanel.java` in the family's shared shape: a transparent-window dialog whose content
 * is ONE bordered rounded black box (2 dp yellow stroke, 16 dp radius) — centred bold title, dim
 * description, a tappable bordered directory box (red when unset, yellow once set), the
 * last-backup line, a divider, 全選択 + one checkbox per category, a divider, and the pill row:
 * Cancel alone on the left, a spacer, Import then Export on the right.
 *
 * All work goes through [TermuxGuiExport], the same core the automation doors use. A successful
 * export ends in a bordered info dialog whose OK closes the whole chain (info dialog → this panel
 * → the UI page, via [Host.onChainFinished]); failures leave the panel open. An import ends in a
 * result dialog with 「Later」 (closes the chain) and 「Restart now」.
 */
class ExportImportPanel(private val activity: Activity, private val host: Host) {

    interface Host {
        fun pickExportDir(initial: Uri?)
        fun pickImportFile()
        fun onChainFinished()
    }

    private val selected = LinkedHashSet(TermuxGuiExport.Cat.defaults())
    private var dialog: AlertDialog? = null
    private var box: LinearLayout? = null

    private fun dp(v: Int) = ShiroikumaUi.dp(activity, v)

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        val b = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = ShiroikumaUi.panelBackground(activity)
        }
        box = b
        dialog = ShiroikumaUi.boxDialog(activity, b, true).also { it.show() }
        rebuild()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    // ---- content --------------------------------------------------------------------------------

    fun rebuild() {
        val b = box ?: return
        b.removeAllViews()

        b.addView(ShiroikumaUi.text(activity, activity.getString(R.string.shiroikuma_eim_title), 18f, ShiroikumaUi.YELLOW, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        })
        b.addView(ShiroikumaUi.text(activity, activity.getString(R.string.shiroikuma_eim_desc), 13f, ShiroikumaUi.DIM).apply {
            alpha = 0.85f
            setPadding(0, 0, 0, dp(10))
        })
        b.addView(dirBox())
        b.addView(statusLine())
        b.addView(divider(0))

        val selectAll = checkbox(activity.getString(R.string.shiroikuma_eim_select_all), bold = true, indent = 0)
        selectAll.isChecked = selected.size == TermuxGuiExport.Cat.entries.size
        selectAll.setOnClickListener {
            if (selectAll.isChecked) selected.addAll(TermuxGuiExport.Cat.all()) else selected.clear()
            rebuild()
        }
        b.addView(selectAll)
        for (cat in TermuxGuiExport.Cat.entries) b.addView(categoryRow(cat))

        b.addView(divider(8))
        b.addView(buttonRow())
    }

    /** The directory box: bordered, clearly tappable — small label over the value, red when unset. */
    private fun dirBox(): View {
        val label = TermuxGuiExport.dirLabel(activity)
        val remembered = if (label == null) TermuxGuiExport.rememberedDirLabel(activity) else null
        val shown = label ?: remembered ?: activity.getString(R.string.shiroikuma_eim_dir_unset)
        val color = if (label != null) ShiroikumaUi.YELLOW else ShiroikumaUi.WARN
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(ShiroikumaUi.BLACK)
                setStroke(dp(2), color)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { host.pickExportDir(TermuxGuiExport.exportDirUri(activity)) }
            addView(ShiroikumaUi.text(activity, activity.getString(R.string.shiroikuma_eim_dir), 12f, ShiroikumaUi.DIM))
            addView(ShiroikumaUi.text(activity, shown, 15f, color, bold = true))
            if (remembered != null) {
                addView(ShiroikumaUi.text(activity, activity.getString(R.string.shiroikuma_eim_dir_regrant), 12f, ShiroikumaUi.WARN).apply {
                    setPadding(0, dp(2), 0, 0)
                })
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(6)
                it.bottomMargin = dp(6)
            }
        }
    }

    private fun statusLine(): View {
        val tv = ShiroikumaUi.text(activity, "…", 14f, ShiroikumaUi.DIM).apply {
            alpha = 0.8f
            setPadding(dp(2), 0, 0, dp(8))
        }
        if (TermuxGuiExport.exportDir(activity) == null) {
            tv.text = activity.getString(R.string.shiroikuma_eim_warn_nodir)
            tv.setTextColor(ShiroikumaUi.WARN)
            tv.alpha = 1f
        } else {
            // A ContentResolver walk — off the main thread, filled in when it returns.
            Thread {
                val newest = TermuxGuiExport.newestExport(activity)
                activity.runOnUiThread {
                    if (newest == null) {
                        tv.text = activity.getString(R.string.shiroikuma_eim_warn_none)
                        tv.setTextColor(ShiroikumaUi.WARN)
                        tv.alpha = 1f
                    } else {
                        tv.text = activity.getString(R.string.shiroikuma_eim_last_line, formatTs(newest.lastModified()))
                    }
                }
            }.start()
        }
        return tv
    }

    private fun formatTs(ts: Long): String =
        DateFormat.getDateFormat(activity).format(ts) + " " + DateFormat.getTimeFormat(activity).format(ts)

    private fun categoryRow(cat: TermuxGuiExport.Cat): View {
        val isChild = cat.parentId != null
        val cb = checkbox(activity.getString(cat.labelRes), bold = false, indent = if (isChild) dp(28) else 0)
        val parentOn = !isChild || selected.contains(TermuxGuiExport.Cat.byId(cat.parentId!!))
        cb.isChecked = selected.contains(cat) && parentOn
        cb.isEnabled = parentOn
        cb.alpha = if (parentOn) 1f else 0.5f
        cb.setOnClickListener {
            val checked = cb.isChecked
            if (checked) selected.add(cat) else selected.remove(cat)
            var hasChildren = false
            for (other in TermuxGuiExport.Cat.entries) {
                if (other.parentId == cat.id) {
                    hasChildren = true
                    if (checked) selected.add(other) else selected.remove(other)
                }
            }
            if (hasChildren) rebuild()
        }
        return cb
    }

    /** Cancel alone on the left, a spacer, Import then Export on the right. */
    private fun buttonRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, 0)
        addView(ShiroikumaUi.pill(activity, activity.getString(R.string.shiroikuma_cancel)) { dismiss() })
        addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        addView(ShiroikumaUi.pill(activity, activity.getString(R.string.shiroikuma_eim_import)) { onImportClicked() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
        })
        addView(ShiroikumaUi.pill(activity, activity.getString(R.string.shiroikuma_eim_export)) { onExportClicked() })
    }

    // ---- export ---------------------------------------------------------------------------------

    private fun onExportClicked() {
        if (selected.isEmpty()) {
            ShiroikumaUi.showInfo(activity, activity.getString(R.string.shiroikuma_eim_export_fail_title),
                activity.getString(R.string.shiroikuma_eim_none_selected))
            return
        }
        val dir = TermuxGuiExport.exportDir(activity)
        if (dir == null) {
            host.pickExportDir(TermuxGuiExport.exportDirUri(activity)) // no directory yet: ask instead of failing
            return
        }
        Toast.makeText(activity, R.string.shiroikuma_eim_exporting, Toast.LENGTH_SHORT).show()
        val cats = LinkedHashSet(selected)
        Thread {
            val result = runCatching { TermuxGuiExport.writeToTree(activity, dir, cats) }
            activity.runOnUiThread {
                result.onSuccess { w ->
                    // OK closes the chain: info dialog → panel → the UI page.
                    ShiroikumaUi.showInfo(
                        activity, activity.getString(R.string.shiroikuma_eim_export_done_title),
                        activity.getString(R.string.shiroikuma_eim_export_done_body, w.shownPath, TermuxGuiExport.humanSize(w.bytes), cats.size),
                        cancelable = false
                    ) {
                        dismiss()
                        host.onChainFinished()
                    }
                }.onFailure { t ->
                    ShiroikumaUi.showInfo(
                        activity, activity.getString(R.string.shiroikuma_eim_export_fail_title),
                        activity.getString(R.string.shiroikuma_eim_export_fail, t.message ?: t.javaClass.simpleName)
                    )
                }
            }
        }.start()
    }

    // ---- import ---------------------------------------------------------------------------------

    private fun onImportClicked() {
        if (selected.isEmpty()) {
            ShiroikumaUi.showInfo(activity, activity.getString(R.string.shiroikuma_eim_import_fail_title),
                activity.getString(R.string.shiroikuma_eim_none_selected))
            return
        }
        Thread {
            val backups = TermuxGuiExport.listExports(activity)
            activity.runOnUiThread { showBackupPicker(backups) }
        }.start()
    }

    /** The backups in the export directory, newest first, plus 「Browse…」 → the SAF file picker. */
    private fun showBackupPicker(backups: List<DocumentFile>) {
        if (activity.isFinishing || activity.isDestroyed) return
        val b = ShiroikumaUi.infoBox(activity, activity.getString(R.string.shiroikuma_eim_pick_backup),
            if (backups.isEmpty()) activity.getString(R.string.shiroikuma_eim_no_backups) else null)
        val d = ShiroikumaUi.boxDialog(activity, b, true)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        fun item(label: String, onClick: () -> Unit) = ShiroikumaUi.text(activity, label, 15f, ShiroikumaUi.YELLOW).apply {
            background = ShiroikumaUi.selectableBackground(activity)
            isClickable = true
            setPadding(dp(4), dp(9), dp(4), dp(9))
            setOnClickListener { d.dismiss(); onClick() }
        }
        for (f in backups) list.addView(item(f.name ?: continue) { runImport(f.uri) })
        list.addView(item(activity.getString(R.string.shiroikuma_eim_browse)) { host.pickImportFile() })
        b.addView(list)
        b.addView(ShiroikumaUi.buttonRow(activity, ShiroikumaUi.pill(activity, activity.getString(R.string.shiroikuma_cancel)) { d.dismiss() }))
        d.show()
    }

    /** Called by the host after the SAF file picker returns. */
    fun onImportFilePicked(uri: Uri?) {
        if (uri != null) runImport(uri)
    }

    private fun runImport(uri: Uri) {
        Toast.makeText(activity, R.string.shiroikuma_eim_importing, Toast.LENGTH_SHORT).show()
        val cats = LinkedHashSet(selected)
        Thread {
            val result = runCatching {
                val data = activity.contentResolver.openInputStream(uri)?.use { input ->
                    ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
                } ?: throw IllegalStateException("cannot open the archive")
                // Only the categories the archive carries, intersected with what is ticked.
                val present = TermuxGuiExport.categoriesIn(data)
                if (present.isEmpty()) throw IllegalStateException("not a 白い熊 Termux GUI backup")
                TermuxGuiExport.import(activity, data, cats.filter { it in present }.toSet())
            }
            activity.runOnUiThread {
                result.onSuccess { showImportResult(it) }.onFailure { t ->
                    ShiroikumaUi.showInfo(
                        activity, activity.getString(R.string.shiroikuma_eim_import_fail_title),
                        activity.getString(R.string.shiroikuma_eim_import_fail, t.message ?: t.javaClass.simpleName)
                    )
                }
            }
        }.start()
    }

    /** 「Later」 closes the whole chain; 「Restart now」 relaunches the process so everything re-reads. */
    private fun showImportResult(summary: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        ShiroikumaEntry.noteSettingsImported()
        val body = summary + "\n\n" + activity.getString(R.string.shiroikuma_eim_restart_hint)
        val b = ShiroikumaUi.infoBox(activity, activity.getString(R.string.shiroikuma_eim_import_done_title), body)
        val d = ShiroikumaUi.boxDialog(activity, b, false)
        b.addView(ShiroikumaUi.buttonRow(
            activity,
            ShiroikumaUi.pill(activity, activity.getString(R.string.shiroikuma_eim_later)) {
                d.dismiss()
                dismiss()
                host.onChainFinished()
            },
            ShiroikumaUi.pill(activity, activity.getString(R.string.shiroikuma_eim_restart_now)) { restartApp() }
        ))
        d.show()
    }

    private fun restartApp() {
        val launch = Intent.makeRestartActivityTask(android.content.ComponentName(activity, GUIConfigActivity::class.java))
        activity.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    // ---- the export directory -------------------------------------------------------------------

    /** Called by the host after the SAF folder picker returns. */
    fun onDirPicked(uri: Uri?) {
        if (uri == null) return
        TermuxGuiExport.setExportDirUri(activity, uri)
        rebuild()
    }

    // ---- view builders --------------------------------------------------------------------------

    private fun checkbox(label: String, bold: Boolean, indent: Int): CheckBox = CheckBox(activity).apply {
        text = label
        setTextColor(ShiroikumaUi.YELLOW)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        buttonTintList = ColorStateList.valueOf(ShiroikumaUi.YELLOW)
        setPadding(dp(8) + indent, dp(7), 0, dp(7))
    }

    private fun divider(topGap: Int): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = dp(topGap) }
        setBackgroundColor(ShiroikumaUi.YELLOW)
        alpha = 0.4f
    }
}
