package app.wortschatz.core

/** How a typed answer compares with the expected one. */
enum class Verdict { CORRECT, CLOSE, WRONG }

/**
 * Typed answers: spelling a word from its meaning, and filling the gap in an example sentence.
 * The app grades them, so the learner has to produce the word instead of only recognising it.
 */
object Typing {
    private val plainWord = Regex("\\p{L}+([ -]\\p{L}+)*")
    private val articles = setOf("der", "die", "das")

    /** Words that can be typed as a spelling exercise; lists like "der, die, das" cannot. */
    fun spellable(entry: Entry): Boolean = plainWord.matches(entry.lemma)

    /**
     * CORRECT for an exact match; CLOSE when only capitalisation differs, umlauts were written
     * as ae / oe / ue / ss, or one letter is off in a longer word; WRONG otherwise.
     */
    fun grade(input: String, answer: String): Verdict {
        val given = tidy(input)
        val expected = tidy(answer)
        if (given.isEmpty()) return Verdict.WRONG
        if (given == expected) return Verdict.CORRECT
        val a = fold(given)
        val b = fold(expected)
        if (a == b) return Verdict.CLOSE
        if (b.length >= 5 && distance(a, b) <= 1) return Verdict.CLOSE
        return Verdict.WRONG
    }

    /** Grades a spelling answer; typing the article in front of a noun is fine. */
    fun gradeSpelling(input: String, entry: Entry): Verdict {
        val words = tidy(input).split(' ')
        val withoutArticle = if (entry.gender != null && words.size > 1 && words[0].lowercase() in articles) {
            words.drop(1).joinToString(" ")
        } else input
        return grade(withoutArticle, entry.lemma)
    }

    private fun tidy(s: String) = s.trim().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")

    private fun fold(s: String) = s.lowercase().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

/** An example sentence with the word blanked out: "Ich ____ dich heute Abend ____." */
data class Cloze(val prompt: String, val answer: String, val gaps: List<IntRange>) {
    /** A gap at the start of the sentence is capitalised there; "wo" for "Wo" still counts. */
    fun grade(input: String): Verdict {
        val verdict = Typing.grade(input, answer)
        if (verdict != Verdict.CLOSE || gaps.first().first != 0) return verdict
        return Typing.grade(input.trim().replaceFirstChar { it.uppercase() }, answer)
    }

    companion object {
        const val GAP = "____"
        private val wordPattern = Regex("\\p{L}+")

        /** Finds the entry's word in its example sentence; null when it cannot be found. */
        fun of(entry: Entry): Cloze? {
            val example = entry.example
            if (example.isBlank() || !Typing.spellable(entry) || ' ' in entry.lemma) return null
            val ranges = Hints.separableParts(example, entry).ifEmpty { find(example, entry)?.let(::listOf).orEmpty() }
            if (ranges.isEmpty()) return null
            val answer = ranges.joinToString(" ") { example.substring(it) }
            val prompt = buildString {
                var at = 0
                for (r in ranges) {
                    append(example, at, r.first)
                    append(GAP)
                    at = r.last + 1
                }
                append(example, at, example.length)
            }
            return Cloze(prompt, answer, ranges)
        }

        private fun find(example: String, entry: Entry): IntRange? {
            val words = wordPattern.findAll(example).toList()
            val forms = buildSet {
                add(entry.lemma)
                entry.plural?.let(::add)
                entry.verbForms?.let { v ->
                    listOf(v.present3, v.preterite, v.participle).forEach { addAll(it.split(' ')) }
                }
            }.map { it.lowercase() }.toSet()
            words.firstOrNull { it.value.lowercase() in forms }?.let { return it.range }
            // Inflected forms: "kaufe", "kleinen", "Kindern" share the start of the lemma.
            val stem = entry.lemma.lowercase().removeSuffix("en").removeSuffix("n").removeSuffix("e")
            if (stem.length < 3) return null
            return words.firstOrNull { it.value.lowercase().startsWith(stem) && it.value.length <= entry.lemma.length + 4 }?.range
        }
    }
}
