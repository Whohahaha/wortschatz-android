package app.wortschatz

import android.app.Application
import android.content.Context
import app.wortschatz.core.CardType
import app.wortschatz.core.Entry
import app.wortschatz.core.NewWordOrder
import app.wortschatz.core.StudySettings
import app.wortschatz.core.imports.Lexicon
import app.wortschatz.data.Backup
import app.wortschatz.data.BookRepository
import app.wortschatz.data.SqliteStudyStore
import java.io.IOException
import java.util.zip.GZIPInputStream

class WortschatzApp : Application() {
    val store by lazy { SqliteStudyStore(this) }
    val books by lazy { BookRepository(this) }
    val backup by lazy { Backup(this, store) }
    val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    /** German grammar data (articles, plurals, verb forms) used to complete imported lists. */
    val lexicon: Lexicon by lazy {
        // The build tools unpack .gz assets into the APK as plain files, so de.tsv.gz arrives as de.tsv.
        val stream = try {
            assets.open("lexicon/de.tsv")
        } catch (e: IOException) {
            try {
                GZIPInputStream(assets.open("lexicon/de.tsv.gz"))
            } catch (e: IOException) {
                null
            }
        }
        stream?.bufferedReader()?.useLines { Lexicon.parse(it) } ?: Lexicon.EMPTY
    }

    var activeBookId: String
        get() = prefs.getString(KEY_BOOK, null)?.takeIf { books.exists(it) } ?: BookRepository.BUILT_IN_ID
        set(value) = prefs.edit().putString(KEY_BOOK, value).apply()

    val entries: List<Entry> get() = books.entries(activeBookId)

    val studySettings: StudySettings
        get() = StudySettings(
            newWordsPerDay = prefs.getInt(KEY_NEW_PER_DAY, DEFAULT_NEW_PER_DAY),
            newWordOrder = NewWordOrder.entries.firstOrNull { it.name == prefs.getString(KEY_NEW_ORDER, null) } ?: NewWordOrder.BOOK,
            shuffleSeed = prefs.getLong(KEY_SHUFFLE_SEED, 0),
            disabledCardTypes = CardType.entries.filter { prefs.getBoolean(KEY_CARD_OFF_PREFIX + it.name, false) }.toSet(),
        )

    override fun onCreate() {
        super.onCreate()
        AudioPrepJob.schedule(this)
        Reminder.schedule(this)
    }

    companion object {
        const val KEY_BOOK = "active_book"
        const val KEY_NEW_PER_DAY = "new_words_per_day"
        const val DEFAULT_NEW_PER_DAY = 25
        const val KEY_NEW_ORDER = "new_word_order"
        const val KEY_SHUFFLE_SEED = "shuffle_seed"
        const val KEY_CARD_OFF_PREFIX = "card_off_"
    }
}
