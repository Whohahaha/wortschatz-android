package app.wortschatz.core

import app.wortschatz.core.fsrs.Fsrs
import app.wortschatz.core.fsrs.Phase
import app.wortschatz.core.fsrs.Rating
import java.time.Instant
import java.time.ZoneId

/** Persistence used by [StudySession]. Implementations must make each method atomic. */
interface StudyStore {
    fun loadCards(): List<Card>
    fun insertCards(cards: List<Card>)
    /** Saves the card's new state and appends the log; returns the log id. */
    fun recordReview(updated: Card, log: ReviewLog): Long
    /** Restores the card to its earlier state and deletes the log. */
    fun undoReview(restored: Card, logId: Long)
    fun logsSince(timestamp: Long): List<ReviewLog>
    /** Times of every answer since [timestamp]; only used for the streak, so it can be cheap. */
    fun reviewTimesSince(timestamp: Long): List<Long> = logsSince(timestamp).map { it.timestamp }
    /** Words the user marked as already known ("斩"); they never come up again. */
    fun knownEntries(): Set<String>
    fun setKnown(entryId: String, known: Boolean)
}

data class StudySettings(
    val newWordsPerDay: Int = 10,
    val maxReviewsPerDay: Int = 200,
    val desiredRetention: Double = 0.9,
    /** The study day rolls over at this local hour, so a late night session counts as "today". */
    val dayStartHour: Int = 4,
    val zone: ZoneId = ZoneId.systemDefault(),
    val newWordOrder: NewWordOrder = NewWordOrder.BOOK,
    /** Seed for [NewWordOrder.RANDOM]; kept fixed so the shuffled order stays the same between sessions. */
    val shuffleSeed: Long = 0,
    /** Card types the learner switched off; their cards are kept but never shown. */
    val disabledCardTypes: Set<CardType> = emptySet(),
)

/** The order new words are introduced in. Reviews are unaffected. */
enum class NewWordOrder(val label: String) {
    RANDOM("随机"),
    ALPHABETICAL("从 A 开始"),
    BOOK("词书顺序"),
}

/** German dictionary order: case and umlauts folded (ä sorts with a), ß as ss, separable marks ignored. */
fun germanSortKey(lemma: String): String = buildString {
    for (c in lemma.lowercase()) when (c) {
        'ä' -> append('a'); 'ö' -> append('o'); 'ü' -> append('u'); 'ß' -> append("ss")
        '|', '·', '-', ' ' -> {}
        else -> append(c)
    }
}

data class Counts(val learning: Int, val review: Int, val new: Int) {
    val total: Int get() = learning + review + new
}

/**
 * Decides which card to show next and records answers.
 *
 * Order: learning cards that are due, then reviews due today (lowest recall probability
 * first), then new words in the chosen [NewWordOrder], then learning cards due soon.
 * Article, plural, verb-form, spelling and gap-fill cards unlock once the word's meaning card has graduated,
 * and at most one card per word is shown per day (apart from learning steps), so sibling
 * cards never give each other away.
 */
class StudySession(
    entries: List<Entry>,
    private val store: StudyStore,
    private val settings: StudySettings = StudySettings(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val fsrs = Fsrs(desiredRetention = settings.desiredRetention)

    private val entryById = entries.associateBy { it.id }
    private val order: Map<String, Int> = when (settings.newWordOrder) {
        NewWordOrder.BOOK -> entries
        NewWordOrder.ALPHABETICAL -> entries.sortedWith(compareBy({ germanSortKey(it.lemma) }, { it.lemma }))
        NewWordOrder.RANDOM -> entries.shuffled(kotlin.random.Random(settings.shuffleSeed))
    }.withIndex().associate { (i, e) -> e.id to i }
    private val cards = LinkedHashMap<String, Card>()
    private var todayStart = 0L
    private val todayLogs = ArrayList<ReviewLog>()
    private val undoStack = ArrayDeque<Undoable>()
    private val known = HashSet<String>()

    /** What [undo] can take back: an answer, or marking a word as known. */
    private sealed interface Undoable {
        data class Answer(val log: ReviewLog) : Undoable
        data class Known(val card: Card) : Undoable
    }

    init {
        store.loadCards().forEach { cards[it.id] = it }
        known += store.knownEntries()
        val missing = entries.flatMap { e ->
            cardTypesFor(e).map { Card(e.id, it) }.filter { it.id !in cards }
        }
        if (missing.isNotEmpty()) {
            store.insertCards(missing)
            missing.forEach { cards[it.id] = it }
        }
        refreshDay(clock(), force = true)
    }

    fun entry(card: Card): Entry = entryById.getValue(card.entryId)

    fun nextCard(): Card? {
        val now = clock()
        refreshDay(now)
        val active = activeCards()
        val siblingsSeen = siblingsSeenToday()
        fun buried(c: Card) = siblingsSeen[c.entryId]?.any { it != c.id } == true
        fun inLearning(c: Card) = c.memory.phase == Phase.LEARNING || c.memory.phase == Phase.RELEARNING

        active.filter { inLearning(it) && it.memory.due <= now }.minByOrNull { it.memory.due }?.let { return it }

        if (reviewsDoneToday() < settings.maxReviewsPerDay) {
            active.filter { it.memory.phase == Phase.REVIEW && it.memory.due < todayEnd() && !buried(it) }
                .minByOrNull { fsrs.retrievability(it.memory, now) }
                ?.let { return it }
        }

        newCandidates(active, ::buried).firstOrNull()?.let { return it }

        return active.filter { inLearning(it) && it.memory.due <= now + LEARN_AHEAD }.minByOrNull { it.memory.due }
    }

    fun answer(card: Card, rating: Rating, durationMs: Long): Card {
        val now = clock()
        val current = cards.getValue(card.id)
        val updated = current.copy(memory = fsrs.review(current.memory, rating, now))
        val log = ReviewLog(cardId = card.id, timestamp = now, rating = rating, durationMs = durationMs, before = current.memory)
        val saved = log.copy(id = store.recordReview(updated, log))
        cards[card.id] = updated
        todayLogs.add(saved)
        undoStack.addLast(Undoable.Answer(saved))
        return updated
    }

    /** Marks the card's word as already known: none of its cards come up again ("斩"). */
    fun markKnown(card: Card) {
        store.setKnown(card.entryId, true)
        known += card.entryId
        undoStack.addLast(Undoable.Known(card))
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** Reverts the last answer (or "known" mark) and returns the card so it can be shown again. */
    fun undo(): Card? = when (val last = undoStack.removeLastOrNull()) {
        null -> null
        is Undoable.Known -> {
            store.setKnown(last.card.entryId, false)
            known -= last.card.entryId
            cards.getValue(last.card.id)
        }
        is Undoable.Answer -> {
            val log = last.log
            val restored = cards.getValue(log.cardId).copy(memory = log.before)
            store.undoReview(restored, log.id)
            cards[log.cardId] = restored
            todayLogs.removeAll { it.id == log.id }
            restored
        }
    }

    /** Time until the card is due again for each rating, in milliseconds. */
    fun preview(card: Card): Map<Rating, Long> {
        val now = clock()
        return fsrs.preview(card.memory, now).mapValues { (_, s) -> s.due - now }
    }

    fun counts(): Counts {
        val now = clock()
        refreshDay(now)
        val active = activeCards()
        val siblingsSeen = siblingsSeenToday()
        fun buried(c: Card) = siblingsSeen[c.entryId]?.any { it != c.id } == true
        val learning = active.count {
            (it.memory.phase == Phase.LEARNING || it.memory.phase == Phase.RELEARNING) && it.memory.due < todayEnd()
        }
        val reviewsDue = active.count { it.memory.phase == Phase.REVIEW && it.memory.due < todayEnd() && !buried(it) }
        val review = minOf(reviewsDue, (settings.maxReviewsPerDay - reviewsDoneToday()).coerceAtLeast(0))
        return Counts(learning, review, newCandidates(active, ::buried).size)
    }

    /**
     * Words likely to come up between now and the end of the study day [days] days ahead:
     * cards falling due by then, plus the new words the daily limit will introduce.
     * Used to prepare audio in the background so studying never waits for it.
     */
    fun upcomingEntries(days: Int = 1): List<Entry> {
        refreshDay(clock())
        val horizon = todayEnd() + days * Fsrs.DAY
        val active = activeCards()
        val due = active.filter { it.memory.phase != Phase.NEW && it.memory.due < horizon }.map { it.entryId }
        val fresh = active.filter { it.type == CardType.MEANING && it.memory.phase == Phase.NEW }
            .sortedBy { order.getValue(it.entryId) }
            .take(settings.newWordsPerDay * (days + 1))
            .map { it.entryId }
        // Grammar cards unlocking soon belong to words already counted above.
        return (due + fresh).distinct().map(entryById::getValue)
    }

    private fun activeCards() = cards.values.filter { it.entryId in entryById && it.entryId !in known && it.type !in settings.disabledCardTypes }

    /** Words whose meaning card has been seen at least once, or that were marked as known. */
    fun learnedWords(): Int = cards.values.count {
        it.type == CardType.MEANING && it.entryId in entryById && (it.memory.phase != Phase.NEW || it.entryId in known)
    }

    /**
     * Nouns for the article speed drill: words already met first (shuffled), topped up with the
     * next new nouns in book order. Practice only; it does not touch the schedule.
     */
    fun drillNouns(count: Int, random: kotlin.random.Random = kotlin.random.Random.Default): List<Entry> {
        val nouns = entryById.values.filter { it.gender != null }
        val meaning = cards.values.filter { it.type == CardType.MEANING }.associateBy { it.entryId }
        val (met, fresh) = nouns.partition { meaning[it.id]?.memory?.phase != Phase.NEW || it.id in known }
        return (met.shuffled(random) + fresh.sortedBy { order.getValue(it.id) }).take(count).shuffled(random)
    }

    fun knownWords(): Int = entryById.keys.count { it in known }

    /** Brings back a word marked as known; its cards continue where they were. */
    fun unmarkKnown(entryId: String) {
        store.setKnown(entryId, false)
        known -= entryId
        undoStack.removeAll { it is Undoable.Known && it.card.entryId == entryId }
    }

    /** Every word of the book with its status, in book order. */
    fun wordProgress(): List<WordProgress> {
        val byEntry = cards.values.groupBy { it.entryId }
        return entryById.values.map { e ->
            val own = byEntry[e.id].orEmpty().filter { it.type !in settings.disabledCardTypes }
            val meaning = own.firstOrNull { it.type == CardType.MEANING }?.memory
            val status = when {
                e.id in known -> WordStatus.KNOWN
                meaning == null || meaning.phase == Phase.NEW -> WordStatus.NEW
                meaning.phase == Phase.REVIEW && meaning.stability >= WordProgress.MASTERED_DAYS -> WordStatus.MASTERED
                else -> WordStatus.LEARNING
            }
            val hardest = own.maxByOrNull { it.memory.lapses }?.takeIf { it.memory.lapses > 0 }
            WordProgress(e, status, hardest?.memory?.lapses ?: 0, hardest?.type)
        }
    }

    /** Figures for the statistics page; [days] study days of history for the calendar. */
    fun stats(days: Int = 119): Stats {
        refreshDay(clock())
        val since = todayStart - (days - 1) * Fsrs.DAY
        val times = store.reviewTimesSince(todayStart - 400 * Fsrs.DAY)
        val daily = IntArray(days)
        val dayIndex = HashSet<Long>()
        for (t in times) {
            val back = (todayStart - dayStart(t)) / Fsrs.DAY
            dayIndex += back
            if (t >= since) daily[(days - 1 - back).toInt()]++
        }
        var longest = 0
        var run = 0
        for (back in 400L downTo 0L) {
            if (back in dayIndex) { run++; longest = maxOf(longest, run) } else run = 0
        }
        val recentReviews = store.logsSince(todayStart - 29 * Fsrs.DAY).filter { it.before.phase == Phase.REVIEW }
        val retention = recentReviews.takeIf { it.isNotEmpty() }?.let { logs -> logs.count { it.rating != Rating.AGAIN }.toDouble() / logs.size }
        return Stats(
            daily = daily,
            retention = retention,
            byStatus = wordProgress().groupingBy { it.status }.eachCount(),
            totalAnswers = times.size,
            longestStreak = longest,
        )
    }

    /** Reviews due today beyond the daily limit; they wait, most-at-risk first, for later days. */
    fun backlog(): Int {
        refreshDay(clock())
        val due = activeCards().count { it.memory.phase == Phase.REVIEW && it.memory.due < todayEnd() }
        return (due - settings.maxReviewsPerDay).coerceAtLeast(0)
    }

    /** Consecutive study days up to today (or up to yesterday, if nothing was studied yet today). */
    fun streak(): Int {
        refreshDay(clock())
        val days = store.reviewTimesSince(todayStart - 400 * Fsrs.DAY).map { (todayStart - dayStart(it)) / Fsrs.DAY }.toSet()
        var day = if (0L in days) 0L else 1L
        var count = 0
        while (day in days) { count++; day++ }
        return count
    }

    fun totalWords(): Int = entryById.size

    fun reviewsToday(): Int = todayLogs.size

    /** Review cards due on each of the next [days] days; overdue cards count toward today. */
    fun forecast(days: Int = 7): IntArray {
        refreshDay(clock())
        val result = IntArray(days)
        activeCards().filter { it.memory.phase == Phase.REVIEW }.forEach {
            val day = ((it.memory.due - todayStart) / Fsrs.DAY).coerceAtLeast(0)
            if (day < days) result[day.toInt()]++
        }
        return result
    }

    private fun newCandidates(active: List<Card>, buried: (Card) -> Boolean): List<Card> {
        val meaningByEntry = active.filter { it.type == CardType.MEANING }.associateBy { it.entryId }
        val introduced = todayLogs.filter { it.before.phase == Phase.NEW }
            .partition { it.cardId.endsWith("/${CardType.MEANING.name}") }
        val newWordsLeft = settings.newWordsPerDay - introduced.first.size
        // Grammar cards for words already known get their own budget of the same size.
        val grammarLeft = settings.newWordsPerDay - introduced.second.size

        val newWords = active.filter { it.type == CardType.MEANING && it.memory.phase == Phase.NEW && !buried(it) }
            .sortedBy { order.getValue(it.entryId) }
            .take(newWordsLeft.coerceAtLeast(0))
        val grammar = active.filter {
            it.type != CardType.MEANING && it.memory.phase == Phase.NEW && !buried(it) &&
                meaningByEntry[it.entryId]?.memory?.phase == Phase.REVIEW
        }
            .sortedWith(compareBy({ order.getValue(it.entryId) }, { UNLOCK_ORDER.indexOf(it.type) }))
            .distinctBy { it.entryId }
            .take(grammarLeft.coerceAtLeast(0))
        return newWords + grammar
    }

    private fun siblingsSeenToday(): Map<String, Set<String>> =
        todayLogs.groupBy({ it.cardId.substringBeforeLast('/') }, { it.cardId }).mapValues { it.value.toSet() }

    private fun reviewsDoneToday() = todayLogs.count { it.before.phase == Phase.REVIEW }

    private fun todayEnd() = todayStart + Fsrs.DAY

    private fun refreshDay(now: Long, force: Boolean = false) {
        if (!force && now in todayStart until todayEnd()) return
        todayStart = dayStart(now)
        todayLogs.clear()
        todayLogs.addAll(store.logsSince(todayStart))
        undoStack.clear()
    }

    private fun dayStart(now: Long): Long {
        val time = Instant.ofEpochMilli(now).atZone(settings.zone)
        var start = time.toLocalDate().atTime(settings.dayStartHour, 0).atZone(settings.zone)
        if (time.isBefore(start)) start = start.minusDays(1)
        return start.toInstant().toEpochMilli()
    }

    companion object {
        private const val LEARN_AHEAD = 20 * Fsrs.MINUTE
        /** After a word graduates its other cards come one a day: article first, then producing the word. */
        private val UNLOCK_ORDER = listOf(CardType.ARTICLE, CardType.SPELLING, CardType.PLURAL, CardType.CLOZE, CardType.VERB_FORMS)
    }
}

/** "1分钟", "10分钟", "3天", "2个月" */
fun formatInterval(ms: Long): String {
    val minutes = ms / Fsrs.MINUTE
    val days = ms.toDouble() / Fsrs.DAY
    return when {
        minutes < 60 -> "${minutes.coerceAtLeast(1)}分钟"
        days < 1 -> "${minutes / 60}小时"
        days < 30 -> "${Math.round(days)}天"
        days < 365 -> "${Math.round(days / 30)}个月"
        else -> "%.1f年".format(days / 365)
    }
}

class InMemoryStudyStore : StudyStore {
    private val cards = LinkedHashMap<String, Card>()
    private val logs = ArrayList<ReviewLog>()
    private var nextId = 1L

    override fun loadCards() = cards.values.toList()
    override fun insertCards(cards: List<Card>) = cards.forEach { this.cards[it.id] = it }
    override fun recordReview(updated: Card, log: ReviewLog): Long {
        cards[updated.id] = updated
        val id = nextId++
        logs.add(log.copy(id = id))
        return id
    }
    override fun undoReview(restored: Card, logId: Long) {
        cards[restored.id] = restored
        logs.removeAll { it.id == logId }
    }
    override fun logsSince(timestamp: Long) = logs.filter { it.timestamp >= timestamp }
    private val known = HashSet<String>()
    override fun knownEntries(): Set<String> = known.toSet()
    override fun setKnown(entryId: String, known: Boolean) {
        if (known) this.known += entryId else this.known -= entryId
    }
}
