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

enum class Screen { HOME, STUDY, DONE, GESTURES, BOOKS, IMPORT }

data class HomeState(
    val wordBookTitle: String,
    val counts: Counts,
    val learnedWords: Int,
    val totalWords: Int,
    val reviewsToday: Int,
    val forecast: List<Int>,
    val newWordsPerDay: Int,
)

data class StudyCard(val card: Card, val entry: Entry, val intervals: Map<Rating, String>)

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
)

data class BooksState(
    val books: List<BookInfo> = emptyList(),
    val activeId: String = "",
    /** Whether the phone has a German voice for books without bundled audio; null = unknown yet. */
    val germanVoice: Boolean? = null,
)

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
        onSession { s -> _state.update { it.copy(home = homeState(s)) } }
    }

    fun startStudy() = onSession { s -> showNext(s) }

    fun goHome() = onSession { s ->
        _state.update { it.copy(screen = Screen.HOME, current = null, import = null, home = homeState(s)) }
    }

    // ---- Word books and import ----

    fun openBooks() {
        _state.update { it.copy(screen = Screen.BOOKS, import = null) }
        refreshBooks()
    }

    fun selectBook(id: String) {
        app.activeBookId = id
        refreshBooks()
        onSession(rebuild = true) { s -> _state.update { it.copy(home = homeState(s)) } }
        AudioPrepJob.runSoon(app)
    }

    fun deleteBook(id: String) = viewModelScope.launch {
        val wasActive = app.activeBookId == id
        withContext(Dispatchers.IO) { app.books.delete(id) }
        // The active book falls back to the bundled one once its file is gone.
        if (wasActive) selectBook(app.activeBookId) else refreshBooks()
    }

    private fun refreshBooks() = viewModelScope.launch {
        val books = withContext(Dispatchers.IO) { app.books.list() }
        _state.update { it.copy(books = BooksState(books, app.activeBookId, speaker.hasGermanVoice)) }
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
        if (current.card.type == CardType.ARTICLE || _state.value.revealed) return
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

    fun undo() = onSession { s ->
        val card = s.undo() ?: return@onSession
        show(s, card)
    }

    fun speakText(text: String) = speaker.speak(text)

    /** Runs whatever the active gesture scheme assigns to [gesture] on the current card. */
    fun onGesture(gesture: Gesture) {
        val s = _state.value
        val current = s.current ?: return
        val answered = if (current.card.type == CardType.ARTICLE) s.articleChoice != null else s.revealed
        when (val command = resolve(s.scheme.actionFor(gesture), current.card.type, answered)) {
            Command.Nothing -> Unit
            Command.Reveal -> reveal()
            is Command.Rate -> rate(command.rating)
            Command.Undo -> undo()
            Command.Speak -> speakCurrent()
        }
    }

    /** Volume keys are only taken over while studying, and only if the scheme uses them. */
    fun handlesKey(gesture: Gesture): Boolean =
        _state.value.screen == Screen.STUDY && _state.value.scheme.actionFor(gesture) != GestureAction.NONE

    fun openGestureSettings() = _state.update { it.copy(screen = Screen.GESTURES) }

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
        speaker.speak(current.entry.spoken)
    }

    fun setNewWordsPerDay(value: Int) {
        prefs.edit().putInt(KEY_NEW_PER_DAY, value.coerceIn(5, 100)).apply()
        onSession(rebuild = true) { s -> _state.update { it.copy(home = homeState(s)) } }
    }

    private fun showNext(s: StudySession) {
        val card = s.nextCard()
        if (card == null) {
            _state.update {
                it.copy(screen = Screen.DONE, current = null, canUndo = s.canUndo, counts = s.counts(), home = homeState(s))
            }
        } else {
            show(s, card)
        }
    }

    private fun show(s: StudySession, card: Card) {
        val entry = s.entry(card)
        val intervals = s.preview(card).mapValues { formatInterval(it.value) }
        shownAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                screen = Screen.STUDY,
                current = StudyCard(card, entry, intervals),
                revealed = false,
                articleChoice = null,
                counts = s.counts(),
                canUndo = s.canUndo,
            )
        }
        // Never read the answer aloud before it is shown: article cards stay silent until answered.
        if (card.type != CardType.ARTICLE) speaker.speak(entry.spoken)
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
    )

    override fun onCleared() = speaker.shutdown()

    private companion object {
        const val KEY_NEW_PER_DAY = WortschatzApp.KEY_NEW_PER_DAY
        const val KEY_SCHEME = "gesture_scheme"
        const val KEY_CUSTOM = "gesture_custom"
        const val KEY_SENSITIVITY = "swipe_sensitivity"
    }
}
