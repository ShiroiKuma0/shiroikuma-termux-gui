package com.termux.gui.shiroikuma

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.termux.gui.R
import com.termux.gui.shiroikuma.automation.AutomationAuth

/**
 * shiroikuma-termux-gui fork: the **白い熊 Termux GUI UI** page — the house black-yellow page every
 * sister app carries, programmatic views (the kojiki / raikidoban recipe; this app has no
 * `androidx.preference`).
 *
 * Sections: **Export / Import** (「Export / Import…」 → [ExportImportPanel]; 「Export directory」 —
 * a persisted SAF tree, red "not set" until chosen; 「Last export」 — the newest
 * `shiroikuma-termux-gui_*.zip` in it, queried on resume off the main thread; then the 保存復元
 * automation rows exactly here, contract v2 §2: 「Automation export」 ON, 「Use authorization
 * token?」 OFF, the token row only while that is on) and **Reset** (clears this page's own prefs
 * file after a confirm — which holds nothing yet, as the page has no appearance settings).
 *
 * Reached from: the launcher shortcut (`res/xml/shortcuts.xml`), and the pill / title long-press
 * [ShiroikumaEntry] adds to upstream's settings screen.
 */
class ShiroikumaUiActivity : AppCompatActivity() {

    private lateinit var holder: LinearLayout
    private lateinit var scroll: ScrollView
    private var lastExportView: TextView? = null
    private var panel: ExportImportPanel? = null

    private val pickExportDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onExportDirPicked(uri)
    }
    private val pickImportFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && panel?.isShowing == true) panel?.onImportFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ShiroikumaUi.BLACK)
        }
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(ShiroikumaUi.BLACK)
            setTitleTextColor(ShiroikumaUi.YELLOW)
            title = getString(R.string.shiroikuma_ui_title)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ShiroikumaUi.dp(this@ShiroikumaUiActivity, 24))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(holder, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon?.setTint(ShiroikumaUi.YELLOW)
        window.navigationBarColor = ShiroikumaUi.BLACK
        buildRows()
        val y = savedInstanceState?.getInt(KEY_SCROLL_Y, 0) ?: 0
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    override fun onResume() {
        super.onResume()
        refreshLastExport()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL_Y, scroll.scrollY)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---- rows -----------------------------------------------------------------------------------

    private fun buildRows() {
        holder.removeAllViews()
        lastExportView = null

        ShiroikumaUi.addSectionHeader(holder, getString(R.string.shiroikuma_section_eim))
        ShiroikumaUi.addValueRow(holder, getString(R.string.shiroikuma_eim_entry), getString(R.string.shiroikuma_eim_entry_desc),
            onClick = { openExportImport() })
        addDirRow()
        lastExportView = ShiroikumaUi.addValueRow(holder, getString(R.string.shiroikuma_eim_last), "…")
        addAutomationRows()

        ShiroikumaUi.addSectionHeader(holder, getString(R.string.shiroikuma_section_reset))
        ShiroikumaUi.addValueRow(holder, getString(R.string.shiroikuma_reset_row), getString(R.string.shiroikuma_reset_desc),
            onClick = { confirmReset() })
    }

    /** Red while unset; a directory a restore remembered but the platform no longer grants is named, with the re-grant line. */
    private fun addDirRow() {
        val label = TermuxGuiExport.dirLabel(this)
        val remembered = if (label == null) TermuxGuiExport.rememberedDirLabel(this) else null
        val value = label ?: remembered?.let { "$it\n${getString(R.string.shiroikuma_eim_dir_regrant)}" }
            ?: getString(R.string.shiroikuma_eim_dir_unset)
        ShiroikumaUi.addValueRow(holder, getString(R.string.shiroikuma_eim_dir), value,
            valueColor = if (label != null) ShiroikumaUi.DIM else ShiroikumaUi.WARN,
            onClick = { pickExportDir.launch(TermuxGuiExport.exportDirUri(this)) })
    }

    /** Queried on every resume, off the main thread: the newest backup's date, time and size. */
    private fun refreshLastExport() {
        val tv = lastExportView ?: return
        if (TermuxGuiExport.exportDir(this) == null) {
            setLastExport(tv, getString(R.string.shiroikuma_eim_last_nodir), warn = true)
            return
        }
        Thread {
            val newest = TermuxGuiExport.newestExport(this)
            val ts = newest?.lastModified() ?: 0L
            val bytes = newest?.length() ?: 0L
            runOnUiThread {
                if (isFinishing || isDestroyed || lastExportView !== tv) return@runOnUiThread
                if (newest == null) {
                    setLastExport(tv, getString(R.string.shiroikuma_eim_last_none), warn = true)
                } else {
                    val date = DateFormat.getDateFormat(this).format(ts) + " " + DateFormat.getTimeFormat(this).format(ts)
                    setLastExport(tv, getString(R.string.shiroikuma_eim_last_value, date, TermuxGuiExport.humanSize(bytes)), warn = false)
                }
            }
        }.start()
    }

    private fun setLastExport(tv: TextView, value: String, warn: Boolean) {
        tv.text = value
        tv.setTextColor(if (warn) ShiroikumaUi.WARN else ShiroikumaUi.YELLOW)
        tv.alpha = 1f
        tv.visibility = TextView.VISIBLE
    }

    /**
     * The three automation rows, in this order and inside the Export / Import section (contract
     * v2 §2): master switch ON, token opt-in OFF, the token row only while asked for.
     */
    private fun addAutomationRows() {
        ShiroikumaUi.addSwitchRow(holder, getString(R.string.shiroikuma_auto_switch), getString(R.string.shiroikuma_auto_switch_desc),
            AutomationAuth.enabled(this)) { AutomationAuth.setEnabled(this, it) }
        ShiroikumaUi.addSwitchRow(holder, getString(R.string.shiroikuma_auto_require_token), getString(R.string.shiroikuma_auto_require_token_desc),
            AutomationAuth.requireToken(this)) {
            AutomationAuth.setRequireToken(this, it)
            // Posted, so the switch that fired it finishes drawing before the holder is rebuilt underneath it.
            holder.post { buildRows(); refreshLastExport() }
        }
        if (AutomationAuth.requireToken(this)) addTokenRow()
    }

    /** Tap = copy the full token; 「Regenerate」 on the right = a fresh secret, after a confirm. */
    private fun addTokenRow() {
        val row = ShiroikumaUi.rowContainer(this)
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ShiroikumaUi.text(this@ShiroikumaUiActivity, getString(R.string.shiroikuma_auto_token), 16f, ShiroikumaUi.YELLOW))
            addView(ShiroikumaUi.text(this@ShiroikumaUiActivity, AutomationAuth.abbreviate(AutomationAuth.token(this@ShiroikumaUiActivity)), 13f, ShiroikumaUi.DIM).apply {
                typeface = Typeface.MONOSPACE
                alpha = ShiroikumaUi.SUMMARY_ALPHA
                setPadding(0, ShiroikumaUi.dp(this@ShiroikumaUiActivity, 2), ShiroikumaUi.dp(this@ShiroikumaUiActivity, 8), 0)
            })
            addView(ShiroikumaUi.text(this@ShiroikumaUiActivity, getString(R.string.shiroikuma_auto_token_desc), 13f, ShiroikumaUi.DIM).apply {
                alpha = ShiroikumaUi.SUMMARY_ALPHA
            })
        }
        row.addView(labels)
        row.addView(ShiroikumaUi.pill(this, getString(R.string.shiroikuma_auto_regenerate)) { confirmRegenerateToken() })
        row.setOnClickListener { copyToken() }
        holder.addView(row)
    }

    private fun copyToken() {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cb?.setPrimaryClip(ClipData.newPlainText("automation_token", AutomationAuth.token(this)))
        Toast.makeText(this, R.string.shiroikuma_auto_token_copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmRegenerateToken() {
        ShiroikumaUi.showConfirm(this, getString(R.string.shiroikuma_auto_token_regen_title),
            getString(R.string.shiroikuma_auto_token_regen_msg), getString(R.string.shiroikuma_auto_regenerate)) {
            AutomationAuth.regenerateToken(this)
            buildRows()
            refreshLastExport()
            Toast.makeText(this, R.string.shiroikuma_auto_token_regenerated, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun confirmReset() {
        ShiroikumaUi.showConfirm(this, getString(R.string.shiroikuma_reset_confirm_title),
            getString(R.string.shiroikuma_reset_confirm_msg), getString(R.string.shiroikuma_section_reset)) {
            getSharedPreferences(TermuxGuiExport.UI_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            buildRows()
            refreshLastExport()
            Toast.makeText(this, R.string.shiroikuma_reset_done, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Export / Import ------------------------------------------------------------------------

    private fun openExportImport() {
        if (isFinishing || isDestroyed) return
        panel = ExportImportPanel(this, object : ExportImportPanel.Host {
            override fun pickExportDir(initial: Uri?) = this@ShiroikumaUiActivity.pickExportDir.launch(initial)
            override fun pickImportFile() = pickImportFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            override fun onChainFinished() = finish() // info dialog → panel → this page
        }).also { it.show() }
    }

    private fun onExportDirPicked(uri: Uri?) {
        if (uri == null) return
        val p = panel
        if (p != null && p.isShowing) p.onDirPicked(uri) else TermuxGuiExport.setExportDirUri(this, uri)
        buildRows()
        refreshLastExport()
    }

    private companion object {
        const val KEY_SCROLL_Y = "shiroikuma_ui_scroll_y"
    }
}
