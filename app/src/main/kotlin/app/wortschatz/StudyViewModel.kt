package app.wortschatz

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.wortschatz.core.Card
import app.wortschatz.core.Audio
import app.wortschatz.core.CardType
import app.wortschatz.core.Command
import app.wortschatz.core.Gesture
import app.wortschatz.core.GestureAction
import app.wortschatz.core.GestureScheme
import app.wortschatz.core.GestureSchemes
import app.wortschatz.core.resolve
import app.wortschatz.core.Counts
import app.wortschatz.core.Entry
import app.wortschatz.core.Gender
import app.wortschatz.core.NewWordOrder
import app.wortschatz.core.Cloze
import app.wortschatz.core.GermanNumbers
import app.wortschatz.core.Lookup
import app.wortschatz.core.LookupResult
import app.wortschatz.core.Typing
import app.wortschatz.core.Verdict
import app.wortschatz.core.Quiz
import app.wortschatz.core.QuizMode
import app.wortschatz.core.Milestone
import app.wortschatz.core.Milestones
import app.wortschatz.core.Stats
import app.wortschatz.core.WordProgress
import app.wortschatz.core.WordStatus
import app.wortschatz.core.confusables
import app.wortschatz.core.StudySession
import app.wortschatz.core.StudySettings
import app.wortschatz.core.formatInterval
import app.wortschatz.core.fsrs.Rating
import app.wortschatz.core.imports.ColumnRole
import app.wortschatz.core.imports.DelimitedText
import app.wortschatz.core.imports.ImportOptions
import app.wortschatz.core.imports.ImportResult
import app.wortschatz.core.imports.ImportSource
import app.wortschatz.core.imports.Importer
import app.wortschatz.core.imports.Table
import app.wortschatz.data.AndroidSqlDatabase
import app.wortschatz.data.BookInfo
import app.wortschatz.data.BookKind
import app.wortschatz.core.BookProgress
import app.wortschatz.core.CatalogBook
import app.wortschatz.core.bookProgress
import kotlinx.coroutines.Job
import com.github.luben.zstd.ZstdInputStream
import app.wortschatz.ui.SwipeSensitivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, STUDY, DONE, SETTINGS, GESTURES, BOOKS, IMPORT, DRILL, NUMBERS, WORDS, STATS }

data class HomeState(
    val wordBookTitle: String,
    val counts: Counts,
    val learnedWords: Int,
    val totalWords: Int,
    val reviewsToday: Int,
    val forecast: List<Int>,
    val newWordsPerDay: Int,
    val newWordOrder: NewWordOrder = NewWordOrder.BOOK,
    val disabledCardTypes: Set<CardType> = emptySet(),
    /** When the daily copy in Download/Wortschatz was last written; 0 = never. */
    val lastBackup: Long = 0,
    /** A fresh install with no progress yet: offer to restore a backup. */
    val offerRestore: Boolean = false,
    val slowSpeech: Boolean = false,
    val streak: Int = 0,
    val knownWords: Int = 0,
    /** Reviews due beyond today's limit, held back so a missed week is not one huge session. */
    val backlog: Int = 0,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = Reminder.DEFAULT_HOUR,
)

/** One round of the article speed drill. Practice only: answers here do not change the schedule. */
data class DrillState(
    val words: List<Entry>,
    val index: Int = 0,
    /** The article tapped for the current word; non-null while the feedback is shown. */
    val choice: Gender? = null,
    val correct: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val mistakes: List<Entry> = emptyList(),
) {
    val current: Entry? get() = words.getOrNull(index)
    val finished: Boolean get() = index >= words.size
}

/** A typed answer on a spelling or gap-fill card, once checked. */
data class TypedAnswer(val input: String, val verdict: Verdict, val expected: String)

/** Which numbers the listening drill reads out. */
enum class NumberRange(val label: String, val range: IntRange, val year: Boolean = false) {
    SMALL("0–20", 0..20),
    TENS("21–99", 21..99),
    HUNDREDS("100–999", 100..999),
    THOUSANDS("1000–9999", 1000..9999),
    YEARS("年份", 1900..2030, year = true),
}

/** Listening drill: a number is read out in German and typed as digits. Practice only. */
data class NumberDrillState(
    val range: NumberRange = NumberRange.TENS,
    val target: Int = 0,
    val input: String = "",
    /** Non-null once the answer has been checked. */
    val right: Boolean? = null,
    val correct: Int = 0,
    val total: Int = 0,
    val combo: Int = 0,
) {
    val spoken: String get() = if (range.year) GermanNumbers.year(target) else GermanNumbers.words(target)
}

data class StudyCard(
    val card: Card,
    val entry: Entry,
    val intervals: Map<Rating, String>,
    /** Words in the book with the same Chinese meaning, shown with the answer. */
    val confusables: List<Entry> = emptyList(),
    /** How a meaning card is asked this time; see [Quiz.modeFor]. */
    val mode: QuizMode = QuizMode.FLIP,
    /** The four options of a multiple-choice question, the answer among them. */
    val options: List<Entry> = emptyList(),
) {
    /** Graded by the app (article, typed or multiple choice) rather than self-rated. */
    val objective: Boolean get() = card.type.isObjective || mode.isChoice
}

/** Filters of the word list. */
enum class WordFilter(val label: String) {
    ALL("全部"), LEECH("难词"), LEARNING("学习中"), MASTERED("已掌握"), NEW("未学"), KNOWN("已斩");

    fun accepts(w: WordProgress) = when (this) {
        ALL -> true
        LEECH -> w.isLeech && w.status != WordStatus.KNOWN
        LEARNING -> w.status == WordStatus.LEARNING
        MASTERED -> w.status == WordStatus.MASTERED
        NEW -> w.status == WordStatus.NEW
        KNOWN -> w.status == WordStatus.KNOWN
    }
}

data class WordsState(
    val filter: WordFilter = WordFilter.ALL,
    val query: String = "",
    val rows: List<WordProgress> = emptyList(),
    val counts: Map<WordFilter, Int> = emptyMap(),
)

data class StatsState(val stats: Stats, val milestones: List<Milestone>, val totalWords: Int)

data class UiState(
    val screen: Screen = Screen.HOME,
    val home: HomeState? = null,
    val current: StudyCard? = null,
    val revealed: Boolean = false,
    /** Article the user tapped on an article card; non-null while feedback is shown. */
    val articleChoice: Gender? = null,
    val counts: Counts = Counts(0, 0, 0),
    val canUndo: Boolean = false,
    val scheme: GestureScheme = GestureSchemes.DEFAULT,
    val swipeSensitivity: SwipeSensitivity = SwipeSensitivity.MEDIUM,
    val books: BooksState = BooksState(),
    val import: ImportState? = null,
    val drill: DrillState? = null,
    val typed: TypedAnswer? = null,
    /** A word tapped in an example sentence, while its lookup is shown. */
    val lookup: LookupResult? = null,
    val numbers: NumberDrillState? = null,
    val words: WordsState? = null,
    val stats: StatsState? = null,
    /** Recording for shadowing is running. */
    val recording: Boolean = false,
    /** Index of the option tapped on a multiple-choice question; non-null while feedback is shown. */
    val picked: Int? = null,
)

data class BooksState(
    val books: List<BookInfo> = emptyList(),
    val activeId: String = "",
    /** Whether the phone has a German voice for books without bundled audio; null = unknown yet. */
    val germanVoice: Boolean? = null,
    /** Learned / due counts per book id. */
    val progress: Map<String, BookProgress> = emptyMap(),
    /** Books offered for download; null until the list has been fetched. */
    val catalog: List<CatalogBook>? = null,
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    /** Running downloads: book id to fraction done (0..1). */
    val downloads: Map<String, Float> = emptyMap(),
) {
    /** Catalog books not on the phone yet. */
    val available: List<CatalogBook> get() = catalog.orEmpty().filter { c -> books.none { it.id == c.id } }

    /** A newer version in the catalog for a downloaded book, if any. */
    fun update(book: BookInfo): CatalogBook? =
        catalog?.firstOrNull { it.id == book.id && book.kind == BookKind.DOWNLOADED && it.version > book.version }
}

/** The import screen: how the picked file was read and what importing it would produce. */
data class ImportState(
    val fileName: String,
    val kindLabel: String,
    val title: String,
    /** Our own format needs no column mapping. */
    val native: Boolean = false,
    val headers: List<String> = emptyList(),
    val hasHeader: Boolean = true,
    val canToggleHeader: Boolean = false,
    val mapping: Map<ColumnRole, Int> = emptyMap(),
    val autoComplete: Boolean = true,
    val lexiconAvailable: Boolean = false,
    val result: ImportResult? = null,
    val busy: Boolean = false,
)

class StudyViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WortschatzApp
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val speaker = Speaker(application)
    private val shadowing = Shadowing(application)

    // StudySession is not thread-safe: every call goes through this one-at-a-time dispatcher.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sessionThread = Dispatchers.IO.limitedParallelism(1)
    private var session: StudySession? = null
    private var shownAt = 0L

    // The picked file while the import screen is open.
    private var importText: String? = null
    private var importTable: Table? = null
    private var importNative: List<app.wortschatz.core.Entry>? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(scheme = loadScheme(), swipeSensitivity = loadSensitivity()) }
        speaker.rate = if (prefs.getBoolean(KEY_SLOW, false)) SLOW_RATE else 1f
        onSession { s -> _state.update { it.copy(home = homeState(s)) } }
        autoBackup()
    }

    fun startStudy() = onSession { s -> showNext(s) }

    fun goHome() = onSession { s ->
        stopShadowing()
        _state.update { it.copy(screen = Screen.HOME, current = null, import = null, drill = null, numbers = null, lookup = null, words = null, stats = null, home = homeState(s)) }
    }

    // ---- Article speed drill ----

    fun startDrill() = onSession { s ->
        val words = s.drillNouns(DRILL_SIZE)
        if (words.isEmpty()) {
            viewModelScope.launch { toast("这本词书里没有名词") }
            return@onSession
        }
        _state.update { it.copy(screen = Screen.DRILL, drill = DrillState(words)) }
    }

    fun drillAnswer(gender: Gender) {
        val drill = _state.value.drill ?: return
        val word = drill.current ?: return
        if (drill.choice != null) return
        val right = gender == word.gender
        val combo = if (right) drill.combo + 1 else 0
        _state.update {
            it.copy(
                drill = drill.copy(
                    choice = gender,
                    correct = drill.correct + if (right) 1 else 0,
                    combo = combo,
                    bestCombo = maxOf(drill.bestCombo, combo),
                    mistakes = if (right) drill.mistakes else drill.mistakes + word,
                )
            )
        }
        speaker.speak(word.spoken)
        viewModelScope.launch {
            delay(if (right) 550 else 1400)
            _state.update { s ->
                val d = s.drill ?: return@update s
                if (d.current?.id != word.id) s else s.copy(drill = d.copy(index = d.index + 1, choice = null))
            }
        }
    }

    // ---- Daily reminder ----

    /** Call after the notification permission was granted (or is not needed). */
    fun setReminderEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Reminder.KEY_ENABLED, enabled).apply()
        Reminder.schedule(app)
        refreshHome()
    }

    fun changeReminderHour(delta: Int) {
        val hour = (prefs.getInt(Reminder.KEY_HOUR, Reminder.DEFAULT_HOUR) + delta + 24) % 24
        prefs.edit().putInt(Reminder.KEY_HOUR, hour).apply()
        Reminder.schedule(app)
        refreshHome()
    }

    private fun refreshHome() = onSession { s -> _state.update { it.copy(home = homeState(s)) } }

    // ---- Word books and import ----

    fun openBooks() {
        _state.update { it.copy(screen = Screen.BOOKS, import = null) }
        refreshBooks()
        if (_state.value.books.catalog == null) loadCatalog()
    }

    fun selectBook(id: String) {
        app.activeBookId = id
        refreshBooks()
        onSession(rebuild = true) { s -> _state.update { it.copy(home = homeState(s)) } }
        AudioPrepJob.runSoon(app)
    }

    /** Removes the book's files; its progress stays, so downloading it again carries on where it was. */
    fun deleteBook(id: String) = viewModelScope.launch {
        val wasActive = app.activeBookId == id
        withContext(Dispatchers.IO) { app.books.delete(id) }
        // The active book falls back to the bundled one once its file is gone.
        if (wasActive) selectBook(app.activeBookId) else refreshBooks()
    }

    fun loadCatalog() = viewModelScope.launch {
        updateBooks { it.copy(catalogLoading = true, catalogError = null) }
        try {
            val catalog = withContext(Dispatchers.IO) { app.downloader.catalog() }
            updateBooks { it.copy(catalog = catalog, catalogLoading = false) }
        } catch (e: Exception) {
            updateBooks { it.copy(catalogLoading = false, catalogError = "暂时连不上词书库，请检查网络后重试。") }
        }
    }

    private val downloadJobs = HashMap<String, Job>()

    /** Downloads a book, or updates one already on the phone; progress is kept either way. */
    fun downloadBook(book: CatalogBook) {
        if (downloadJobs[book.id]?.isActive == true) return
        val updating = app.books.exists(book.id)
        updateBooks { it.copy(downloads = it.downloads + (book.id to 0f)) }
        downloadJobs[book.id] = viewModelScope.launch {
            try {
                var shown = 0f
                withContext(Dispatchers.IO) {
                    app.downloader.download(book) { done, total ->
                        val fraction = if (total > 0) done.toFloat() / total else 0f
                        // Only redraw once per percent.
                        if (fraction - shown >= 0.01f || fraction >= 1f) {
                            shown = fraction
                            updateBooks { it.copy(downloads = it.downloads + (book.id to fraction)) }
                        }
                    }
                }
                if (updating) {
                    toast("“${book.title}”已更新，学习进度都在")
                    if (app.activeBookId == book.id) selectBook(book.id) else refreshBooks()
                } else {
                    toast("“${book.title}”已下载并设为当前词书。之前词书的进度都保留，随时可以切回去。")
                    selectBook(book.id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("下载没有完成：${e.message ?: e.javaClass.simpleName}。请检查网络后重试。")
            } finally {
                downloadJobs.remove(book.id)
                updateBooks { it.copy(downloads = it.downloads - book.id) }
            }
        }
    }

    fun cancelDownload(id: String) {
        downloadJobs.remove(id)?.cancel()
    }

    private fun updateBooks(change: (BooksState) -> BooksState) = _state.update { it.copy(books = change(it.books)) }

    private fun refreshBooks() = viewModelScope.launch {
        val (books, progress) = withContext(Dispatchers.IO) {
            val books = app.books.list()
            val cards = app.store.loadCards()
            val known = app.store.knownEntries()
            val now = System.currentTimeMillis()
            books to books.associate { b -> b.id to bookProgress(app.books.entries(b.id).map { it.id }, cards, known, now) }
        }
        updateBooks { it.copy(books = books, activeId = app.activeBookId, germanVoice = speaker.hasGermanVoice, progress = progress) }
    }

    /** Opens the import screen for a file picked in the app or shared / opened from another app. */
    fun beginImport(uri: Uri) = viewModelScope.launch {
        try {
            val resolver = app.contentResolver
            val name = withContext(Dispatchers.IO) {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: uri.lastPathSegment ?: "导入的文件"
            }
            val bytes = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到这个文件")
            }
            beginImport(name, bytes)
        } catch (e: Exception) {
            toast("无法打开这个文件：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Text shared from another app (a pasted list) is imported like a table file. */
    fun beginImportText(text: String) = viewModelScope.launch { beginImport("分享的文本", text.toByteArray()) }

    private suspend fun beginImport(name: String, bytes: ByteArray) {
        _state.update { it.copy(screen = Screen.IMPORT, import = ImportState(name, "正在读取…", "", busy = true)) }
        try {
            val (source, lexiconAvailable) = withContext(Dispatchers.IO) {
                ImportSource.detect(name, bytes, ::AndroidSqlDatabase, ::ZstdInputStream, app.cacheDir) to (app.lexicon.size > 0)
            }
            val title = name.substringBeforeLast('.').ifBlank { "导入的词书" }
            when (source) {
                is ImportSource.Native -> {
                    importNative = source.entries
                    importTable = null
                    importText = null
                    val result = ImportResult(source.entries, duplicates = 0, skipped = 0, completed = 0)
                    _state.update {
                        it.copy(import = ImportState(name, "本 App 的词书格式", title, native = true, result = result))
                    }
                }
                is ImportSource.Tabular -> {
                    importNative = null
                    importTable = source.table
                    importText = if (source.kind == ImportSource.Kind.TEXT) ImportSource.decode(bytes) else null
                    val hasHeader = source.kind == ImportSource.Kind.ANKI ||
                        DelimitedText.looksLikeHeader(source.table.headers)
                    _state.update {
                        it.copy(
                            import = ImportState(
                                fileName = name,
                                kindLabel = source.kind.label,
                                title = title,
                                headers = source.table.headers,
                                hasHeader = hasHeader,
                                canToggleHeader = source.kind == ImportSource.Kind.TEXT,
                                mapping = Importer.guessMapping(source.table),
                                lexiconAvailable = lexiconAvailable,
                                busy = true,
                            )
                        )
                    }
                    rebuildPreview()
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(screen = Screen.BOOKS, import = null) }
            refreshBooks()
            toast("没能识别这个文件：${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun setImportHeader(hasHeader: Boolean) {
        val text = importText ?: return
        val table = DelimitedText.parse(text, hasHeader = hasHeader)
        importTable = table
        updateImport { it.copy(hasHeader = hasHeader, headers = table.headers, mapping = Importer.guessMapping(table)) }
    }

    fun setImportColumn(role: ColumnRole, column: Int?) = updateImport { s ->
        val mapping = s.mapping.filterValues { it != column }.toMutableMap()
        if (column == null) mapping.remove(role) else mapping[role] = column
        s.copy(mapping = mapping)
    }

    fun setImportAutoComplete(value: Boolean) = updateImport { it.copy(autoComplete = value) }

    fun setImportTitle(value: String) = _state.update { s -> s.copy(import = s.import?.copy(title = value)) }

    private fun updateImport(change: (ImportState) -> ImportState) {
        _state.update { s -> s.copy(import = s.import?.let(change)?.copy(busy = true)) }
        rebuildPreview()
    }

    private fun rebuildPreview() = viewModelScope.launch {
        val s = _state.value.import ?: return@launch
        val table = importTable ?: return@launch
        val result = withContext(Dispatchers.Default) {
            Importer.build(table, ImportOptions(s.mapping, s.autoComplete), "preview", app.lexicon)
        }
        // Ignore a result for settings that have changed since.
        if (_state.value.import?.mapping == s.mapping && _state.value.import?.autoComplete == s.autoComplete) {
            _state.update { it.copy(import = it.import?.copy(result = result, busy = false)) }
        }
    }

    fun confirmImport() = viewModelScope.launch {
        val s = _state.value.import ?: return@launch
        val entries = importNative ?: s.result?.entries ?: return@launch
        if (entries.isEmpty() || s.busy) return@launch
        val book = withContext(Dispatchers.IO) { app.books.add(s.title.trim().ifEmpty { "导入的词书" }, entries) }
        importNative = null
        importTable = null
        importText = null
        toast("已导入 ${book.wordCount} 个词，并设为当前词书")
        _state.update { it.copy(screen = Screen.BOOKS, import = null) }
        selectBook(book.id)
    }

    private fun toast(message: String) = Toast.makeText(app, message, Toast.LENGTH_LONG).show()

    fun reveal() {
        val current = _state.value.current ?: return
        if (current.objective || _state.value.revealed) return
        _state.update { it.copy(revealed = true) }
        val answer = when (current.card.type) {
            CardType.PLURAL -> Audio.plural(current.entry)
            CardType.VERB_FORMS -> Audio.verbForms(current.entry)
            else -> null
        }
        answer?.let(speaker::speak)
    }

    fun rate(rating: Rating) = onSession { s ->
        val current = _state.value.current ?: return@onSession
        s.answer(current.card, rating, System.currentTimeMillis() - shownAt)
        showNext(s)
    }

    /** Article cards are graded by the app: correct counts as Good, wrong as Again. */
    fun chooseArticle(gender: Gender) {
        val current = _state.value.current ?: return
        if (current.card.type != CardType.ARTICLE || _state.value.articleChoice != null) return
        _state.update { it.copy(articleChoice = gender) }
        speaker.speak(current.entry.spoken)
        val correct = gender == current.entry.gender
        viewModelScope.launch {
            delay(if (correct) 700 else 1600)
            if (_state.value.current?.card?.id == current.card.id) rate(if (correct) Rating.GOOD else Rating.AGAIN)
        }
    }

    // ---- Spelling and gap-fill cards ----

    /** Checks a typed answer. Right moves on by itself; anything else waits for [continueTyped]. */
    fun submitTyped(input: String) {
        val current = _state.value.current ?: return
        if (!current.card.type.isTyped || _state.value.typed != null) return
        val entry = current.entry
        val typed = if (current.card.type == CardType.SPELLING) {
            TypedAnswer(input, Typing.gradeSpelling(input, entry), entry.lemma)
        } else {
            val cloze = Cloze.of(entry) ?: return
            TypedAnswer(input, cloze.grade(input), cloze.answer)
        }
        _state.update { it.copy(typed = typed) }
        speaker.speak(if (current.card.type == CardType.SPELLING) entry.spoken else entry.example)
        if (typed.verdict == Verdict.CORRECT) {
            viewModelScope.launch {
                delay(900)
                if (_state.value.current?.card?.id == current.card.id) rate(Rating.GOOD)
            }
        }
    }

    /** "不会": show the answer; the card counts as forgotten. */
    fun giveUpTyped() {
        val current = _state.value.current ?: return
        if (!current.card.type.isTyped || _state.value.typed != null) return
        val expected = if (current.card.type == CardType.SPELLING) current.entry.lemma else Cloze.of(current.entry)?.answer.orEmpty()
        _state.update { it.copy(typed = TypedAnswer("", Verdict.WRONG, expected)) }
        speaker.speak(if (current.card.type == CardType.SPELLING) current.entry.spoken else current.entry.example)
    }

    /** Close (case, umlaut spelling, one letter off) counts as Hard; wrong as Again. */
    fun continueTyped() {
        val typed = _state.value.typed ?: return
        rate(if (typed.verdict == Verdict.CLOSE) Rating.HARD else Rating.AGAIN)
    }

    // ---- Looking up words in example sentences ----

    private var lookupIndex: Pair<String, Lookup>? = null

    fun lookupWord(word: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val bookId = app.activeBookId
                val index = lookupIndex?.takeIf { it.first == bookId }?.second
                    ?: Lookup(app.entries, app.lexicon).also { lookupIndex = bookId to it }
                index.find(word)
            }
            _state.update { it.copy(lookup = result) }
        }
    }

    fun closeLookup() = _state.update { it.copy(lookup = null) }

    // ---- Number listening drill ----

    fun openNumbers() {
        val range = NumberRange.entries.firstOrNull { it.name == prefs.getString(KEY_NUMBER_RANGE, null) } ?: NumberRange.TENS
        _state.update { it.copy(screen = Screen.NUMBERS, numbers = nextNumber(NumberDrillState(range = range))) }
        replayNumber()
    }

    fun setNumberRange(range: NumberRange) {
        prefs.edit().putString(KEY_NUMBER_RANGE, range.name).apply()
        _state.update { it.copy(numbers = nextNumber(NumberDrillState(range = range))) }
        replayNumber()
    }

    fun replayNumber() {
        _state.value.numbers?.let { speaker.speak(it.spoken) }
    }

    fun numberKey(key: Char) {
        val n = _state.value.numbers ?: return
        if (n.right != null) return
        val input = when (key) {
            '⌫' -> n.input.dropLast(1)
            else -> (n.input + key).take(4)
        }
        _state.update { it.copy(numbers = n.copy(input = input)) }
    }

    fun submitNumber() {
        val n = _state.value.numbers ?: return
        if (n.right != null || n.input.isEmpty()) return
        val right = n.input.toIntOrNull() == n.target
        _state.update {
            it.copy(
                numbers = n.copy(
                    right = right,
                    correct = n.correct + if (right) 1 else 0,
                    total = n.total + 1,
                    combo = if (right) n.combo + 1 else 0,
                )
            )
        }
        if (right) {
            viewModelScope.launch {
                delay(900)
                if (_state.value.numbers?.let { it.target == n.target && it.right != null } == true) nextNumberAndSpeak()
            }
        } else {
            replayNumber()
        }
    }

    fun nextNumberAndSpeak() {
        _state.update { s -> s.copy(numbers = s.numbers?.let(::nextNumber)) }
        replayNumber()
    }

    private fun nextNumber(n: NumberDrillState): NumberDrillState {
        var target = n.range.range.random()
        if (n.range.range.count() > 1) while (target == n.target) target = n.range.range.random()
        return n.copy(target = target, input = "", right = null)
    }

    // ---- Backup ----

    // Nothing to keep yet on a fresh install, and an empty copy would only confuse a later restore.
    private fun autoBackup() = onSession { s ->
        if (s.learnedWords() > 0 && app.backup.autoBackup()) _state.update { it.copy(home = homeState(s)) }
    }

    fun backupNow() {
        viewModelScope.launch {
            val ok = withContext(sessionThread) { app.backup.autoBackup(maxAgeMs = 0) }
            toast(if (ok) "已备份到 下载/Wortschatz" else "备份失败")
            refreshHome()
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(sessionThread) {
                runCatching { app.contentResolver.openOutputStream(uri, "wt")!!.use(app.backup::export) }.isSuccess
            }
            toast(if (ok) "备份已导出" else "导出失败")
        }
    }

    /** Restores a backup, then restarts the app so everything is read again from the restored data. */
    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            val error = withContext(sessionThread) {
                runCatching { app.contentResolver.openInputStream(uri)!!.use(app.backup::restore) }.exceptionOrNull()
            }
            if (error != null) {
                toast(error.message?.takeIf { it.any { c -> c.code > 0x2e80 } } ?: "恢复失败：不是有效的备份文件")
                return@launch
            }
            toast("已恢复，正在重新打开")
            delay(600)
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                app.startActivity(it)
            }
            Runtime.getRuntime().exit(0)
        }
    }

    fun dismissRestore() {
        prefs.edit().putBoolean(KEY_RESTORE_DISMISSED, true).apply()
        refreshHome()
    }

    // ---- Word list and statistics ----

    fun openWords() = onSession { s -> _state.update { it.copy(screen = Screen.WORDS, words = wordsState(s, it.words ?: WordsState())) } }

    fun setWordFilter(filter: WordFilter) = onSession { s ->
        _state.update { it.copy(words = wordsState(s, (it.words ?: WordsState()).copy(filter = filter))) }
    }

    fun setWordQuery(query: String) {
        _state.update { it.copy(words = it.words?.copy(query = query)) }
        onSession { s -> _state.update { it.copy(words = wordsState(s, it.words ?: WordsState(query = query))) } }
    }

    /** Puts a word marked as known back into the schedule. */
    fun restoreWord(entryId: String) = onSession { s ->
        s.unmarkKnown(entryId)
        _state.update { it.copy(words = wordsState(s, it.words ?: WordsState()), home = homeState(s)) }
    }

    private fun wordsState(s: StudySession, base: WordsState): WordsState {
        val all = s.wordProgress()
        val q = base.query.trim().lowercase()
        val rows = all.filter { base.filter.accepts(it) }.filter {
            q.isEmpty() || it.entry.lemma.lowercase().contains(q) || it.entry.meaning.contains(q)
        }.let { list -> if (base.filter == WordFilter.LEECH) list.sortedByDescending { it.lapses } else list }
        return base.copy(rows = rows, counts = WordFilter.entries.associateWith { f -> all.count(f::accepts) })
    }

    fun openStats() = onSession { s ->
        val stats = s.stats()
        _state.update { it.copy(screen = Screen.STATS, stats = StatsState(stats, Milestones.of(stats, s.totalWords()), s.totalWords())) }
    }

    // ---- Pronunciation: slow speech and shadowing ----

    fun setSlowSpeech(slow: Boolean) {
        prefs.edit().putBoolean(KEY_SLOW, slow).apply()
        speaker.rate = if (slow) SLOW_RATE else 1f
        refreshHome()
    }

    /** First tap starts recording; the second stops it and plays the learner's take, then the reference. */
    fun toggleShadow(text: String) {
        if (shadowing.isRecording) {
            val ok = shadowing.stop()
            _state.update { it.copy(recording = false) }
            if (ok) speaker.playFile(shadowing.file.path) { speaker.speak(text) }
        } else if (shadowing.start()) {
            _state.update { it.copy(recording = true) }
        } else {
            viewModelScope.launch { toast("无法录音") }
        }
    }

    private fun stopShadowing() {
        if (shadowing.isRecording) shadowing.stop()
        _state.update { it.copy(recording = false) }
    }

    // ---- Card types ----

    fun setCardTypeEnabled(type: CardType, enabled: Boolean) {
        prefs.edit().putBoolean(WortschatzApp.KEY_CARD_OFF_PREFIX + type.name, !enabled).apply()
        onSession(rebuild = true) { s -> _state.update { it.copy(home = homeState(s)) } }
    }

    /** Multiple choice: right counts as Good, wrong as Again, after a moment to see the answer. */
    fun pick(index: Int) {
        val current = _state.value.current ?: return
        if (!current.mode.isChoice || _state.value.picked != null) return
        _state.update { it.copy(picked = index) }
        speaker.speak(current.entry.spoken)
        val correct = current.options.getOrNull(index)?.id == current.entry.id
        viewModelScope.launch {
            delay(if (correct) 800 else 2000)
            if (_state.value.current?.card?.id == current.card.id && _state.value.picked != null) rate(if (correct) Rating.GOOD else Rating.AGAIN)
        }
    }

    /** "斩": the current word is already known and never comes up again. */
    fun markKnown() = onSession { s ->
        val current = _state.value.current ?: return@onSession
        s.markKnown(current.card)
        showNext(s)
    }

    fun undo() = onSession { s ->
        val card = s.undo() ?: return@onSession
        show(s, card)
    }

    fun speakText(text: String) = speaker.speak(text)

    /** Runs whatever the active gesture scheme assigns to [gesture] on the current card. */
    fun onGesture(gesture: Gesture) {
        val s = _state.value
        val current = s.current ?: return
        val answered = when {
            current.card.type == CardType.ARTICLE -> s.articleChoice != null
            current.card.type.isTyped -> s.typed != null
            current.mode.isChoice -> s.picked != null
            else -> s.revealed
        }
        when (val command = resolve(s.scheme.actionFor(gesture), current.card.type, answered, current.objective)) {
            Command.Nothing -> Unit
            Command.Reveal -> reveal()
            is Command.Rate -> rate(command.rating)
            Command.Undo -> undo()
            Command.Speak -> speakCurrent()
            Command.Master -> markKnown()
        }
    }

    /** Volume keys are only taken over while studying, and only if the scheme uses them. */
    fun handlesKey(gesture: Gesture): Boolean =
        _state.value.screen == Screen.STUDY && _state.value.scheme.actionFor(gesture) != GestureAction.NONE

    fun openGestureSettings() = _state.update { it.copy(screen = Screen.GESTURES) }

    fun openSettings() = onSession { s -> _state.update { it.copy(screen = Screen.SETTINGS, home = homeState(s)) } }

    fun selectGestureScheme(id: String) {
        prefs.edit().putString(KEY_SCHEME, id).apply()
        _state.update { it.copy(scheme = loadScheme()) }
    }

    /** Editing any gesture switches to the custom scheme, starting from whatever was active. */
    fun setGestureAction(gesture: Gesture, action: GestureAction) {
        val custom = _state.value.scheme.with(gesture, action)
        prefs.edit().putString(KEY_SCHEME, GestureSchemes.CUSTOM_ID).putString(KEY_CUSTOM, custom.encode()).apply()
        _state.update { it.copy(scheme = loadScheme()) }
    }

    fun setSwipeSensitivity(value: SwipeSensitivity) {
        prefs.edit().putString(KEY_SENSITIVITY, value.name).apply()
        _state.update { it.copy(swipeSensitivity = value) }
    }

    private fun loadSensitivity(): SwipeSensitivity =
        SwipeSensitivity.entries.firstOrNull { it.name == prefs.getString(KEY_SENSITIVITY, null) } ?: SwipeSensitivity.MEDIUM

    private fun loadScheme(): GestureScheme {
        val id = prefs.getString(KEY_SCHEME, null)
        return if (id == GestureSchemes.CUSTOM_ID) GestureSchemes.custom(prefs.getString(KEY_CUSTOM, null))
        else GestureSchemes.preset(id.orEmpty()) ?: GestureSchemes.DEFAULT
    }

    fun speakCurrent() {
        val s = _state.value
        val current = s.current ?: return
        // Hearing "der Tisch" would give away an article card that has not been answered yet.
        if (current.card.type == CardType.ARTICLE && s.articleChoice == null) return
        if (current.card.type.isTyped && s.typed == null) return
        if (current.mode == QuizMode.PICK_WORD && s.picked == null) return
        speaker.speak(current.entry.spoken)
    }

    fun setNewWordsPerDay(value: Int) {
        prefs.edit().putInt(KEY_NEW_PER_DAY, value.coerceIn(5, 100)).apply()
        onSession(rebuild = true) { s -> _state.update { it.copy(home = homeState(s)) } }
    }

    fun setNewWordOrder(order: NewWordOrder) {
        prefs.edit().apply {
            putString(WortschatzApp.KEY_NEW_ORDER, order.name)
            // Each switch to random deals a fresh shuffle, which then stays put between sessions.
            if (order == NewWordOrder.RANDOM) putLong(WortschatzApp.KEY_SHUFFLE_SEED, System.nanoTime())
        }.apply()
        onSession(rebuild = true) { s -> _state.update { it.copy(home = homeState(s)) } }
    }

    private fun showNext(s: StudySession) {
        val card = s.nextCard()
        if (card == null) {
            _state.update {
                it.copy(screen = Screen.DONE, current = null, canUndo = s.canUndo, counts = s.counts(), home = homeState(s))
            }
            autoBackup()
        } else {
            show(s, card)
        }
    }

    private fun show(s: StudySession, card: Card) {
        stopShadowing()
        val entry = s.entry(card)
        val intervals = s.preview(card).mapValues { formatInterval(it.value) }
        val mode = Quiz.modeFor(card)
        shownAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                screen = Screen.STUDY,
                current = StudyCard(
                    card, entry, intervals, confusables(entry, app.entries),
                    mode = mode,
                    options = if (mode.isChoice) Quiz.options(entry, app.entries, seed = card.id.hashCode() * 31L + card.memory.reps) else emptyList(),
                ),
                revealed = false,
                picked = null,
                articleChoice = null,
                typed = null,
                counts = s.counts(),
                canUndo = s.canUndo,
            )
        }
        // Never read the answer aloud before it is shown: article, typed and "pick the German"
        // questions stay silent until answered.
        if (!card.type.isObjective && mode != QuizMode.PICK_WORD) speaker.speak(entry.spoken)
    }

    private fun onSession(rebuild: Boolean = false, block: (StudySession) -> Unit) {
        viewModelScope.launch {
            withContext(sessionThread) {
                if (session == null || rebuild) session = createSession()
                block(session!!)
            }
        }
    }

    private fun createSession() = StudySession(entries = app.entries, store = app.store, settings = app.studySettings)

    private fun homeState(s: StudySession) = HomeState(
        wordBookTitle = app.books.info(app.activeBookId).title,
        counts = s.counts(),
        learnedWords = s.learnedWords(),
        totalWords = s.totalWords(),
        reviewsToday = s.reviewsToday(),
        forecast = s.forecast(7).toList(),
        newWordsPerDay = prefs.getInt(KEY_NEW_PER_DAY, WortschatzApp.DEFAULT_NEW_PER_DAY),
        newWordOrder = app.studySettings.newWordOrder,
        disabledCardTypes = app.studySettings.disabledCardTypes,
        lastBackup = app.backup.lastAutoBackup,
        slowSpeech = prefs.getBoolean(KEY_SLOW, false),
        offerRestore = s.learnedWords() == 0 && s.reviewsToday() == 0 && !prefs.getBoolean(KEY_RESTORE_DISMISSED, false),
        streak = s.streak(),
        knownWords = s.knownWords(),
        backlog = s.backlog(),
        reminderEnabled = prefs.getBoolean(Reminder.KEY_ENABLED, false),
        reminderHour = prefs.getInt(Reminder.KEY_HOUR, Reminder.DEFAULT_HOUR),
    )

    override fun onCleared() = speaker.shutdown()

    private companion object {
        const val KEY_NEW_PER_DAY = WortschatzApp.KEY_NEW_PER_DAY
        const val KEY_SCHEME = "gesture_scheme"
        const val KEY_CUSTOM = "gesture_custom"
        const val KEY_SENSITIVITY = "swipe_sensitivity"
        const val DRILL_SIZE = 20
        const val KEY_NUMBER_RANGE = "number_range"
        const val KEY_RESTORE_DISMISSED = "restore_dismissed"
        const val KEY_SLOW = "slow_speech"
        const val SLOW_RATE = 0.75f
    }
}
