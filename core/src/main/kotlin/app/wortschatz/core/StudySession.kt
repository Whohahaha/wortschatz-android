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
}

data class StudySettings(
    val newWordsPerDay: Int = 10,
    val maxReviewsPerDay: Int = 200,
    val desiredRetention: Double = 0.9,
    /** The study day rolls over at this local hour, so a late night session counts as "today". */
    val dayStartHour: Int = 4,
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class Counts(val learning: Int, val review: Int, val new: Int) {
    val total: Int get() = learning + review + new
}

/**
 * Decides which card to show next and records answers.
 *
 * Order: learning cards that are due, then reviews due today (lowest recall probability
 * first), then new words in word-book order, then learning cards due soon.
 * Article, plural and verb-form cards unlock once the word's meaning card has graduated,
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
    private val order = entries.withIndex().associate { (i, e) -> e.id to i }
    private val cards = LinkedHashMap<String, Card>()
    private var todayStart = 0L
    private val todayLogs = ArrayList<ReviewLog>()
    private val undoStack = ArrayDeque<ReviewLog>()

    init {
        store.loadCards().forEach { cards[it.id] = it }
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

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    fun nextCard(): Card? {
        val now = clock()
        refreshDay(now)
        val active = cards.values.filter { it.entryId in entryById }
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
        undoStack.addLast(saved)
        return updated
    }

    /** Reverts the last answer and returns the card so it can be shown again. */
    fun undo(): Card? {
        val log = undoStack.removeLastOrNull() ?: return null
        val restored = cards.getValue(log.cardId).copy(memory = log.before)
        store.undoReview(restored, log.id)
        cards[log.cardId] = restored
        todayLogs.removeAll { it.id == log.id }
        return restored
    }

    /** Time until the card is due again for each rating, in milliseconds. */
    fun preview(card: Card): Map<Rating, Long> {
        val now = clock()
        return fsrs.preview(card.memory, now).mapValues { (_, s) -> s.due - now }
    }

    fun counts(): Counts {
        val now = clock()
        refreshDay(now)
        val active = cards.values.filter { it.entryId in entryById }
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
        val active = cards.values.filter { it.entryId in entryById }
        val due = active.filter { it.memory.phase != Phase.NEW && it.memory.due < horizon }.map { it.entryId }
        val fresh = active.filter { it.type == CardType.MEANING && it.memory.phase == Phase.NEW }
            .sortedBy { order.getValue(it.entryId) }
            .take(settings.newWordsPerDay * (days + 1))
            .map { it.entryId }
        // Grammar cards unlocking soon belong to words already counted above.
        return (due + fresh).distinct().map(entryById::getValue)
    }

    /** Words whose meaning card has been seen at least once. */
    fun learnedWords(): Int =
        cards.values.count { it.type == CardType.MEANING && it.entryId in entryById && it.memory.phase != Phase.NEW }

    fun totalWords(): Int = entryById.size

    fun reviewsToday(): Int = todayLogs.size

    /** Review cards due on each of the next [days] days; overdue cards count toward today. */
    fun forecast(days: Int = 7): IntArray {
        refreshDay(clock())
        val result = IntArray(days)
        cards.values.filter { it.memory.phase == Phase.REVIEW && it.entryId in entryById }.forEach {
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
            .sortedWith(compareBy({ order.getValue(it.entryId) }, { it.type.ordinal }))
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
}
