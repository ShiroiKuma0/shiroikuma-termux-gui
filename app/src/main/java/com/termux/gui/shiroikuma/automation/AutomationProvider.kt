package com.termux.gui.shiroikuma.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.termux.gui.shiroikuma.TermuxGuiExport
import org.json.JSONArray
import org.json.JSONObject

/**
 * shiroikuma-termux-gui fork: the **data door** of sister-app contract v2 (§2a) — export this app's
 * state into a caller-supplied descriptor, and put it back, for a caller we can actually identify.
 *
 * A broadcast cannot tell who sent it; a provider gets the caller's identity from the framework
 * ([AutomationCallers]: exact name → uid cross-check → pinned signing certificate). And a list
 * needs a synchronous answer: 応用管理 draws a row per installed app before any export exists.
 *
 * Four methods, all short, none carrying the payload — each answers a `Bundle` with `result` in
 * the same `OK:` / `ERROR:` grammar as §1: `describe` (the header JSON), `export` / `import`
 * (validate, hand the descriptor to [AutomationDataService], answer `OK:<job_id>` — the CALLEE
 * mints the id and it is the correlation key), `cancel` (`OK:cancelled`).
 *
 * **A refusal is returned, never thrown** — an exception across a binder reaches the caller as a
 * stack trace. **Nothing here touches app state that needs `Application.onCreate`**: a provider's
 * `onCreate` runs before it, and on a clean phone this call is what starts the process.
 *
 * `import` exists ONLY here: an import overwrites the app's settings, and the §1 receiver is
 * `exported="true"` with no permission.
 */
class AutomationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: return fail("ERROR:not ready")
        return try {
            // WHO, before WHAT.
            when (val verdict = AutomationCallers.verify(ctx, callingPackage)) {
                is AutomationCallers.Verdict.Refused -> return fail(verdict.why)
                AutomationCallers.Verdict.Allowed -> Unit
            }
            // Then this app's own switches; a token is IGNORED unless this app asks for one.
            AutomationAuth.refuse(ctx, extras?.getString(AutomationWire.EXTRA_TOKEN))?.let { return fail(it) }

            when (method) {
                METHOD_DESCRIBE -> ok(describe(ctx))
                METHOD_EXPORT -> start(ctx, extras, importing = false)
                METHOD_IMPORT -> start(ctx, extras, importing = true)
                METHOD_CANCEL -> {
                    AutomationJobs.cancel(extras?.getString(AutomationWire.EXTRA_JOB_ID))
                    ok("OK:cancelled")
                }
                else -> fail("ERROR:unknown method: $method")
            }
        } catch (t: Throwable) {
            fail("ERROR:${AutomationWire.oneLine(t)}")
        }
    }

    /**
     * What this app would export, answered without exporting anything — and kept OUT of the
     * archive, so 応用管理 can draw a row before an export exists and judge compatibility before
     * streaming into an app that would reject it.
     *
     * `requires_permissions` is `[]` — the restore writes this app's own encrypted prefs and
     * touches no permission-guarded provider. `requires_launch_first` is false — a provider call
     * starts the process, which is all the initialisation the import needs. `contains` names the
     * one tighten-only key so 白い熊 sees what a restore would touch before choosing to run one.
     */
    private fun describe(ctx: Context): String {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
        else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        val header = JSONObject()
            .put("app_id", ctx.packageName)
            .put("version_code", versionCode)
            .put("version_name", pkg.versionName.orEmpty())
            .put("format", FORMAT)
            .put("min_format_readable", MIN_FORMAT_READABLE)
            .put("requires_launch_first", false)
            .put("requires_permissions", JSONArray())
            .put("contains", JSONArray(TermuxGuiExport.Cat.defaults().map { ctx.getString(it.labelRes) } +
                    listOf("JavaScript-without-dialog: tighten-only on restore")))
        return "OK:$header"
    }

    /**
     * Hand the descriptor to the foreground service and get out of the way. The descriptor is
     * **`dup()`ed before it leaves this method** — the one in [extras] belongs to the binder
     * transaction and is closed the moment `call()` returns. If the service cannot be started
     * (site 3 of four: a provider `call()` is a background start), the dup is closed, the job
     * dropped and the refusal RETURNED — no `OK:<job_id>` is ever handed out for a job that will
     * not run, so nothing double-answers.
     */
    private fun start(ctx: Context, extras: Bundle?, importing: Boolean): Bundle {
        @Suppress("DEPRECATION")
        val fd = extras?.getParcelable<ParcelFileDescriptor>(KEY_FD) ?: return fail("ERROR:no descriptor")
        val dup = runCatching { fd.dup() }.getOrNull() ?: return fail("ERROR:descriptor unusable")
        val jobId = AutomationJobs.begin()
        val refusal = AutomationDataService.start(ctx, jobId, dup, importing, extras)
        if (refusal != null) {
            AutomationJobs.finish(jobId)
            return fail(refusal)
        }
        return ok("OK:$jobId")
    }

    private fun ok(result: String) = Bundle().apply { putString(AutomationWire.EXTRA_RESULT, result) }
    private fun fail(why: String) = Bundle().apply { putString(AutomationWire.EXTRA_RESULT, why) }

    // call()-only; refusing loudly beats an empty cursor that reads as "no data".
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? =
        throw UnsupportedOperationException("automation is call() only")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("automation is call() only")

    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    companion object {
        const val METHOD_DESCRIBE = "describe"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_CANCEL = "cancel"
        const val KEY_FD = "fd"

        /** This app's archive format — [TermuxGuiExport.VERSION], so the two never drift. */
        const val FORMAT = TermuxGuiExport.VERSION

        /** The oldest archive this build can read. */
        const val MIN_FORMAT_READABLE = 1
    }
}
