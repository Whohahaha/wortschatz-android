package app.wortschatz.core.imports

import app.wortschatz.core.Entry
import app.wortschatz.core.WordBook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Minimal SQL access, so Anki collections can be read with Android's SQLite or JDBC in tests. */
interface SqlDatabase : AutoCloseable {
    fun tables(): Set<String>
    /** Runs [sql] and returns every row with each column as text (null stays null). */
    fun query(sql: String): List<List<String?>>
}

/** A file picked for import, recognised by content rather than by its extension. */
sealed interface ImportSource {
    val name: String

    /** Our own word-book format: imported as-is, no field mapping needed. */
    data class Native(override val name: String, val entries: List<Entry>) : ImportSource

    /** A table whose columns the user maps to German / meaning / plural …; from text or Anki. */
    data class Tabular(override val name: String, val table: Table, val kind: Kind) : ImportSource

    enum class Kind(val label: String) { TEXT("表格文本"), ANKI("Anki 卡组") }

    companion object {
        private val nativeColumns = setOf("id", "level", "pos", "lemma", "meaning")

        /**
         * @param openDatabase opens an SQLite file (Android SQLiteDatabase or JDBC)
         * @param zstd wraps a zstd-compressed stream (needed for Anki 2.1.50+ packages)
         */
        fun detect(
            name: String,
            bytes: ByteArray,
            openDatabase: (File) -> SqlDatabase,
            zstd: (InputStream) -> InputStream,
            tempDir: File,
        ): ImportSource {
            if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                return Tabular(name, AnkiPackage.read(bytes, openDatabase, zstd, tempDir), Kind.ANKI)
            }
            val text = decode(bytes)
            val header = text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?.split('\t')?.map { it.trim() }?.toSet().orEmpty()
            if (header.containsAll(nativeColumns)) return Native(name, WordBook.parse(text))
            return Tabular(name, DelimitedText.parse(text), Kind.TEXT)
        }

        /** UTF-8 when valid, otherwise GB18030, which WPS and older Excel use for Chinese text. */
        fun decode(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }
}

/**
 * Reads the notes of an Anki .apkg / .colpkg into a [Table], one column per note field.
 * Handles all three collection formats: collection.anki21b (zstd, Anki 2.1.50+),
 * collection.anki21 and the legacy collection.anki2. When a deck mixes note types, the
 * most common one is used.
 */
object AnkiPackage {
    private val collectionNames = listOf("collection.anki21b", "collection.anki21", "collection.anki2")

    fun read(bytes: ByteArray, openDatabase: (File) -> SqlDatabase, zstd: (InputStream) -> InputStream, tempDir: File): Table {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name in collectionNames) files[e.name] = zip.readBytes()
            }
        }
        val name = collectionNames.firstOrNull { it in files } ?: error("这个文件里没有 Anki 卡组数据")
        val data = files.getValue(name).let { if (name.endsWith("b")) zstd(ByteArrayInputStream(it)).readBytes() else it }
        val dbFile = File.createTempFile("anki", ".db", tempDir)
        try {
            dbFile.writeBytes(data)
            return openDatabase(dbFile).use(::readNotes)
        } finally {
            dbFile.delete()
            File(dbFile.path + "-journal").delete()
        }
    }

    private fun readNotes(db: SqlDatabase): Table {
        val notes = db.query("SELECT mid, flds FROM notes ORDER BY id")
        if (notes.isEmpty()) error("这个 Anki 卡组里没有笔记")
        val mid = notes.groupingBy { it[0] }.eachCount().maxBy { it.value }.key.orEmpty()
        val fieldNames = if ("fields" in db.tables()) {
            db.query("SELECT name FROM fields WHERE ntid = $mid ORDER BY ord").mapNotNull { it[0] }
        } else {
            val models = Json.parseToJsonElement(db.query("SELECT models FROM col").first()[0].orEmpty()).jsonObject
            models[mid]?.jsonObject?.get("flds")?.jsonArray?.map { it.jsonObject.getValue("name").jsonPrimitive.content }.orEmpty()
        }
        val rows = notes.filter { it[0] == mid }.map { it[1].orEmpty().split('\u001f') }
        val width = maxOf(fieldNames.size, rows.maxOf { it.size })
        val headers = (0 until width).map { fieldNames.getOrNull(it) ?: "字段 ${it + 1}" }
        return Table(headers, rows.map { it + List(width - it.size) { "" } })
    }
}
