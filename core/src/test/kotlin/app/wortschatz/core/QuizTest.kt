package app.wortschatz.core

import app.wortschatz.core.fsrs.MemoryState
import app.wortschatz.core.fsrs.Phase
import app.wortschatz.core.fsrs.Rating
import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuizTest {
    private val book = WordBook.parse(File("../app/src/main/assets/wordbooks/goethe_a1.tsv").readText())

    @Test
    fun modeFollowsTheLearningStage() {
        val card = Card("x", CardType.MEANING)
        assertEquals(QuizMode.FLIP, Quiz.modeFor(card))
        assertEquals(QuizMode.PICK_MEANING, Quiz.modeFor(card.copy(memory = MemoryState(phase = Phase.LEARNING, reps = 1))))
        assertEquals(QuizMode.PICK_WORD, Quiz.modeFor(card.copy(memory = MemoryState(phase = Phase.LEARNING, reps = 2))))
        assertTrue(Quiz.modeFor(card.copy(memory = MemoryState(phase = Phase.REVIEW, stability = 3.0, reps = 3))).isChoice)
        assertEquals(QuizMode.FLIP, Quiz.modeFor(card.copy(memory = MemoryState(phase = Phase.REVIEW, stability = 30.0, reps = 5))))
        assertEquals(QuizMode.FLIP, Quiz.modeFor(Card("x", CardType.ARTICLE, MemoryState(phase = Phase.LEARNING, reps = 1))))
    }

    @Test
    fun optionsHaveOneRightAnswerAndAreStable() {
        for (entry in book.take(200)) {
            val options = Quiz.options(entry, book, seed = 42)
            assertEquals(4, options.size, entry.lemma)
            assertEquals(1, options.count { it.id == entry.id })
            assertEquals(4, options.map { it.meaning }.toSet().size, "${entry.lemma}: ${options.map { it.meaning }}")
            assertEquals(options, Quiz.options(entry, book, seed = 42))
        }
        val tisch = book.first { it.lemma == "Tisch" }
        assertTrue(Quiz.options(tisch, book, 1).all { it.pos == PartOfSpeech.NOUN })
    }

    @Test
    fun newWordIsCheckedByChoiceInTheSameSession() {
        var now = 1_704_103_200_000L
        val s = StudySession(book.take(5), InMemoryStudyStore(), StudySettings(newWordsPerDay = 1, zone = ZoneOffset.UTC)) { now }
        val first = s.nextCard()!!
        assertEquals(QuizMode.FLIP, Quiz.modeFor(first))
        s.answer(first, Rating.GOOD, 1000)
        now += 11 * 60_000
        val again = s.nextCard()!!
        assertEquals(first.id, again.id)
        assertTrue(Quiz.modeFor(again).isChoice)
    }
}

class ConjugationTest {
    private fun verb(lemma: String, er: String, prefix: String? = null) = Entry(
        lemma, "A1", PartOfSpeech.VERB, lemma, meaning = "-", separablePrefix = prefix,
        verbForms = VerbForms(er, "", "", "haben"),
    )

    @Test
    fun presentTense() {
        assertEquals(listOf("fahre", "fährst", "fährt", "fahren", "fahrt", "fahren"), Conjugation.present(verb("fahren", "fährt")))
        assertEquals(listOf("arbeite", "arbeitest", "arbeitet", "arbeiten", "arbeitet", "arbeiten"), Conjugation.present(verb("arbeiten", "arbeitet")))
        assertEquals("liest", Conjugation.present(verb("lesen", "liest"))!![1])
        assertEquals("isst", Conjugation.present(verb("essen", "isst"))!![1])
        assertEquals("hältst", Conjugation.present(verb("halten", "hält"))!![1])
        assertEquals("lädst", Conjugation.present(verb("laden", "lädt"))!![1])
        assertEquals(listOf("feiere", "feierst", "feiert", "feiern", "feiert", "feiern"), Conjugation.present(verb("feiern", "feiert")))
        assertEquals("rufe an", Conjugation.present(verb("anrufen", "ruft an", "an"))!![0])
        assertEquals("kannst", Conjugation.present(verb("können", "kann"))!![1])
        assertEquals("bin", Conjugation.present(verb("sein", "ist"))!![0])
    }

    @Test
    fun everyBundledVerbConjugates() {
        val book = WordBook.parse(java.io.File("../app/src/main/assets/wordbooks/goethe_a1.tsv").readText())
        val verbs = book.filter { it.pos == PartOfSpeech.VERB && ' ' !in it.lemma }
        val missing = verbs.filter { Conjugation.present(it) == null }.map { it.lemma }
        assertTrue(missing.isEmpty(), "no conjugation for $missing")
    }
}
