package app.wortschatz

import android.app.Application
import app.wortschatz.core.Entry
import app.wortschatz.core.WordBook
import app.wortschatz.data.SqliteStudyStore

class WortschatzApp : Application() {
    val store by lazy { SqliteStudyStore(this) }

    val entries: List<Entry> by lazy {
        assets.open("wordbooks/goethe_a1_sample.tsv").bufferedReader().use { WordBook.parse(it.readText()) }
    }

    val wordBookTitle = "Goethe A1 示例词书"
}
