package com.termux.gui.shiroikuma

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import com.termux.gui.R
import com.termux.gui.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * shiroikuma-termux-gui fork: the headless export / import core of 白い熊 Termux GUI.
 *
 * ONE implementation, three thin callers: the Export / Import panel ([ExportImportPanel]), the
 * 保存復元 broadcast receiver ([com.termux.gui.shiroikuma.automation.StateExportService]) and the
 * data door ([com.termux.gui.shiroikuma.automation.AutomationDataService]). None of them carries
 * export logic of its own.
 *
 * ## What is backed up
 *
 * This app keeps no plain `SharedPreferences` file at all. Its four settings (service timeout,
 * background, log level, JavaScript prompt — [Settings]) live in the encrypted
 * `settings_prefs`, read and written here through the very handle `Settings` opens, so the dump
 * carries the decrypted values and a restore lands in the same keyset. That is the one category,
 * `settings`.
 *
 * Deliberately NOT exported: `widgets_prefs` (the widget id ↔ uuid mapping — an `appWidgetId` is
 * dead after a reinstall, so the mapping is meaningless on any device but the one it was made on,
 * and on that device it is still there); the automation prefs (`shiroikuma_automation`: switch,
 * token) and the export-directory prefs (`shiroikuma_eximport`), both device-local by design.
 *
 * ## The archive
 *
 * `shiroikuma-termux-gui_<yyyy-MM-dd_HH-mm-ss>.zip` — the family's file-name convention, ONE zip per
 * export, written as `<name>.part` and renamed only when complete. Inside: `manifest.json` first
 * (`format`, `version`, `app`, `appVersion`, `createdTs`, `categories[]`), then `settings.json` —
 * `{"<prefs file>":{"<key>":{"t":"int|long|float|bool|string|set","v":…}}}`.
 *
 * ## The import
 *
 * Merges per key, only the categories the archive carries, every write `commit()`ed (応用管理
 * SIGKILLs the process the instant it hears `OK`, so an `apply()` still in flight is lost). One
 * key is **tighten-only**: `javascript` ("Allow Javascript without dialog") disables a security
 * prompt, so a restore may switch it off but never on — contract v2 §2a, an import must not
 * silently weaken a different surface.
 */
object TermuxGuiExport {

    const val FORMAT = "shiroikuma-termux-gui"
    const val VERSION = 1
    const val EXPORT_PREFIX = "shiroikuma-termux-gui_"

    /** The encrypted prefs file [Settings] keeps the app's settings in, and its keys. */
    const val SETTINGS_PREFS = "settings_prefs"
    private const val KEY_JAVASCRIPT = "javascript"

    /** Where the 白い熊 UI page keeps its own state (nothing yet — Reset clears it). */
    const val UI_PREFS = "shiroikuma_ui"

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val SETTINGS_ENTRY = "settings.json"

    /**
     * A selectable category. [id] is what `items` / `LIST_CATEGORIES` speak and what the manifest
     * lists; [parentId] and [onByDefault] exist for the contract's picker grammar (this app's list
     * is flat and everything is on).
     */
    enum class Cat(
        val id: String,
        @StringRes val labelRes: Int,
        val parentId: String? = null,
        val onByDefault: Boolean = true
    ) {
        SETTINGS("settings", R.string.shiroikuma_eim_cat_settings);

        companion object {
            fun byId(id: String): Cat? = entries.firstOrNull { it.id == id }
            fun all(): Set<Cat> = entries.toSet()
            fun defaults(): Set<Cat> = entries.filter { it.onByDefault }.toSet()

            /** Declaration order — the order [export] walks them, so a position names a category. */
            fun ordered(cats: Set<Cat>): List<Cat> = entries.filter { it in cats }

            /**
             * Resolve a comma-separated `items` list. Null on any unknown id; absent/blank means the
             * DEFAULT set — the ones `LIST_CATEGORIES` reports `on` — not everything.
             */
            fun resolve(items: String?): Set<Cat>? {
                if (items.isNullOrBlank()) return defaults()
                val wanted = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val found = wanted.mapNotNull { byId(it) }
                return if (found.size == wanted.size) found.toSet() else null
            }
        }
    }

    /** Thrown out of [export] when the caller's cancel signal goes up; the caller deletes the partial. */
    class Cancelled : IOException("cancelled")

    // ---- file naming ----------------------------------------------------------------------------

    fun exportFileName(): String =
        EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"

    fun isExportFileName(name: String?): Boolean =
        name != null && name.startsWith(EXPORT_PREFIX) && name.endsWith(".zip")

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    // ---- the export directory (a persisted SAF tree; device-local, never exported) ----------------

    private const val EXIMPORT_PREFS = "shiroikuma_eximport"
    private const val KEY_DIR_URI = "dir_uri"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private fun eximportPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)

    /** The tree last chosen, grant or no grant — so the picker can open right there. */
    fun exportDirUri(context: Context): Uri? =
        eximportPrefs(context).getString(KEY_DIR_URI, null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** Persist a tree picked with `ACTION_OPEN_DOCUMENT_TREE`, taking the persistable grant. */
    fun setExportDirUri(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        eximportPrefs(context).edit().putString(KEY_DIR_URI, uri.toString()).commit()
    }

    /** The configured directory, or null when unset / no longer reachable. */
    fun exportDir(context: Context): DocumentFile? =
        exportDirUri(context)
            ?.let { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
            ?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }

    /**
     * Best-effort real filesystem path of a SAF tree (+ file), so rows and replies show an absolute
     * path rather than a bare folder label. Only primary storage resolves; anything else is null.
     */
    fun absolutePathOf(treeUri: Uri?, fileName: String?): String? {
        if (treeUri == null || treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        if (!docId.startsWith("primary:")) return null
        val rel = docId.removePrefix("primary:").trim('/')
        val base = Environment.getExternalStorageDirectory().absolutePath
        val path = if (rel.isEmpty()) base else "$base/$rel"
        return if (fileName == null) path else "$path/$fileName"
    }

    /** The directory as shown on the page: absolute path when resolvable, its name otherwise, null when unset. */
    fun dirLabel(context: Context): String? {
        val dir = exportDir(context) ?: return null
        return absolutePathOf(dir.uri, null) ?: dir.name
    }

    /** A directory that is remembered but no longer granted (a restore, a reinstall) — named, not usable. */
    fun rememberedDirLabel(context: Context): String? {
        if (exportDir(context) != null) return null
        val uri = exportDirUri(context) ?: return null
        absolutePathOf(uri, null)?.let { return it }
        val last = uri.lastPathSegment ?: return uri.toString()
        val colon = last.lastIndexOf(':')
        return if (colon >= 0 && colon + 1 < last.length) last.substring(colon + 1) else last
    }

    /** Our backups in the configured directory, newest first (a ContentResolver walk — call off the main thread). */
    fun listExports(context: Context): List<DocumentFile> {
        val dir = exportDir(context) ?: return emptyList()
        return runCatching {
            dir.listFiles().filter { it.isFile && isExportFileName(it.name) }
                .sortedByDescending { it.lastModified() }
        }.getOrDefault(emptyList())
    }

    fun newestExport(context: Context): DocumentFile? = listExports(context).firstOrNull()

    // ---- export ----------------------------------------------------------------------------------

    /**
     * Write a ZIP of [cats] to [out] (which the caller closes). Returns the number of categories
     * written. [onProgress] is `(position, total, cat)` — position 1 while the first category is
     * being written, a final call with `position == total`. [isCancelled] is polled at entry
     * boundaries only, never mid-write, and unwinds with [Cancelled].
     */
    @Throws(IOException::class)
    fun export(
        context: Context,
        cats: Set<Cat>,
        out: OutputStream,
        onProgress: ((Int, Int, Cat) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Int {
        val ordered = Cat.ordered(cats)
        if (ordered.isEmpty()) throw IOException("no categories selected")
        val app = context.applicationContext
        var count = 0
        val zip = ZipOutputStream(out)
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", app.packageName)
            .put("appVersion", appVersionName(app))
            .put("createdTs", System.currentTimeMillis())
            .put("categories", JSONArray(ordered.map { it.id }))
        writeEntry(zip, MANIFEST_ENTRY, manifest.toString(2))
        for ((i, cat) in ordered.withIndex()) {
            if (isCancelled?.invoke() == true) throw Cancelled()
            onProgress?.invoke(i + 1, ordered.size, cat)
            when (cat) {
                Cat.SETTINGS -> writeEntry(zip, SETTINGS_ENTRY, settingsJson(app))
            }
            count++
        }
        zip.finish()
        zip.flush()
        return count
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** `{"settings_prefs": {key: {t, v}}}` — the app's prefs files, keyed by file name. */
    private fun settingsJson(context: Context): String {
        val files = JSONObject()
        files.put(SETTINGS_PREFS, dumpPrefs(Settings.instance.settingsPreferences(context)))
        return files.toString(2)
    }

    private fun dumpPrefs(sp: SharedPreferences?): JSONObject {
        val obj = JSONObject()
        val all = runCatching { sp?.all }.getOrNull() ?: return obj
        for ((k, v) in all) {
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "bool").put("v", v)
                is Int -> e.put("t", "int").put("v", v)
                is Long -> e.put("t", "long").put("v", v)
                is Float -> e.put("t", "float").put("v", v.toDouble())
                is String -> e.put("t", "string").put("v", v)
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            obj.put(k, e)
        }
        return obj
    }

    // ---- import ----------------------------------------------------------------------------------

    /** The categories an archive carries (its manifest, else its entries); empty = not one of ours. */
    fun categoriesIn(zip: ByteArray): Set<Cat> {
        val files = runCatching { readZip(zip) }.getOrNull() ?: return emptySet()
        files[MANIFEST_ENTRY]?.let { mf ->
            val ids = runCatching { JSONObject(mf.decodeToString()).optJSONArray("categories") }.getOrNull()
            if (ids != null) {
                val set = (0 until ids.length()).mapNotNull { Cat.byId(ids.optString(it)) }.toSet()
                if (set.isNotEmpty()) return set
            }
        }
        return Cat.entries.filter { files.containsKey(entryOf(it)) }.toSet()
    }

    private fun entryOf(cat: Cat): String = when (cat) {
        Cat.SETTINGS -> SETTINGS_ENTRY
    }

    /**
     * Apply [cats] from an archive; categories the archive lacks are skipped. Returns one summary
     * line per category (`Settings: 4 values`), plus what was deliberately held back.
     */
    @Throws(IOException::class)
    fun import(context: Context, zip: ByteArray, cats: Set<Cat>): String {
        val app = context.applicationContext
        val files = readZip(zip)
        if (files.isEmpty()) throw IOException("empty or unreadable archive")
        val lines = mutableListOf<String>()
        for (cat in Cat.ordered(cats)) {
            val data = files[entryOf(cat)] ?: continue
            val label = app.getString(cat.labelRes).substringBefore(" (")
            when (cat) {
                Cat.SETTINGS -> {
                    val r = importSettings(app, data.decodeToString())
                    lines.add("$label: ${r.written} values" + (r.note?.let { " — $it" } ?: ""))
                }
            }
        }
        // The running process must see what was restored: Settings caches its fields.
        Settings.instance.load(app)
        return if (lines.isEmpty()) "nothing imported" else lines.joinToString("\n")
    }

    private class Merged(val written: Int, val note: String?)

    private fun importSettings(context: Context, json: String): Merged {
        val files = JSONObject(json)
        val dump = files.optJSONObject(SETTINGS_PREFS) ?: return Merged(0, null)
        val sp = Settings.instance.settingsPreferences(context)
            ?: throw IOException("secure settings storage unavailable")
        var note: String? = null
        // Tighten-only: never switch the JavaScript prompt OFF on this device from an archive.
        val wantsJavascript = dump.optJSONObject(KEY_JAVASCRIPT)?.let {
            it.optString("t") == "bool" && it.optBoolean("v")
        } ?: false
        val skip = if (wantsJavascript && !sp.getBoolean(KEY_JAVASCRIPT, false)) {
            note = "JavaScript-without-dialog kept off (tighten-only)"
            setOf(KEY_JAVASCRIPT)
        } else emptySet()
        return Merged(mergePrefs(sp, dump, skip), note)
    }

    /** Merge a typed dump into [sp] per key — never a wipe — with `commit()`. Returns values written. */
    private fun mergePrefs(sp: SharedPreferences, dump: JSONObject, skip: Set<String>): Int {
        val ed = sp.edit()
        var n = 0
        val keys = dump.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in skip) continue
            val e = dump.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "bool" -> ed.putBoolean(k, e.optBoolean("v"))
                "int" -> ed.putInt(k, e.optInt("v"))
                "long" -> ed.putLong(k, e.optLong("v"))
                "float" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "string" -> ed.putString(k, e.optString("v"))
                "set" -> {
                    val arr = e.optJSONArray("v") ?: JSONArray()
                    ed.putStringSet(k, (0 until arr.length()).map { arr.optString(it) }.toHashSet())
                }
                else -> continue
            }
            n++
        }
        ed.commit()
        return n
    }

    /** An empty `commit()` per file the restore spans — blocks until any queued write has landed. */
    fun flushPrefs(context: Context) {
        runCatching { Settings.instance.settingsPreferences(context)?.edit()?.commit() }
    }

    private fun readZip(zip: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val buf = ByteArrayOutputStream()
                    zis.copyTo(buf)
                    out[e.name] = buf.toByteArray()
                }
                e = zis.nextEntry
            }
        }
        return out
    }

    // ---- writing into the SAF directory, atomically ------------------------------------------------

    class Written(val doc: DocumentFile, val shownPath: String, val bytes: Long)

    /**
     * Export [cats] into [dir] as `<name>.part`, renamed to `<name>` only once the archive is
     * complete. On any failure or cancel the partial is deleted and the exception rethrown, so the
     * directory is left exactly as it was found.
     */
    @Throws(IOException::class)
    fun writeToTree(
        context: Context,
        dir: DocumentFile,
        cats: Set<Cat>,
        onProgress: ((Int, Int, Cat) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Written {
        val app = context.applicationContext
        val name = exportFileName()
        val partName = "$name.part"
        val part = dir.createFile("application/octet-stream", partName)
            ?: throw IOException("cannot create $partName in the export directory")
        var done = false
        try {
            val os = app.contentResolver.openOutputStream(part.uri, "w")
                ?: throw IOException("cannot open $partName for writing")
            os.use { export(app, cats, it, onProgress, isCancelled) }
            if (isCancelled?.invoke() == true) throw Cancelled()
            val final = finishPart(app, dir, part, name)
            done = true
            val shown = absolutePathOf(dir.uri, final.name ?: name) ?: "${dir.name}/${final.name ?: name}"
            return Written(final, shown, final.length())
        } finally {
            if (!done) runCatching { part.delete() }
        }
    }

    /** Rename the completed `.part`; where the provider refuses, copy into the final name instead. */
    private fun finishPart(context: Context, dir: DocumentFile, part: DocumentFile, name: String): DocumentFile {
        if (runCatching { part.renameTo(name) }.getOrDefault(false)) return part
        val final = dir.createFile("application/zip", name)
            ?: throw IOException("cannot create $name in the export directory")
        try {
            context.contentResolver.openInputStream(part.uri)?.use { input ->
                context.contentResolver.openOutputStream(final.uri, "w")?.use { out -> input.copyTo(out) }
                    ?: throw IOException("cannot open $name for writing")
            } ?: throw IOException("cannot read back $name.part")
        } catch (t: Throwable) {
            runCatching { final.delete() }
            throw t
        }
        runCatching { part.delete() }
        return final
    }
}
