package com.termux.gui.shiroikuma.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.termux.gui.R
import com.termux.gui.shiroikuma.TermuxGuiExport
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * shiroikuma-termux-gui fork: where an [AutomationProvider] data export or import actually runs —
 * a `dataSync` foreground service, so the binder call returns in milliseconds and a backgrounded
 * process is not frozen mid-stream underneath a success reply.
 *
 * The descriptor was already `dup()`ed by the provider; it crosses to this service through
 * [HANDOVER] (an Intent extra is duplicated again by the system, with a lifetime that is no longer
 * ours to reason about), so exactly one open descriptor has exactly one owner, who closes it in a
 * `finally`. A leaked one holds the caller's file open, and the caller cannot checksum or encrypt
 * an open file.
 *
 * `onStartCommand` is the three-step recipe (§2a): read the extras → go foreground inside a
 * `try` (site 4 of four — the `OK:<job_id>` has genuinely been sent by now, so a refusal here is
 * answered with the terminal broadcast) → drain [HANDOVER] → only then the early returns, which
 * on a stale or already-claimed job id stop SILENTLY (that id already had its one reply).
 */
class AutomationDataService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Extras, defensively — no early returns.
        val jobId = intent?.getStringExtra(EXTRA_JOB).orEmpty()
        val importing = intent?.getBooleanExtra(EXTRA_IMPORTING, false) ?: false
        val replyAction = intent?.getStringExtra(AutomationWire.EXTRA_REPLY_ACTION)
        val replyPackage = intent?.getStringExtra(AutomationWire.EXTRA_REPLY_PACKAGE)
        val progressAction = intent?.getStringExtra(AutomationWire.EXTRA_PROGRESS_ACTION)
        val items = intent?.getStringExtra(AutomationWire.EXTRA_ITEMS)

        // 2. Foreground, guarded. A refusal is answered under the job id, the descriptor closed, the job dropped.
        try {
            val text = getString(if (importing) R.string.shiroikuma_notif_importing else R.string.shiroikuma_notif_exporting)
            AutomationWire.goForeground(this, NOTIFICATION_ID, AutomationWire.notification(this, text))
        } catch (t: Throwable) {
            HANDOVER.remove(jobId)?.let { runCatching { it.close() } }
            if (jobId.isNotEmpty()) {
                AutomationJobs.finish(jobId)
                AutomationWire.reply(applicationContext, replyAction, replyPackage, jobId, jobId,
                    AutomationWire.refusal(applicationContext, t))
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. Drain the handover, then the (silent) early returns.
        val fd = HANDOVER.remove(jobId)
        if (jobId.isEmpty() || fd == null) return stop()

        val app = applicationContext
        Thread({
            try {
                runJob(app, jobId, fd, importing, items, replyAction, replyPackage, progressAction)
            } finally {
                AutomationWire.stopForeground(this)
                stopSelf()
            }
        }, "shiroikuma-automation-data").start()
        return START_NOT_STICKY
    }

    private fun stop(): Int {
        AutomationWire.stopForeground(this)
        stopSelf()
        return START_NOT_STICKY
    }

    /** Bytes written so far — a named class, not an anonymous object (an AGP lint crash shape). */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        @Volatile var written = 0L
            private set

        override fun write(b: Int) { out.write(b); written++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    companion object {
        private const val NOTIFICATION_ID = 91_702
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /** The most an import may be. This app's archives are a few KB; the descriptor is the caller's. */
        private const val MAX_IMPORT_BYTES = 16 shl 20

        private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

        /**
         * Park the descriptor and start the service. Returns null on success, or the refusal to
         * RETURN from `call()` — on that path the dup is closed here and nothing is broadcast,
         * because no `OK:<job_id>` has been handed out.
         */
        fun start(context: Context, jobId: String, fd: ParcelFileDescriptor, importing: Boolean, extras: Bundle?): String? {
            HANDOVER[jobId] = fd
            val intent = Intent(context, AutomationDataService::class.java).apply {
                putExtra(EXTRA_JOB, jobId)
                putExtra(EXTRA_IMPORTING, importing)
                putExtra(AutomationWire.EXTRA_ITEMS, extras?.getString(AutomationWire.EXTRA_ITEMS))
                putExtra(AutomationWire.EXTRA_REPLY_ACTION, extras?.getString(AutomationWire.EXTRA_REPLY_ACTION))
                putExtra(AutomationWire.EXTRA_REPLY_PACKAGE, extras?.getString(AutomationWire.EXTRA_REPLY_PACKAGE))
                putExtra(AutomationWire.EXTRA_PROGRESS_ACTION, extras?.getString(AutomationWire.EXTRA_PROGRESS_ACTION))
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                null
            } catch (t: Throwable) {
                HANDOVER.remove(jobId)?.let { runCatching { it.close() } }
                AutomationWire.refusal(context, t)
            }
        }

        private fun runJob(
            context: Context, jobId: String, fd: ParcelFileDescriptor, importing: Boolean,
            items: String?, replyAction: String?, replyPackage: String?, progressAction: String?
        ) {
            val replied = AtomicBoolean(false)
            // A `val` lambda, not a local `fun` — the other half of the lint-crash shape.
            val reply: (String) -> Unit = { result ->
                if (replied.compareAndSet(false, true)) {
                    AutomationJobs.finish(jobId)
                    // The job id rides under BOTH `job_id` and `reply_id`.
                    AutomationWire.reply(context, replyAction, replyPackage, jobId, jobId, result)
                }
            }
            val progress = AutomationProgress(
                context, progressAction, replyPackage, jobId,
                arrayOf(AutomationWire.EXTRA_REPLY_ID, AutomationWire.EXTRA_JOB_ID)
            )
            try {
                progress.start()
                fd.use { open ->
                    if (importing) runImport(context, open, progress, reply)
                    else runExport(context, jobId, open, items, progress, reply)
                }
            } catch (_: TermuxGuiExport.Cancelled) {
                reply("ERROR:cancelled")
            } catch (t: Throwable) {
                reply("ERROR:${AutomationWire.oneLine(t)}")
            } finally {
                progress.stop()
                // A path that somehow returned without replying must not leave the caller waiting.
                reply("ERROR:ended without a result")
            }
        }

        private fun runExport(
            context: Context, jobId: String, fd: ParcelFileDescriptor, items: String?,
            progress: AutomationProgress, reply: (String) -> Unit
        ) {
            val cats = TermuxGuiExport.Cat.resolve(items)
                ?: run { reply("ERROR:unknown category in items: $items"); return }
            var written = 0L
            // Counted as it goes: the caller owns the file and we may not be able to stat it at all.
            CountingOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(fd)).use { out ->
                TermuxGuiExport.export(
                    context, cats, out,
                    onProgress = { pos, total, cat -> progress.onCategory(pos, total, cat) },
                    isCancelled = { AutomationJobs.isCancelled(jobId) }
                )
                out.flush()
                written = out.written
            }
            if (AutomationJobs.isCancelled(jobId)) reply("ERROR:cancelled")
            else reply("OK:$written|${cats.size} categories")
        }

        /** Read the whole (capped) archive before touching anything — half a restore is worse than none. */
        private fun runImport(
            context: Context, fd: ParcelFileDescriptor, progress: AutomationProgress, reply: (String) -> Unit
        ) {
            progress.note("reading archive")
            val buf = ByteArray(MAX_IMPORT_BYTES + 1)
            var n = 0
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            if (n > MAX_IMPORT_BYTES) { reply("ERROR:archive too large (over ${MAX_IMPORT_BYTES shr 20} MB)"); return }
            if (n == 0) { reply("ERROR:empty archive"); return }
            val bytes = buf.copyOf(n)
            val present = TermuxGuiExport.categoriesIn(bytes)
            if (present.isEmpty()) { reply("ERROR:archive carries no categories"); return }
            progress.note("restoring ${present.size} categories")
            val summary = TermuxGuiExport.import(context, bytes, present)
            // FLUSH BEFORE REPLYING: 応用管理 SIGKILLs this process the instant it hears OK.
            TermuxGuiExport.flushPrefs(context)
            reply("OK:${summary.replace('\n', ' ')}")
        }
    }
}
