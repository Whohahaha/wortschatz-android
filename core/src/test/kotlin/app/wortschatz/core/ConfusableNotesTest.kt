package app.wortschatz.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfusableNotesTest {
    private val file = File("../app/src/main/assets/confusables.tsv").readText()

    private fun word(lemma: String, pos: PartOfSpeech = PartOfSpeech.VERB) = Entry(lemma.lowercase(), "A1", pos, lemma, meaning = "")

    @Test
    fun findsTheGroupsOfAWord() {
        val notes = ConfusableNotes.parse(file)
        val kennen = notes.groupsFor(word("kennen")).single()
        assertEquals(listOf("kennen", "wissen"), kennen.words)
        assertEquals(Swap.NO, kennen.swap)
        assertTrue(kennen.examples.isNotEmpty())
        assertEquals("Ich kenne Berlin sehr gut.", kennen.examples.first().german)
        // Nouns match by their capitalised headword only.
        assertEquals(listOf("Mal", "Zeit"), notes.groupsFor(word("Mal", PartOfSpeech.NOUN)).single().words)
        assertTrue(notes.groupsFor(word("Zeit", PartOfSpeech.NOUN)).size == 2)
        assertTrue(notes.groupsFor(word("Tisch", PartOfSpeech.NOUN)).isEmpty())
    }

    @Test
    fun everyLineOfTheBundledFileIsUsable() {
        val lines = file.lines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
        val notes = ConfusableNotes.parse(file)
        val words = lines.flatMap { it.substringBefore('\t').split(',') }.toSet()
        // Every line parsed: each word finds at least the group of its own line.
        words.forEach { assertTrue(notes.groupsFor(word(it)).isNotEmpty(), it) }
        lines.forEach { line ->
            val cells = line.split('\t')
            assertTrue(Swap.parse(cells[1]) != null, line)
            assertTrue(cells.drop(3).all { it.contains('｜') }, line)
            assertTrue(cells.size >= 5, "at least two examples: $line")
        }
    }
}
