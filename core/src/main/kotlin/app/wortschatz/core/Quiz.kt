package app.wortschatz.core

import app.wortschatz.core.fsrs.Phase
import kotlin.random.Random

/** How a meaning card is asked. The label is shown on the card so the kind of question is obvious. */
enum class QuizMode(val label: String) {
    /** Recall the meaning, flip, rate yourself. */
    FLIP("回想释义"),
    /** German shown, pick the Chinese meaning from four. */
    PICK_MEANING("四选一 · 选中文"),
    /** Chinese shown, pick the German word from four. */
    PICK_WORD("四选一 · 选德语");

    val isChoice: Boolean get() = this != FLIP
}

/**
 * Varies how a word is checked as it is being learned, so knowing it is verified, not only claimed:
 * a new word is first seen and flipped; while it is in learning steps the app checks it with
 * multiple choice, alternating direction; young reviews (under a week) keep alternating choices,
 * and established words go back to free recall. Spelling and gap-fill cards follow once the word
 * has graduated.
 */
object Quiz {
    const val OPTIONS = 4
    private const val YOUNG_DAYS = 7.0

    fun modeFor(card: Card): QuizMode {
        if (card.type != CardType.MEANING) return QuizMode.FLIP
        val m = card.memory
        return when (m.phase) {
            Phase.NEW -> QuizMode.FLIP
            Phase.LEARNING, Phase.RELEARNING -> if (m.reps % 2 == 1) QuizMode.PICK_MEANING else QuizMode.PICK_WORD
            Phase.REVIEW -> when {
                m.stability >= YOUNG_DAYS -> QuizMode.FLIP
                m.reps % 2 == 0 -> QuizMode.PICK_WORD
                else -> QuizMode.PICK_MEANING
            }
        }
    }

    /**
     * Four entries, [entry] among them, in a stable shuffled order for this [seed]. Distractors
     * have the same part of speech and look alike (shared beginning, same article), but never
     * share a meaning with the answer, so exactly one option is right.
     */
    fun options(entry: Entry, entries: List<Entry>, seed: Long): List<Entry> {
        val random = Random(seed)
        val mine = senses(entry)
        val usable = entries.filter {
            it.id != entry.id && it.lemma != entry.lemma && it.meaning != entry.meaning && senses(it).none(mine::contains)
        }.distinctBy { it.meaning }
        fun likeness(e: Entry): Int {
            val a = e.lemma.lowercase()
            val b = entry.lemma.lowercase()
            var prefix = 0
            while (prefix < minOf(a.length, b.length) && a[prefix] == b[prefix]) prefix++
            return prefix * 2 + (if (e.gender != null && e.gender == entry.gender) 2 else 0) +
                (if (kotlin.math.abs(a.length - b.length) <= 2) 1 else 0)
        }
        val samePos = usable.filter { it.pos == entry.pos }
        // The closest look-alikes, with some randomness so the same three do not always come up.
        val pool = samePos.map { it to likeness(it) + random.nextInt(3) }.sortedByDescending { it.second }.map { it.first }.take(10).shuffled(random)
        val distractors = (pool + usable.shuffled(random)).distinct().take(OPTIONS - 1)
        return (distractors + entry).shuffled(random)
    }

    private fun senses(e: Entry) = e.meaning.split('，', ',', '；', ';', '/', '、', '（', '）', '(', ')', ' ')
        .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
