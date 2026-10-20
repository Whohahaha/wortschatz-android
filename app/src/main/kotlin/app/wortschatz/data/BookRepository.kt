package app.wortschatz.data

import android.content.Context
import app.wortschatz.core.Entry
import app.wortschatz.core.WordBook
import java.io.File

enum class BookKind { BUILT_IN, DOWNLOADED, IMPORTED }

data class BookInfo(
    val id: String,
    val title: String,
    val wordCount: Int,
    val kind: BookKind,
    /** Catalog version of a downloaded book; 0 otherwise. */
    val version: Int = 0,
    /** Whether pronunciation recordings came with the book (bundled or downloaded). */
    val hasAudio: Boolean = false,
) {
    val builtIn: Boolean get() = kind == BookKind.BUILT_IN
}

/**
 * Word books: the bundled one in assets, books downloaded from the word book catalog, and
 * imported ones, all saved in our own TSV format under files/books/<id>.tsv.
 *
 * A downloaded book keeps the entry ids it was published with (a "<id>.version" file next to it
 * marks it as downloaded), and its recordings go to files/book-audio/<id>/. Entry ids of imported
 * books are prefixed with the book id. Either way, cards of different books never collide in the
 * study database, and since progress is stored there by entry id, deleting a book file never
 * loses it: downloading the same book again brings the progress back.
 */
class BookRepository(private val context: Context) {
    private val dir = File(context.filesDir, "books")
    val audioRoot = File(context.filesDir, "book-audio")
    private val cache = HashMap<String, Pair<String, List<Entry>>>()

    @Synchronized
    fun list(): List<BookInfo> {
        val stored = dir.listFiles { f -> f.extension == "tsv" }.orEmpty()
            .sortedBy { it.lastModified() }
            .map { info(it.nameWithoutExtension) }
        val (downloaded, imported) = stored.partition { it.kind == BookKind.DOWNLOADED }
        return listOf(builtIn()) + downloaded.sortedBy { it.title } + imported
    }

    @Synchronized
    fun entries(id: String): List<Entry> = load(id).second

    @Synchronized
    fun info(id: String): BookInfo {
        if (id == BUILT_IN_ID) return builtIn()
        val (title, entries) = load(id)
        val version = installedVersion(id)
        return if (version > 0) {
            BookInfo(id, title, entries.size, BookKind.DOWNLOADED, version, hasAudio = File(audioRoot, id).isDirectory)
        } else {
            BookInfo(id, title, entries.size, BookKind.IMPORTED)
        }
    }

    fun exists(id: String) = id == BUILT_IN_ID || File(dir, "$id.tsv").exists()

    /** Version of a downloaded book on the phone, or 0 when it is not downloaded. */
    fun installedVersion(id: String): Int =
        File(dir, "$id.version").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0

    /** Saves [entries] as a new book; their ids are replaced with "<book id>~<n>". */
    @Synchronized
    fun add(title: String, entries: List<Entry>): BookInfo {
        val id = "b" + System.currentTimeMillis().toString(36)
        val renumbered = entries.mapIndexed { i, e -> e.copy(id = "$id~${i + 1}") }
        dir.mkdirs()
        File(dir, "$id.tsv").writeText(WordBook.write(renumbered, title))
        cache[id] = title to renumbered
        return BookInfo(id, title, renumbered.size, BookKind.IMPORTED)
    }

    /**
     * Installs (or updates) a downloaded book from files already checked: [tsv] is moved into
     * place and [audioDir], if any, replaces the book's recordings. Entry ids are kept as published.
     */
    @Synchronized
    fun install(id: String, version: Int, tsv: File, audioDir: File?) {
        require(id != BUILT_IN_ID)
        dir.mkdirs()
        if (audioDir != null) {
            audioRoot.mkdirs()
            val target = File(audioRoot, id)
            val old = File(audioRoot, "$id.old")
            old.deleteRecursively()
            if (target.exists()) check(target.renameTo(old)) { "could not replace audio" }
            check(audioDir.renameTo(target)) { "could not install audio" }
            old.deleteRecursively()
        }
        val book = File(dir, "$id.tsv")
        if (!tsv.renameTo(book)) {
            tsv.copyTo(book, overwrite = true)
            tsv.delete()
        }
        File(dir, "$id.version").writeText(version.toString())
        cache.remove(id)
    }

    /** Removes the book's files. Progress stays in the study database. */
    @Synchronized
    fun delete(id: String) {
        if (id == BUILT_IN_ID) return
        File(dir, "$id.tsv").delete()
        File(dir, "$id.version").delete()
        File(audioRoot, id).deleteRecursively()
        cache.remove(id)
    }

    private fun builtIn(): BookInfo =
        BookInfo(BUILT_IN_ID, BUILT_IN_TITLE, load(BUILT_IN_ID).second.size, BookKind.BUILT_IN, hasAudio = true)

    private fun load(id: String): Pair<String, List<Entry>> = cache.getOrPut(id) {
        if (id == BUILT_IN_ID) {
            BUILT_IN_TITLE to context.assets.open(BUILT_IN_ASSET).bufferedReader().use { WordBook.parse(it.readText()) }
        } else {
            val text = File(dir, "$id.tsv").takeIf { it.exists() }?.readText().orEmpty()
            (WordBook.title(text) ?: "导入的词书") to if (text.isEmpty()) emptyList() else WordBook.parse(text)
        }
    }

    companion object {
        const val BUILT_IN_ID = "builtin"
        const val BUILT_IN_TITLE = "歌德 A1 词书"
        private const val BUILT_IN_ASSET = "wordbooks/goethe_a1.tsv"
    }
}
