package app.wortschatz.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HintsTest {
    private val book = WordBook.parse(File("../app/src/main/assets/wordbooks/goethe_a1.tsv").readText())
    private fun word(lemma: String) = book.first { it.lemma == lemma }

    @Test
    fun articleRulesOfThumb() {
        assertEquals(GenderHint("以 -ung 结尾的名词一般是 die", true), Hints.gender(word("Wohnung")))
        assertEquals(GenderHint("以 -chen 结尾的名词一般是 das", true), Hints.gender(word("Mädchen")))
        assertEquals(GenderHint("以 -e 结尾的名词大多是 die", false), Hints.gender(word("Junge")))
        assertNull(Hints.gender(word("Tisch")))
        assertNull(Hints.gender(word("fahren")))
    }

    @Test
    fun separableVerbIsHighlightedInItsExample() {
        fun parts(example: String, lemma: String) =
            Hints.separableParts(example, word(lemma)).map { example.substring(it) }
        assertEquals(listOf("rufe", "an"), parts("Ich rufe dich heute Abend an.", "anrufen"))
        assertEquals(listOf("fährt", "ab"), parts("Der Bus fährt in fünf Minuten ab.", "abfahren"))
        assertEquals(listOf("kam", "an"), parts("Wann kam der Zug in Hamburg an?", "ankommen"))
        assertEquals(listOf("anrufen"), parts("Kannst du mich morgen anrufen?", "anrufen"))
        assertEquals(listOf("aufgestanden"), parts("Ich bin früh aufgestanden.", "aufstehen"))
        assertEquals(emptyList(), parts("Ich fahre morgen nach Berlin.", "fahren"))
    }
}
