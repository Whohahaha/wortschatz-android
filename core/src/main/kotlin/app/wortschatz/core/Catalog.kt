package app.wortschatz.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A downloadable file of a book: its path relative to the catalog, size and SHA-256. */
data class CatalogFile(val path: String, val bytes: Long, val sha256: String)

/**
 * One word book offered for download. [version] goes up whenever its content changes; entry ids
 * stay the same across versions, so updating a book keeps the learner's progress.
 */
data class CatalogBook(
    val id: String,
    val title: String,
    val description: String,
    val level: String,
    val version: Int,
    val words: Int,
    val book: CatalogFile,
    /**
     * Zips of pronunciation recordings (<key>.webm, see [Audio]); empty when the book has none.
     * Split in parts so each stays below the file size limit of the CDN mirror.
     */
    val audio: List<CatalogFile>,
) {
    val bytes: Long get() = book.bytes + audio.sumOf { it.bytes }
}

/**
 * The list of downloadable word books, index.json in the word book repository:
 *
 * {"format": 1, "books": [{"id": "goethe_a2", "title": "…", "description": "…", "level": "A2",
 *   "version": 1, "words": 600, "book": {"path": "goethe_a2/goethe_a2-1.tsv", "bytes": …, "sha256": "…"},
 *   "audio": [{"path": "goethe_a2/goethe_a2-1-audio-1.zip", "bytes": …, "sha256": "…"}, …]}]}
 *
 * Paths carry the version, so a mirror that still serves an older index never mixes files.
 */
object Catalog {
    const val FORMAT = 1

    /** Book ids become file names on the phone, so they are kept to a safe alphabet. */
    private val ID = Regex("[a-z0-9_]{1,40}")

    fun parse(text: String): List<CatalogBook> {
        val root = Json.parseToJsonElement(text).jsonObject
        val format = root["format"]?.jsonPrimitive?.intOrNull ?: FORMAT
        require(format <= FORMAT) { "词书列表的格式比这个版本的 App 新，请先更新 App" }
        return root["books"]?.jsonArray.orEmpty().mapNotNull { element ->
            runCatching { book(element.jsonObject) }.getOrNull()
        }
    }

    private fun book(o: JsonObject): CatalogBook? {
        val id = o.str("id")
        if (!ID.matches(id)) return null
        return CatalogBook(
            id = id,
            title = o.str("title"),
            description = o["description"]?.jsonPrimitive?.content.orEmpty(),
            level = o["level"]?.jsonPrimitive?.content.orEmpty(),
            version = o.getValue("version").jsonPrimitive.int,
            words = o["words"]?.jsonPrimitive?.intOrNull ?: 0,
            book = file(o.getValue("book").jsonObject) ?: return null,
            audio = when (val audio = o["audio"]) {
                null -> emptyList()
                is JsonArray -> audio.map { file(it.jsonObject) ?: return null }
                else -> listOf(file(audio.jsonObject) ?: return null)
            },
        )
    }

    private fun file(o: JsonObject): CatalogFile? {
        val path = o.str("path")
        // Relative paths only: never let a catalog point outside the repository.
        if (path.startsWith("/") || ".." in path || "://" in path) return null
        return CatalogFile(path, o["bytes"]?.jsonPrimitive?.longOrNull ?: 0, o.str("sha256").lowercase())
    }

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
}

/** "12.3 MB", "820 KB" */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "${(bytes / 1000).coerceAtLeast(1)} KB"
}
