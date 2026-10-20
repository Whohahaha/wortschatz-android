package app.wortschatz.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything a learner would miss after reinstalling, in one zip: the study database (progress
 * and review history), settings, and imported word books.
 *
 * A copy is written to Download/Wortschatz once a day, outside the app's own storage, so it
 * survives an uninstall and can be restored on a new install or a new phone.
 */
class Backup(private val context: Context, private val store: SqliteStudyStore) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val booksDir = File(context.filesDir, "books")

    fun export(out: OutputStream) {
        val db = store.snapshot()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(JSONObject().put("format", FORMAT).put("created", System.currentTimeMillis()).toString().toByteArray())
            zip.putNextEntry(ZipEntry(DB))
            db.inputStream().use { it.copyTo(zip) }
            zip.putNextEntry(ZipEntry(SETTINGS))
            zip.write(prefsToJson().toString().toByteArray())
            booksDir.listFiles().orEmpty().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry("books/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
            }
        }
    }

    /**
     * Replaces progress, settings and imported books with the backup's. The app has to restart
     * afterwards, since the open database and session no longer match what is on disk.
     * Throws if [input] is not a Wortschatz backup; nothing is changed in that case.
     */
    fun restore(input: InputStream) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var settings: JSONObject? = null
        var sawManifest = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == MANIFEST -> sawManifest = true
                    name == SETTINGS -> settings = JSONObject(zip.readBytes().decodeToString())
                    name == DB -> File(staging, DB).outputStream().use { zip.copyTo(it) }
                    name.startsWith("books/") && !name.contains("..") ->
                        File(staging, name).apply { parentFile?.mkdirs() }.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val db = File(staging, DB)
        require(sawManifest && db.isFile) { "这不是 Wortschatz 的备份文件" }

        store.replaceWith(db)
        booksDir.deleteRecursively()
        File(staging, "books").takeIf { it.isDirectory }?.copyRecursively(booksDir)
        settings?.let(::jsonToPrefs)
        staging.deleteRecursively()
    }

    /** Writes the daily copy to Download/Wortschatz if the last one is older than [maxAgeMs]. */
    fun autoBackup(maxAgeMs: Long = 20 * HOUR): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (System.currentTimeMillis() - lastAutoBackup < maxAgeMs) return false
        val uri = prefs.getString(KEY_AUTO_URI, null)?.let(Uri::parse)
        val written = uri?.let(::tryWrite) == true || createFile(AUTO_NAME)?.let { tryWrite(it).also { ok -> if (ok) prefs.edit().putString(KEY_AUTO_URI, it.toString()).apply() } } == true
        if (written) prefs.edit().putLong(KEY_AUTO_TIME, System.currentTimeMillis()).apply()
        return written
    }

    val lastAutoBackup: Long get() = prefs.getLong(KEY_AUTO_TIME, 0)

    private fun tryWrite(uri: Uri): Boolean = try {
        context.contentResolver.openOutputStream(uri, "wt")?.use(::export) != null
    } catch (e: Exception) {
        false
    }

    /**
     * Writes a copy named [name] to Download/Wortschatz, apart from the daily one, so later daily
     * backups never overwrite it. Returns false if it could not be written.
     */
    fun saveCopy(name: String): Boolean {
        val uri = createFile(name) ?: return false
        return tryWrite(uri).also { ok -> if (!ok) runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    private fun createFile(name: String): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Wortschatz")
        }
        return try {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        }
    }

    // Values keep their type, so an Int setting does not come back as a Long.
    private fun prefsToJson(): JSONObject {
        val out = JSONObject()
        for ((key, value) in prefs.all) {
            if (key in DEVICE_ONLY) continue
            val (type, v) = when (value) {
                is Boolean -> "b" to value
                is Int -> "i" to value
                is Long -> "l" to value
                is Float -> "f" to value.toDouble()
                is String -> "s" to value
                is Set<*> -> "ss" to JSONArray(value.toList())
                else -> continue
            }
            out.put(key, JSONObject().put("t", type).put("v", v))
        }
        return out
    }

    private fun jsonToPrefs(json: JSONObject) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it !in DEVICE_ONLY }.forEach(edit::remove)
        for (key in json.keys()) {
            if (key in DEVICE_ONLY) continue
            val o = json.getJSONObject(key)
            when (o.getString("t")) {
                "b" -> edit.putBoolean(key, o.getBoolean("v"))
                "i" -> edit.putInt(key, o.getInt("v"))
                "l" -> edit.putLong(key, o.getLong("v"))
                "f" -> edit.putFloat(key, o.getDouble("v").toFloat())
                "s" -> edit.putString(key, o.getString("v"))
                "ss" -> edit.putStringSet(key, o.getJSONArray("v").let { a -> (0 until a.length()).map(a::getString).toSet() })
            }
        }
        edit.commit()
    }

    companion object {
        const val PREFS = "settings"
        private const val FORMAT = 1
        private const val MANIFEST = "wortschatz-backup.json"
        private const val DB = "wortschatz.db"
        private const val SETTINGS = "settings.json"
        const val AUTO_NAME = "wortschatz-backup.zip"
        private const val HOUR = 60 * 60 * 1000L
        private const val KEY_AUTO_URI = "auto_backup_uri"
        private const val KEY_AUTO_TIME = "auto_backup_time"
        /** Belong to this install only: where its own backup file lives. */
        private val DEVICE_ONLY = setOf(KEY_AUTO_URI, KEY_AUTO_TIME)
    }
}
