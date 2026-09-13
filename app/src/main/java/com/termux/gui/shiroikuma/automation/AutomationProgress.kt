package com.termux.gui.shiroikuma.automation

import android.content.Context
import android.content.Intent
import com.termux.gui.shiroikuma.TermuxGuiExport

/**
 * shiroikuma-termux-gui fork: the ONE §3 progress sender, shared by both automation doors.
 *
 * The only thing that differs between the two callers is the correlation id — the §1 receiver's
 * `reply_id`, the §2a door's `job_id` — and it is written under every name in [correlationExtras],
 * so one reader on the caller's side serves both.
 *
 * Real counts, never a percentage: `current` is the POSITION of the category being written (1
 * while the first is written, a final call with `current == total`), `total` the number actually
 * being exported after `items` filtering, `item` the category id — which is how the caller's panel
 * knows which row to highlight. At most one message per 500 ms, the final one always sent.
 *
 * The heartbeat is separate from the throttle: the throttle caps a chatty engine, the heartbeat
 * covers a quiet one — a caller silent for two minutes is presumed dead. This app's export takes
 * milliseconds, so the heartbeat will never fire; it is here so the shape is the family's.
 */
class AutomationProgress(
    context: Context,
    action: String?,
    replyPackage: String?,
    private val correlationId: String,
    private val correlationExtras: Array<String>,
    /** Bytes written so far, when the destination is one we count; null otherwise. */
    private val bytes: (() -> Long)? = null
) {
    private val context = context.applicationContext
    private val action = action?.trim().orEmpty()
    private val replyPackage = replyPackage?.trim().orEmpty()
    private val appLabel = AutomationWire.appLabel(this.context)

    /** A `progress_action` without a `reply_package` is not a weak channel — it is none at all. */
    private val active = this.action.isNotEmpty() && this.replyPackage.isNotEmpty()

    @Volatile private var lastItem: String? = null
    @Volatile private var lastText: String? = null
    @Volatile private var lastCurrent = 0L
    @Volatile private var lastTotal = 0L
    @Volatile private var lastSentMs = 0L
    @Volatile private var running = false
    private var heartbeat: Thread? = null

    fun start() {
        if (!active || running) return
        running = true
        lastSentMs = System.currentTimeMillis()
        heartbeat = Thread({
            while (running) {
                try {
                    Thread.sleep(HEARTBEAT_TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val text = lastText
                if (!running || text == null) continue
                if (System.currentTimeMillis() - lastSentMs >= HEARTBEAT_MS) {
                    emit(lastItem, text, lastCurrent, lastTotal) // same numbers again: "still here"
                }
            }
        }, "shiroikuma-automation-heartbeat").apply {
            isDaemon = true
            start()
        }
    }

    /** Always in a `finally`. */
    fun stop() {
        running = false
        heartbeat?.interrupt()
        heartbeat = null
    }

    /** [position] 1-based, [cat] the one being written right now. */
    fun onCategory(position: Int, total: Int, cat: TermuxGuiExport.Cat) {
        if (!active) return
        val now = System.currentTimeMillis()
        if (position < total && now - lastSentMs < MIN_INTERVAL_MS) return
        val label = context.getString(cat.labelRes).substringBefore(" (")
        emit(cat.id, "$UNIT $position/$total — $label", position.toLong(), total.toLong())
    }

    /** A free-form line for a step with no honest count (the import) — for the heartbeat's sake. */
    fun note(text: String) {
        if (!active) return
        emit(null, text, 0, 0)
    }

    private fun emit(item: String?, text: String, current: Long, total: Long) {
        lastItem = item
        lastText = text
        lastCurrent = current
        lastTotal = total
        lastSentMs = System.currentTimeMillis()
        context.sendBroadcast(
            Intent(action).apply {
                setPackage(replyPackage)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                for (extra in correlationExtras) putExtra(extra, correlationId)
                putExtra("app", appLabel)
                item?.let { putExtra("item", it) }
                putExtra("text", text)
                putExtra("current", current)
                putExtra("total", total)
                putExtra("unit", UNIT)
                bytes?.let { putExtra("bytes", it()) }
            }
        )
    }

    companion object {
        private const val UNIT = "区分"
        private const val MIN_INTERVAL_MS = 500L
        private const val HEARTBEAT_MS = 20_000L
        private const val HEARTBEAT_TICK_MS = 5_000L
    }
}
