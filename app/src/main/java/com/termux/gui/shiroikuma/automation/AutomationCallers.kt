package com.termux.gui.shiroikuma.automation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import java.security.MessageDigest

/**
 * shiroikuma-termux-gui fork: who is allowed through the automation data door, and how that is
 * decided (sister-app contract v2 §2a). Copied from the family file — deliberately
 * self-contained and app-independent.
 *
 * ## Why not a token
 *
 * A pasted secret cannot survive a wipe, which is fatal for the case the family exists to serve:
 * 応用管理 restoring apps and their data onto a clean phone where nothing is configured yet.
 *
 * ## Why not a `shiroikuma.*` prefix
 *
 * A package name is not an identity. What makes `getCallingPackage()` worth anything is that a
 * name cannot be taken *while the real package is installed* — any sideloaded app may call itself
 * `shiroikuma.evil` and pass a prefix test, and since the caller supplies the descriptor an export
 * is written into, a prefix check would hand it every sister app's data in turn: strictly weaker
 * than the token it replaces.
 *
 * ## What is checked, in order
 *
 * 1. **An exact name** from [CALLERS].
 * 2. **The uid agrees** — `getCallingPackage()` is the caller's declared attribution; the kernel's
 *    uid cannot be borrowed.
 * 3. **The signing certificate matches the pin** — closes the real gap: whichever caller package
 *    is absent from the device is a name anyone can take, and a clean phone is precisely a device
 *    where not everything is installed yet.
 */
object AutomationCallers {

    /**
     * The apps allowed to drive this one's data door: 応用管理 (backs up and restores) and
     * 自由作業盤 (runs the 保存復元 batch). An entry added here is a deliberate act.
     *
     * Pins are derived with `apksigner verify --print-certs <that app's signed release APK> | grep
     * 'SHA-256 digest'`. Every app in the family has its own keystore, so there is no shared key
     * to compare against and each caller is pinned by name. **If a caller's key is ever rotated its
     * calls stop working and the fix is these constants** — the intended failure.
     */
    private val CALLERS = mapOf(
        "shiroikuma.oyokanri" to "9c585f4d118cb97ff653f949a8872875548403b9083ce6b9baa2e8f0c55ac6cc",
        "shiroikuma.jiyusagyoban" to "efd0d352192651593a92288ecdc64fc87262ec8648c24ed8f51a5587d46ac602",
    )

    /** A refusal that says only "no" cannot be debugged across an IPC boundary — each reason is a string. */
    sealed interface Verdict {
        object Allowed : Verdict
        data class Refused(val why: String) : Verdict
    }

    fun verify(context: Context, declared: String?): Verdict {
        val name = declared?.takeIf { it.isNotEmpty() }
            ?: return Verdict.Refused("ERROR:caller unknown")
        val pin = CALLERS[name] ?: return Verdict.Refused("ERROR:caller not permitted: $name")

        val real = runCatching {
            context.packageManager.getPackagesForUid(Binder.getCallingUid())
        }.getOrNull().orEmpty()
        if (name !in real) return Verdict.Refused("ERROR:caller uid mismatch: $name")

        val signature = signingSha256(context, name)
            ?: return Verdict.Refused("ERROR:caller signature unreadable: $name")
        if (!MessageDigest.isEqual(signature.toByteArray(), pin.toByteArray())) {
            return Verdict.Refused("ERROR:caller signature mismatch: $name")
        }
        return Verdict.Allowed
    }

    /**
     * SHA-256 of the caller's current signing certificate, lower-case hex. `signingInfo` (API 28+)
     * reports the certificate actually in force; below 28 (this app's minSdk is 24) the deprecated
     * `signatures` array IS the signing certificate. More than one current signer → null (refused):
     * every app in the family has exactly one key.
     */
    private fun signingSha256(context: Context, pkg: String): String? = runCatching {
        val pm = context.packageManager
        val certs: Array<out android.content.pm.Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
        val only = certs?.singleOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(only.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
