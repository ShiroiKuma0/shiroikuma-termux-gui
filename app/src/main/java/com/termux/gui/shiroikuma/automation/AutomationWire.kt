package com.termux.gui.shiroikuma.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.termux.gui.R

/**
 * shiroikuma-termux-gui fork: the ONE place a terminal reply is built, a refused foreground start
 * is worded, and a foreground service goes foreground.
 *
 * Both doors of the sister-app contract — §1's broadcast receiver + [StateExportService] and §2a's
 * [AutomationProvider] + [AutomationDataService] — answer the same way. They are deliberately not
 * two senders: the caller's liveness watchdog and its failure-row buttons key on these exact
 * strings, and two copies drift.
 */
object AutomationWire {

    // Contract extras — deliberately bare names, shared verbatim by every sister app.
    const val EXTRA_TOKEN = "token"
    const val EXTRA_PATH = "path"
    const val EXTRA_ITEMS = "items"
    const val EXTRA_PROGRESS_ACTION = "progress_action"
    const val EXTRA_REPLY_ACTION = "reply_action"
    const val EXTRA_REPLY_PACKAGE = "reply_package"
    const val EXTRA_REPLY_ID = "reply_id"
    const val EXTRA_JOB_ID = "job_id"
    const val EXTRA_RESULT = "result"

    /**
     * The reserved key 保存中核 matches to put a 「電池最適化を除外」 button on the failed row —
     * sent ONLY when that button can actually fix it (see [refusal]).
     */
    const val NO_FOREGROUND_START = "ERROR:no-foreground-start"

    /**
     * The terminal reply: a FRESH broadcast, never a binder. No `ResultReceiver`, `PendingIntent`
     * or `Messenger`, and never a reliance on the ordered-broadcast result — EMUI severs both
     * between third-party apps (verified on 白い熊's Mate XT, 2026-07-23).
     * [Intent.FLAG_INCLUDE_STOPPED_PACKAGES]: without it a backgrounded, stopped or never-launched
     * caller never hears us. **Never degrade to an implicit broadcast**: with no package there is
     * nobody to answer, so nothing is sent at all.
     *
     * §1 correlates on `reply_id` alone ([jobId] null); §2a passes the job id as both.
     */
    fun reply(context: Context, action: String?, pkg: String?, replyId: String, jobId: String?, result: String) {
        if (action.isNullOrEmpty() || pkg.isNullOrEmpty()) return
        context.applicationContext.sendBroadcast(
            Intent(action).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(EXTRA_REPLY_ID, replyId)
                jobId?.let { putExtra(EXTRA_JOB_ID, it) }
                putExtra(EXTRA_RESULT, result)
            }
        )
    }

    /**
     * What to answer when a foreground start is refused, at any of the FOUR sites (the receiver's
     * `startForegroundService`, the §1 service's `startForeground`, the provider's service start,
     * the §2a service's `startForeground`). A broadcast or a provider `call()` is a BACKGROUND
     * start on API 31+, so each can throw `ForegroundServiceStartNotAllowedException` — and only
     * when the app is cold, which is the unattended batch and the clean-phone restore.
     *
     * [NO_FOREGROUND_START] only when BOTH hold: the throwable is that exception (matched by class
     * NAME — it is API 31 and cannot be loaded on older devices for `instanceof`) AND the app is not
     * already battery-exempt. Otherwise the button could not repair the fault, and a descriptive
     * line is better than a dead-end button.
     */
    fun refusal(context: Context, t: Throwable?): String {
        val notAllowed = t?.javaClass?.name == "android.app.ForegroundServiceStartNotAllowedException"
        val exemptionWouldHelp = notAllowed && runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(context.packageName) == false
        }.getOrDefault(false)
        return if (exemptionWouldHelp) NO_FOREGROUND_START else "ERROR:cannot start export service: ${oneLine(t)}"
    }

    /** A reply is ONE line; `LIST_CATEGORIES` answers are newline-delimited, so a newline corrupts the wire. */
    fun oneLine(t: Throwable?): String =
        (t?.message ?: t?.javaClass?.simpleName ?: "foreground service refused")
            .replace('\n', ' ').replace('\r', ' ')

    // ---- the foreground notification, shared by both services -----------------------------------

    private const val CHANNEL = "shiroikuma_automation"

    fun notification(context: Context, text: String): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.shiroikuma_notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * `startForeground` with the manifest's `dataSync` type stated explicitly on API 29+ (the typed
     * overload exists from 29; on 34 the type is enforced against `FOREGROUND_SERVICE_DATA_SYNC`),
     * the plain overload below. Throws exactly as the platform does — the caller guards it.
     */
    fun goForeground(service: Service, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            service.startForeground(id, notification)
        }
    }

    fun stopForeground(service: Service) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service.stopForeground(true)
            }
        }
    }

    fun appLabel(context: Context): String = runCatching {
        context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    }.getOrDefault(context.packageName)
}
