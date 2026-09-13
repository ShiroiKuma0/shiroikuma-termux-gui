package com.termux.gui.shiroikuma.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.termux.gui.R
import com.termux.gui.shiroikuma.TermuxGuiExport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * shiroikuma-termux-gui fork: where a §1 `EXPORT_STATE` actually runs — a `dataSync` foreground
 * service, off the broadcast window, with real progress and exactly one terminal reply.
 *
 * `onStartCommand` is THREE steps in this order, because two rules pull against each other:
 * 1. **Read the extras** (microseconds, no early return) — a refused promotion must have an
 *    address to answer to.
 * 2. **Go foreground, guarded** (site 2 of four). On refusal: reply, `stopSelf`, return.
 * 3. **Only then the early returns and the "already running" claim** — claiming the flag before
 *    the guarded promotion would leave it stuck when the promotion is refused.
 *
 * Destination (§1): this app declares no storage permission (not `MANAGE_EXTERNAL_STORAGE`, not
 * even `WRITE_EXTERNAL_STORAGE`), so a caller-supplied absolute `path` can never be written: it is
 * ignored in favour of the configured SAF directory (the contract's fallback for exactly this
 * case), and with no directory the reply is exactly `ERROR:no-storage-access` (path given) /
 * `ERROR:no-directory` (no path) — the keys 自由作業盤 turns into repair buttons.
 *
 * The archive is written as `<name>.part` and renamed on completion; a failure or a cancel deletes
 * the partial ([TermuxGuiExport.writeToTree]). The "running" flag is process-local and released in
 * a `finally` — a persisted one wedges the app after a single crash.
 */
class StateExportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Extras — no early returns yet.
        val replyAction = intent?.getStringExtra(AutomationWire.EXTRA_REPLY_ACTION).orEmpty()
        val replyPackage = intent?.getStringExtra(AutomationWire.EXTRA_REPLY_PACKAGE).orEmpty()
        val replyId = intent?.getStringExtra(AutomationWire.EXTRA_REPLY_ID).orEmpty()
        val progressAction = intent?.getStringExtra(AutomationWire.EXTRA_PROGRESS_ACTION)
        val pathOverride = intent?.getStringExtra(AutomationWire.EXTRA_PATH)?.trim().orEmpty()
        val items = intent?.getStringExtra(AutomationWire.EXTRA_ITEMS)

        val replied = AtomicBoolean(false)
        val reply: (String) -> Unit = { result ->
            if (replied.compareAndSet(false, true)) {
                AutomationWire.reply(applicationContext, replyAction, replyPackage, replyId, null, result)
            }
        }

        // 2. Foreground, guarded (site 2). Once startForegroundService() was called the platform
        //    demands this whatever we decide next, and enforces it by killing the process.
        try {
            AutomationWire.goForeground(this, NOTIFICATION_ID, AutomationWire.notification(this, getString(R.string.shiroikuma_notif_exporting)))
        } catch (t: Throwable) {
            reply(AutomationWire.refusal(applicationContext, t))
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. Early returns and the running claim.
        if (intent == null) {
            AutomationWire.stopForeground(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) {
            // The first run still owns the service and stops it when done; only this request is answered.
            reply("ERROR:export already running")
            return START_NOT_STICKY
        }
        cancelRequested = false
        runningReplyId = replyId

        val app = applicationContext
        Thread({
            val cats = TermuxGuiExport.Cat.resolve(items) ?: TermuxGuiExport.Cat.defaults()
            val progress = AutomationProgress(
                app, progressAction, replyPackage, replyId, arrayOf(AutomationWire.EXTRA_REPLY_ID)
            )
            // EMUI dozes the CPU with the screen off; this export is milliseconds, but the shape stays.
            val wakeLock = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                ?.apply { setReferenceCounted(false) }
            try {
                runCatching { wakeLock?.acquire(WAKELOCK_TIMEOUT_MS) }
                progress.start()
                // This app declares no storage permission at all, so a caller-supplied absolute
                // `path` can never be written: the configured SAF directory stands in for it, and
                // with none the reply is the keyed refusal 自由作業盤 turns into a repair button.
                val safDir = TermuxGuiExport.exportDir(app)
                if (safDir == null) {
                    reply(if (pathOverride.isNotEmpty()) "ERROR:no-storage-access" else "ERROR:no-directory")
                    return@Thread
                }
                val written = TermuxGuiExport.writeToTree(
                    app, safDir, cats,
                    onProgress = { pos, total, cat -> progress.onCategory(pos, total, cat) },
                    isCancelled = { cancelRequested }
                )
                reply("OK:${written.shownPath}|${written.bytes}|${TermuxGuiExport.humanSize(written.bytes)}|${cats.size} categories")
            } catch (_: TermuxGuiExport.Cancelled) {
                // The terminal reply for the ORIGINAL request — proves the run ended rather than carried on unseen.
                reply("ERROR:cancelled")
            } catch (t: Throwable) {
                reply("ERROR:${AutomationWire.oneLine(t)}")
            } finally {
                progress.stop()
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
                runningReplyId = ""
                cancelRequested = false
                running.set(false)
                AutomationWire.stopForeground(this)
                stopSelf()
            }
        }, "shiroikuma-state-export").start()
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 91_701
        private const val WAKELOCK_TAG = "shiroikuma-termux-gui:state-export"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** Process-local, never persisted. */
        private val running = AtomicBoolean(false)

        @Volatile private var runningReplyId = ""
        @Volatile private var cancelRequested = false

        /**
         * Raise the flag for the run in flight. An empty [replyId] means "whatever is running"; a
         * given one must match. Nothing running, already finished, or another run → silent no-op.
         */
        fun requestCancel(replyId: String) {
            if (!running.get()) return
            if (replyId.isNotEmpty() && replyId != runningReplyId) return
            cancelRequested = true
        }
    }
}
