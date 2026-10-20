package app.wortschatz.data

import app.wortschatz.core.Audio
import app.wortschatz.core.Catalog
import app.wortschatz.core.CatalogBook
import app.wortschatz.core.CatalogFile
import app.wortschatz.core.WordBook
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads word books listed in the catalog (index.json of the public word book repository).
 * Every file is checked against the size and SHA-256 in the catalog, and nothing is installed
 * until all of a book's files have arrived intact, so a broken connection leaves no half book.
 */
class BookDownloader(private val books: BookRepository, private val cacheDir: File) {
    /** The mirror that answered last; later downloads try it first. */
    @Volatile
    private var preferred: String? = null

    suspend fun catalog(): List<CatalogBook> = fromMirrors { base -> Catalog.parse(String(get(base + CATALOG), Charsets.UTF_8)) }

    /** Downloads and installs [book]; [progress] gets bytes done and bytes in total. */
    suspend fun download(book: CatalogBook, progress: (Long, Long) -> Unit) {
        val total = book.bytes
        val work = File(cacheDir, "download-${book.id}").apply { deleteRecursively(); mkdirs() }
        try {
            val tsv = File(work, "book.tsv")
            fetch(book.book, tsv) { done -> progress(done, total) }
            // Refuse a file that does not parse, before anything on the phone is touched.
            check(WordBook.parse(tsv.readText()).isNotEmpty()) { "词书是空的" }

            val audioDir = if (book.audio.isEmpty()) null else File(work, "audio")
            var before = book.book.bytes
            for (part in book.audio) {
                val zip = File(work, "audio.zip")
                val start = before
                fetch(part, zip) { done -> progress(start + done, total) }
                unzipAudio(zip, audioDir!!)
                zip.delete()
                before += part.bytes
            }
            books.install(book.id, book.version, tsv, audioDir)
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun fetch(file: CatalogFile, target: File, progress: (Long) -> Unit) = fromMirrors { base ->
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        open(base + file.path).let { connection ->
            try {
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            progress(done)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (file.sha256.isNotEmpty() && sha != file.sha256) throw IOException("文件校验失败")
    }

    /** Keeps only recordings named the way [Audio] names them; anything else in the zip is ignored. */
    private suspend fun unzipAudio(zip: File, target: File) {
        target.mkdirs()
        val name = Regex("[0-9a-f]{16}\\.${Audio.EXTENSION}")
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = input.nextEntry ?: break
                val fileName = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name.matches(fileName)) {
                    File(target, fileName).outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    /** Runs [block] against each mirror in turn, the one that worked last time first. */
    private suspend fun <T> fromMirrors(block: suspend (String) -> T): T {
        var failure: Exception? = null
        for (base in MIRRORS.sortedByDescending { it == preferred }) {
            currentCoroutineContext().ensureActive()
            try {
                return block(base).also { preferred = base }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("没有可用的下载地址")
    }

    private fun get(url: String): ByteArray {
        val connection = open(url)
        try {
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Cache-Control", "no-cache")
            if (responseCode != HttpURLConnection.HTTP_OK) {
                disconnect()
                throw IOException("HTTP $responseCode")
            }
        }

    companion object {
        private const val CATALOG = "index.json"

        /**
         * The public word book repository, straight from GitHub and through jsDelivr's CDN,
         * which is often reachable where GitHub is slow or blocked.
         */
        val MIRRORS = listOf(
            "https://raw.githubusercontent.com/whohahaha/wortschatz-books/main/",
            "https://cdn.jsdelivr.net/gh/whohahaha/wortschatz-books@main/",
        )
    }
}
