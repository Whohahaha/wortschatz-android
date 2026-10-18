package app.wortschatz.data

import android.content.Context
import app.wortschatz.core.Entry
import app.wortschatz.core.WordBook
import java.io.File

data class BookInfo(val id: String, val title: String, val wordCount: Int, val builtIn: Boolean)

/**
 * Word books: the bundled one in assets, plus imported ones saved in our own TSV format
 * under files/books/<id>.tsv. Entry ids of imported books are prefixed with the book id,
 * so cards of different books never collide in the study database.
 */
class BookRepository(private val context: Context) {
    private val dir = File(context.filesDir, "books")
    private val cache = HashMap<String, Pair<String, List<Entry>>>()

    @Synchronized
    fun list(): List<BookInfo> {
        val imported = dir.listFiles { f -> f.extension == "tsv" }.orEmpty()
            .sortedBy { it.lastModified() }
            .map { load(it.nameWithoutExtension) }
        return listOf(builtIn()) + imported.map { (id, title, entries) -> BookInfo(id, title, entries.size, builtIn = false) }
    }

    @Synchronized
    fun entries(id: String): List<Entry> = load(id).third

    @Synchronized
    fun info(id: String): BookInfo = if (id == BUILT_IN_ID) builtIn() else load(id).let { BookInfo(id, it.second, it.third.size, false) }

    fun exists(id: String) = id == BUILT_IN_ID || File(dir, "$id.tsv").exists()

    /** Saves [entries] as a new book; their ids are replaced with "<book id>~<n>". */
    @Synchronized
    fun add(title: String, entries: List<Entry>): BookInfo {
        val id = "b" + System.currentTimeMillis().toString(36)
        val renumbered = entries.mapIndexed { i, e -> e.copy(id = "$id~${i + 1}") }
        dir.mkdirs()
        File(dir, "$id.tsv").writeText(WordBook.write(renumbered, title))
        cache[id] = title to renumbered
        return BookInfo(id, title, renumbered.size, builtIn = false)
    }

    @Synchronized
    fun delete(id: String) {
        if (id == BUILT_IN_ID) return
        File(dir, "$id.tsv").delete()
        cache.remove(id)
    }

    private fun builtIn(): BookInfo = BookInfo(BUILT_IN_ID, BUILT_IN_TITLE, load(BUILT_IN_ID).third.size, builtIn = true)

    private fun load(id: String): Triple<String, String, List<Entry>> {
        val (title, entries) = cache.getOrPut(id) {
            if (id == BUILT_IN_ID) {
                BUILT_IN_TITLE to context.assets.open(BUILT_IN_ASSET).bufferedReader().use { WordBook.parse(it.readText()) }
            } else {
                val text = File(dir, "$id.tsv").takeIf { it.exists() }?.readText().orEmpty()
                (WordBook.title(text) ?: "导入的词书") to if (text.isEmpty()) emptyList() else WordBook.parse(text)
            }
        }
        return Triple(id, title, entries)
    }

    companion object {
        const val BUILT_IN_ID = "builtin"
        const val BUILT_IN_TITLE = "Goethe A1 示例词书"
        private const val BUILT_IN_ASSET = "wordbooks/goethe_a1_sample.tsv"
    }
}
