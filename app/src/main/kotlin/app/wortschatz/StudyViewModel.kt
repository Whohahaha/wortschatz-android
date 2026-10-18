package app.wortschatz

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, STUDY, DONE, GESTURES }

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

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(scheme = loadScheme()) }
        onSession { s -> _state.update { it.copy(home = homeState(s)) } }
    }

    fun startStudy() = onSession { s -> showNext(s) }

    fun goHome() = onSession { s ->
        _state.update { it.copy(screen = Screen.HOME, current = null, home = homeState(s)) }
    }

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
        prefs.edit().putInt(KEY_NEW_PER_DAY, value.coerceIn(1, 100)).apply()
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

    private fun createSession() = StudySession(
        entries = app.entries,
        store = app.store,
        settings = StudySettings(newWordsPerDay = prefs.getInt(KEY_NEW_PER_DAY, 10)),
    )

    private fun homeState(s: StudySession) = HomeState(
        wordBookTitle = app.wordBookTitle,
        counts = s.counts(),
        learnedWords = s.learnedWords(),
        totalWords = s.totalWords(),
        reviewsToday = s.reviewsToday(),
        forecast = s.forecast(7).toList(),
        newWordsPerDay = prefs.getInt(KEY_NEW_PER_DAY, 10),
    )

    override fun onCleared() = speaker.shutdown()

    private companion object {
        const val KEY_NEW_PER_DAY = "new_words_per_day"
        const val KEY_SCHEME = "gesture_scheme"
        const val KEY_CUSTOM = "gesture_custom"
    }
}
