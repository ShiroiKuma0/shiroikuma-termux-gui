package com.termux.gui.shiroikuma

import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.termux.gui.R
import com.termux.gui.Settings
import java.util.concurrent.atomic.AtomicInteger

/**
 * shiroikuma-termux-gui fork: the entry points into the 白い熊 Termux GUI UI page from upstream's
 * settings screen ([com.termux.gui.GUIConfigActivity]) — installed by ONE line at the end of its
 * `onCreate`, so the upstream file carries a single hook:
 *
 * - a first row: a yellow pill button right under the 「白い熊 Termux GUI Settings」 title;
 * - a long-press on that title (the screen's settings affordance — it has no toolbar);
 * - and the repair for an import done while that screen sits underneath in the back stack:
 *   upstream fills its fields in `onCreate` and writes them back in `onPause`, so a restore would
 *   be clobbered by stale field values the next time the screen is left. When the screen resumes
 *   after an import, the fields are refilled from the restored [Settings].
 *
 * (The third entry point, the launcher long-press shortcut, is `res/xml/shortcuts.xml`.)
 */
object ShiroikumaEntry {

    /** Bumped by every import that touched `settings_prefs`; each config screen remembers what it saw. */
    private val settingsGeneration = AtomicInteger(0)

    fun noteSettingsImported() {
        settingsGeneration.incrementAndGet()
    }

    fun install(activity: AppCompatActivity) {
        val container = activity.findViewById<LinearLayout>(R.id.linearLayout1) ?: return
        val title = activity.findViewById<TextView>(R.id.settings_title) ?: return
        val open = { activity.startActivity(Intent(activity, ShiroikumaUiActivity::class.java)) }

        val button = ShiroikumaUi.pill(activity, activity.getString(R.string.shiroikuma_ui_title)) { open() }
        (button.layoutParams as LinearLayout.LayoutParams).topMargin = ShiroikumaUi.dp(activity, 12)
        val index = container.indexOfChild(title) + 1
        container.addView(button, index)

        title.setOnLongClickListener { open(); true }

        var seen = settingsGeneration.get()
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = settingsGeneration.get()
                if (now != seen) {
                    seen = now
                    refill(activity)
                }
            }
        })
    }

    /** Re-read the restored settings into upstream's fields, so its `onPause` writes them back unchanged. */
    private fun refill(activity: AppCompatActivity) {
        Settings.instance.load(activity)
        activity.findViewById<EditText>(R.id.service_timeout)?.setText(Settings.instance.timeout.toString(), TextView.BufferType.EDITABLE)
        activity.findViewById<SwitchCompat>(R.id.service_background)?.isChecked = Settings.instance.background
        activity.findViewById<EditText>(R.id.loglevel)?.setText(Settings.instance.loglevel.toString(), TextView.BufferType.EDITABLE)
    }
}
