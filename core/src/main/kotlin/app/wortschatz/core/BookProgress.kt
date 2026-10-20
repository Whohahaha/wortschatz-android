package app.wortschatz.core

import app.wortschatz.core.fsrs.Phase

/** How far the learner is in one word book, shown in the book list. */
data class BookProgress(val learned: Int, val total: Int, val due: Int)

/**
 * Progress lives in the study database keyed by entry id, not in the book, so it survives
 * switching books, deleting a downloaded book and downloading it again, and book updates.
 * [due] counts review cards due before [dayEnd]; they wait while another book is active.
 */
fun bookProgress(entryIds: Collection<String>, cards: Collection<Card>, known: Set<String>, dayEnd: Long): BookProgress {
    val ids = entryIds.toHashSet()
    val mine = cards.filter { it.entryId in ids && it.entryId !in known }
    val learned = cards.count { it.type == CardType.MEANING && it.entryId in ids && (it.memory.phase != Phase.NEW || it.entryId in known) }
    val due = mine.count { it.memory.phase != Phase.NEW && it.memory.due < dayEnd }
    return BookProgress(learned, ids.size, due)
}
