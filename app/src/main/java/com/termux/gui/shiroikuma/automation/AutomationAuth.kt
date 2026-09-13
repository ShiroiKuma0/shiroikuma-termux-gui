package com.termux.gui.shiroikuma.automation

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * shiroikuma-termux-gui fork: the external-automation gate — a master switch that is ON, and a token
 * that is OFF (sister-app contract v2 §2).
 *
 * v1 shipped every app closed behind a pasted 48-character secret; a pasted secret cannot survive
 * a wipe, and the case this exists for is 応用管理 restoring apps and their data onto a clean phone
 * where nothing has been configured. So [enabled] defaults to **true** and [requireToken] to
 * **false**; the token still exists, regenerates, and never leaves the phone — it is opt-in.
 *
 * **A token handed to an app that does not require one is IGNORED, never an error.** Tokens live in
 * task arguments that outlive the setting they were pasted for.
 *
 * **Every write is `commit()`** — this gate fails OPEN: a lost `setEnabled(false)` (応用管理
 * force-stops the app with SIGKILL right after an import, which is exactly when 白い熊 is likely to
 * be flipping switches) would silently reopen the door.
 *
 * Device-local by design: its own prefs file, which [com.termux.gui.shiroikuma.TermuxGuiExport]
 * never reads. Who may open the DATA door is a separate question — [AutomationCallers].
 */
object AutomationAuth {

    private const val PREFS_FILE = "shiroikuma_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_REQUIRE_TOKEN = "automation_require_token"
    private const val KEY_TOKEN = "automation_token"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Whether this app answers automation at all. Default true (contract v2). */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    @SuppressLint("ApplySharedPref")
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).commit()
    }

    /** Whether a caller must present [token]. Default false — the token is opt-in. */
    fun requireToken(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false)

    @SuppressLint("ApplySharedPref")
    fun setRequireToken(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, value).commit()
    }

    /** The shared secret — 24 random bytes, hex; generated on first read so the row always shows one. */
    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: regenerateToken(context)

    @SuppressLint("ApplySharedPref")
    fun regenerateToken(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_TOKEN, token).commit()
        return token
    }

    /** Abbreviated form for the settings row — `80922d8c…4c49a87c`. */
    fun abbreviate(token: String): String =
        if (token.length <= 20) token else "${token.take(8)}…${token.takeLast(8)}"

    /** Constant-time ([MessageDigest.isEqual]) so a wrong token leaks nothing through timing. */
    fun isTokenValid(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), token(context).toByteArray())
    }

    /**
     * The whole gate, in ONE place every entry point asks — the receiver and the data door alike.
     * Null = proceed; otherwise the exact `ERROR:` string to answer with. "disabled" and "bad
     * token" stay distinct because they debug differently.
     */
    fun refuse(context: Context, candidate: String?): String? = when {
        !enabled(context) -> "ERROR:automation disabled"
        requireToken(context) && !isTokenValid(context, candidate) -> "ERROR:bad token"
        else -> null
    }
}
