package app.wortschatz.core

/** Where a word stands, judged by its meaning card. */
enum class WordStatus(val label: String) {
    NEW("未学"), LEARNING("学习中"), MASTERED("已掌握"), KNOWN("已斩");
}

/** One row of the word list. [lapses] is the most times any one of its cards was forgotten. */
data class WordProgress(
    val entry: Entry,
    val status: WordStatus,
    val lapses: Int,
    /** The card type forgotten most often, for "冠词忘了 5 次". */
    val hardestType: CardType?,
) {
    val isLeech: Boolean get() = lapses >= LEECH_LAPSES

    companion object {
        /** Forgotten this often, a word needs a different approach rather than more of the same. */
        const val LEECH_LAPSES = 4
        /** A meaning card this stable (in days) counts as mastered. */
        const val MASTERED_DAYS = 21.0
    }
}

/** Numbers for the statistics page. */
data class Stats(
    /** Answers per study day, oldest first, ending today. */
    val daily: IntArray,
    /** Share of review answers in the last 30 days that were not "again"; null without reviews. */
    val retention: Double?,
    val byStatus: Map<WordStatus, Int>,
    val totalAnswers: Int,
    val longestStreak: Int,
)

/** A milestone shown on the statistics page once reached. */
data class Milestone(val title: String, val reached: Boolean)

object Milestones {
    fun of(stats: Stats, totalWords: Int): List<Milestone> {
        val learned = totalWords - (stats.byStatus[WordStatus.NEW] ?: 0)
        return listOf(
            Milestone("学会第一个词", learned >= 1),
            Milestone("学满 100 词", learned >= 100),
            Milestone("学满 300 词", learned >= 300),
            Milestone("学完整本词书", totalWords > 0 && learned >= totalWords),
            Milestone("连续学习 7 天", stats.longestStreak >= 7),
            Milestone("连续学习 30 天", stats.longestStreak >= 30),
            Milestone("掌握 100 词", (stats.byStatus[WordStatus.MASTERED] ?: 0) >= 100),
            Milestone("答题 1000 次", stats.totalAnswers >= 1000),
        )
    }
}

/** Other words in the book that share a Chinese meaning with [entry], such as 认识 for kennen and wissen. */
fun confusables(entry: Entry, entries: List<Entry>, limit: Int = 3): List<Entry> {
    fun senses(e: Entry) = e.meaning.split('，', ',', '；', ';', '/', '、', '（', '）', '(', ')', ' ')
        .map { it.trim() }.filter { it.length >= 2 }.toSet()
    val mine = senses(entry)
    if (mine.isEmpty()) return emptyList()
    return entries.filter { it.id != entry.id && it.lemma != entry.lemma && senses(it).any(mine::contains) }.take(limit)
}
