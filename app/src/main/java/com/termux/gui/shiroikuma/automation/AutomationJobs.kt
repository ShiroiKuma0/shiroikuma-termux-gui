package com.termux.gui.shiroikuma.automation

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * shiroikuma-termux-gui fork: the jobs the data door has started, and the flag each watches to stop.
 *
 * The CALLEE mints the id (contract v2 §2a): [begin] returns it, the provider answers
 * `OK:<job_id>` with it, and every progress line and the terminal reply carry it. A `cancel` names
 * an id handed out here; one for a job that is finished or was never real is a silent no-op — the
 * normal race, not an error.
 */
object AutomationJobs {

    private val cancelled = ConcurrentHashMap<String, Boolean>()

    fun begin(): String = UUID.randomUUID().toString().also { cancelled[it] = false }

    fun cancel(jobId: String?) {
        jobId?.let { cancelled.computeIfPresent(it) { _, _ -> true } }
    }

    /** Polled at write boundaries — never mid-write, so a cancelled archive is never half a file. */
    fun isCancelled(jobId: String): Boolean = cancelled[jobId] == true

    fun finish(jobId: String) {
        cancelled.remove(jobId)
    }
}
