package app.wortschatz.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordFamilyTest {
    private val a1 = WordBook.parse(File("../app/src/main/assets/wordbooks/goethe_a1.tsv").readText())
    private val families = WordFamilies(a1)
    private fun lemma(l: String) = a1.first { it.lemma == l }
    private fun family(l: String) = families.familyOf(lemma(l)).map { it.lemma }

    @Test
    fun groupsVerbsWithTheirPrefixedFormsAndDerivedNouns() {
        assertTrue(listOf("fahren", "abfahren", "Fahrer", "Abfahrt").all { it in family("fahren") }, family("fahren").toString())
        assertEquals("ab", families.prefixOf(lemma("abfahren")))
        assertTrue("bekommen" in family("ankommen"))
        assertTrue("Frage" in family("fragen"))
    }

    @Test
    fun leavesLookalikesAlone() {
        assertTrue("Reis" !in family("reisen"))
        assertTrue("seit" !in family("sein"))
        assertTrue("fehlen" !in family("empfehlen"))
        assertTrue(families.familyOf(lemma("Tisch")).isEmpty())
    }

    @Test
    fun familyOrderKeepsEveryWordOnceAndFamiliesTogether() {
        val grouped = families.grouped(a1)
        assertEquals(a1.map { it.id }.sorted(), grouped.map { it.id }.sorted())
        val at = grouped.withIndex().associate { it.value.lemma to it.index }
        val positions = family("fahren").map { at.getValue(it) }.sorted()
        assertEquals(positions.last() - positions.first(), positions.size - 1)
    }

    @Test
    fun sessionIntroducesAFamilyInARow() {
        val s = StudySession(a1, InMemoryStudyStore(), StudySettings(newWordsPerDay = 300, newWordOrder = NewWordOrder.FAMILY))
        val first = generateSequence { s.nextCard()?.also { s.answer(it, app.wortschatz.core.fsrs.Rating.EASY, 1000) } }
            .filter { it.type == CardType.MEANING }.map { it.entryId }.distinct().take(a1.size).toList()
        val index = first.withIndex().associate { it.value to it.index }
        val positions = families.familyOf(lemma("fahren")).map { index.getValue(it.id) }.sorted()
        assertEquals(positions.last() - positions.first(), positions.size - 1)
    }
}
