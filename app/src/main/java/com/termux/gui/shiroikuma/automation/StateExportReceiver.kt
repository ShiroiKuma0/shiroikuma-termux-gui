package com.termux.gui.shiroikuma.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.termux.gui.shiroikuma.TermuxGuiExport

/**
 * shiroikuma-termux-gui fork: the sister-app **state-export automation contract** (v2 §1) — the
 * exported receiver 自由作業盤's 保存復元 batch fires at.
 *
 * It does NO work of its own. A manifest receiver must reach `finish()` inside Android's broadcast
 * window (~10 s foreground, ~60 s background) or the process is ANR'd and killed mid-export, so:
 *
 * - `<pkg>.action.LIST_CATEGORIES` — gated, answered inline (instant): `OK:` + one
 *   `id<TAB>label<TAB>parent<TAB>on|off` line per category. The parent field is empty (this app's
 *   list is flat) but present, because the default flag after it is positional.
 * - `<pkg>.action.EXPORT_STATE` — gated, `items` validated here (where an error is still cheap),
 *   then handed to [StateExportService] with a GUARDED `startForegroundService`: a broadcast is a
 *   background start on API 31+ and the platform may refuse it with
 *   `ForegroundServiceStartNotAllowedException`; unguarded, that exception crashes the app being
 *   backed up. The refusal is answered ([AutomationWire.refusal]), never merely swallowed —
 *   catching alone turns a crash into a silent no-export the caller cannot tell from an app that
 *   never implemented the contract.
 * - `<pkg>.action.CANCEL_EXPORT` — fire-and-forget: signals the running export (which deletes its
 *   partial and answers `ERROR:cancelled` through its own request's channel) and replies nothing.
 *   Safe at any time; nothing running is a silent no-op.
 *
 * Extras (all String): `token` (optional — ignored unless 「Use authorization token?」 is on),
 * `path`, `items`, `progress_action`, `reply_action`, `reply_package`, `reply_id`. Reply: a fresh
 * broadcast ([AutomationWire.reply]), exactly one per request.
 *
 * Exported with NO `android:permission` — since v2 this is deliberately the unauthenticated half
 * of the surface: it only ever writes where it was told to and reports what it did. Everything
 * that moves data through a caller-supplied descriptor lives behind [AutomationProvider], which
 * knows who is calling; `import` exists ONLY there.
 */
class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val token = intent.getStringExtra(AutomationWire.EXTRA_TOKEN)
        val replyAction = intent.getStringExtra(AutomationWire.EXTRA_REPLY_ACTION)?.trim().orEmpty()
        val replyPackage = intent.getStringExtra(AutomationWire.EXTRA_REPLY_PACKAGE)?.trim().orEmpty()
        val replyId = intent.getStringExtra(AutomationWire.EXTRA_REPLY_ID)?.trim().orEmpty()
        val items = intent.getStringExtra(AutomationWire.EXTRA_ITEMS)?.trim().orEmpty()

        fun reply(result: String) = AutomationWire.reply(app, replyAction, replyPackage, replyId, null, result)

        // Cancel first: it never answers anything — not OK:, not a gate refusal.
        if (action == cancelExportAction(app)) {
            if (AutomationAuth.refuse(app, token) == null) StateExportService.requestCancel(replyId)
            return
        }

        // The gate, in ONE place (§2). A token sent to an app that does not ask for one is IGNORED.
        AutomationAuth.refuse(app, token)?.let {
            reply(it)
            return
        }

        when (action) {
            listCategoriesAction(app) -> reply("OK:" + categoryLines(app))

            exportStateAction(app) -> {
                if (TermuxGuiExport.Cat.resolve(items) == null) {
                    reply("ERROR:unknown category in items: $items")
                    return
                }
                val svc = Intent(app, StateExportService::class.java).apply {
                    putExtra(AutomationWire.EXTRA_PATH, intent.getStringExtra(AutomationWire.EXTRA_PATH))
                    putExtra(AutomationWire.EXTRA_ITEMS, items)
                    putExtra(AutomationWire.EXTRA_PROGRESS_ACTION, intent.getStringExtra(AutomationWire.EXTRA_PROGRESS_ACTION))
                    putExtra(AutomationWire.EXTRA_REPLY_ACTION, replyAction)
                    putExtra(AutomationWire.EXTRA_REPLY_PACKAGE, replyPackage)
                    putExtra(AutomationWire.EXTRA_REPLY_ID, replyId)
                }
                // Site 1 of four (§1): guarded, and answered.
                try {
                    ContextCompat.startForegroundService(app, svc)
                } catch (t: Throwable) {
                    reply(AutomationWire.refusal(app, t))
                }
            }

            else -> reply("ERROR:unknown action: $action")
        }
    }

    companion object {
        // `<pkg>.action.…` on the INSTALLED applicationId, exactly as the manifest's ${applicationId}.
        fun exportStateAction(context: Context) = "${context.packageName}.action.EXPORT_STATE"
        fun listCategoriesAction(context: Context) = "${context.packageName}.action.LIST_CATEGORIES"
        fun cancelExportAction(context: Context) = "${context.packageName}.action.CANCEL_EXPORT"

        /** `id ⇥ label ⇥ parent ⇥ on|off`, one line per category, in export order. */
        fun categoryLines(context: Context): String =
            TermuxGuiExport.Cat.entries.joinToString("\n") { cat ->
                val label = context.getString(cat.labelRes)
                "${cat.id}\t$label\t${cat.parentId.orEmpty()}\t${if (cat.onByDefault) "on" else "off"}"
            }
    }
}
