package app.wortschatz

import android.app.Application
import android.content.Context
import app.wortschatz.core.Entry
import app.wortschatz.core.StudySettings
import app.wortschatz.core.imports.Lexicon
import app.wortschatz.data.BookRepository
import app.wortschatz.data.SqliteStudyStore
import java.io.IOException
import java.util.zip.GZIPInputStream

class WortschatzApp : Application() {
    val store by lazy { SqliteStudyStore(this) }
    val books by lazy { BookRepository(this) }
    val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    /** German grammar data (articles, plurals, verb forms) used to complete imported lists. */
    val lexicon: Lexicon by lazy {
        try {
            GZIPInputStream(assets.open("lexicon/de.tsv.gz")).bufferedReader().useLines { Lexicon.parse(it) }
        } catch (e: IOException) {
            Lexicon.EMPTY
        }
    }

    var activeBookId: String
        get() = prefs.getString(KEY_BOOK, null)?.takeIf { books.exists(it) } ?: BookRepository.BUILT_IN_ID
        set(value) = prefs.edit().putString(KEY_BOOK, value).apply()

    val entries: List<Entry> get() = books.entries(activeBookId)

    val studySettings: StudySettings get() = StudySettings(newWordsPerDay = prefs.getInt(KEY_NEW_PER_DAY, 10))

    override fun onCreate() {
        super.onCreate()
        AudioPrepJob.schedule(this)
    }

    companion object {
        const val KEY_BOOK = "active_book"
        const val KEY_NEW_PER_DAY = "new_words_per_day"
    }
}
