package app.wortschatz.core

import app.wortschatz.core.fsrs.Rating
import app.wortschatz.core.imports.GermanParsing
import app.wortschatz.core.imports.Lexicon
import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NounNumberTest {
    private val book = """
        id	level	pos	lemma	gender	plural	meaning	present3	preterite	participle	aux	example	example_zh	number
        eltern	A1	n	Eltern	die		父母					Meine Eltern wohnen in Shanghai.	我父母住在上海。	pl
        lehrer	A1	n	Lehrer	der	Lehrer	老师
        milch	A1	n	Milch	die		牛奶
        tisch	A1	n	Tisch	der	Tische	桌子
    """.trimIndent()

    private val entries = WordBook.parse(book)
    private fun entry(id: String) = entries.first { it.id == id }

    @Test
    fun pluralOnlyNounsAreNeverAskedForArticleOrPlural() {
        val eltern = entry("eltern")
        assertTrue(eltern.pluralOnly)
        assertEquals("die Eltern", eltern.display)
        assertFalse(CardType.ARTICLE in cardTypesFor(eltern))
        assertFalse(CardType.PLURAL in cardTypesFor(eltern))
        assertTrue(CardType.SPELLING in cardTypesFor(eltern))
        assertEquals("只有复数形式", eltern.pluralLabel)
        assertNull(Hints.gender(eltern))
    }

    @Test
    fun sameFormAndUncountableNounsAreLabelled() {
        assertEquals("复数：die Lehrer（单复同形）", entry("lehrer").pluralLabel)
        assertTrue(CardType.PLURAL in cardTypesFor(entry("lehrer")))
        assertEquals("无复数", entry("milch").pluralLabel)
        assertFalse(CardType.PLURAL in cardTypesFor(entry("milch")))
        assertEquals("复数：die Tische", entry("tisch").pluralLabel)
    }

    @Test
    fun numberColumnSurvivesWritingAndOldBooksStillParse() {
        assertEquals(entries, WordBook.parse(WordBook.write(entries, "t")))
        // Books without the column (older downloads) read every noun as having a singular.
        val old = WordBook.parse(book.lines().joinToString("\n") { it.removeSuffix("\tnumber").removeSuffix("\tpl") })
        assertFalse(old.first().pluralOnly)
    }

    @Test
    fun cardsOfARetiredTypeAreNoLongerAsked() {
        // Eltern used to be an ordinary feminine noun with an article card.
        val store = InMemoryStudyStore()
        val before = entries.map { if (it.pluralOnly) it.copy(pluralOnly = false) else it }
        var now = 1_704_103_200_000L
        val settings = StudySettings(newWordsPerDay = 10, zone = ZoneOffset.UTC)
        val s1 = StudySession(before, store, settings) { now }
        val meaning = s1.nextCard()!!
        assertEquals("eltern", meaning.entryId)
        s1.answer(meaning, Rating.EASY, 1000)
        assertTrue(store.loadCards().any { it.entryId == "eltern" && it.type == CardType.ARTICLE })

        now += 3 * 86_400_000L
        val s2 = StudySession(entries, store, settings) { now }
        val asked = generateSequence { s2.nextCard()?.also { s2.answer(it, Rating.EASY, 1000) } }.take(50).toList()
        assertTrue(asked.none { it.entryId == "eltern" && it.type == CardType.ARTICLE })
        assertTrue(s2.drillNouns(10).none { it.pluralOnly })
    }

    @Test
    fun lookupKeepsPluralOnlyWordsAsTheyAre() {
        val lexicon = Lexicon.parse(sequenceOf("Elter\tn\tdas\tEltern\t\t\t\t", "Leute\tn\tdie\t\t\t\t\t"))
        val inBook = Lookup(entries, lexicon).find("Eltern")
        assertEquals(entry("eltern"), inBook.entry)
        assertNull(inBook.noun)
        assertTrue(inBook.pluralOnly)

        val notInBook = Lookup(emptyList(), lexicon).find("Eltern")
        assertNull(notInBook.nounLemma)
        assertTrue(notInBook.pluralOnly)
        assertTrue(notInBook.found)
    }

    @Test
    fun importRecognisesPluralOnlyMarks() {
        for (text in listOf("Eltern (Pl.)", "die Leute, nur Pl.", "Ferien (Plural)", "Kosten pl")) {
            val head = GermanParsing.parse(text)
            assertTrue(head.pluralOnly, text)
            assertEquals(Gender.DIE, head.gender, text)
            assertNull(head.plural, text)
        }
        assertFalse(GermanParsing.parse("der Tisch, Pl. Tische").pluralOnly)
        assertEquals("Tische", GermanParsing.parse("der Tisch, Pl. Tische").plural)
        assertFalse(GermanParsing.parse("Apfel").pluralOnly)
    }

    @Test
    fun bundledBookMarksPluralOnlyNouns() {
        val a1 = WordBook.parse(File("../app/src/main/assets/wordbooks/goethe_a1.tsv").readText())
        val pluralOnly = a1.filter { it.pluralOnly }.map { it.lemma }.toSet()
        assertTrue(setOf("Eltern", "Leute", "Geschwister", "Großeltern").all { it in pluralOnly }, pluralOnly.toString())
        // A noun marked "（复数）" in its meaning must be plural-only, or it would get an article question.
        assertEquals(emptyList(), a1.filter { it.meaning.contains("（复数）") && !it.pluralOnly }.map { it.lemma })
    }
}
