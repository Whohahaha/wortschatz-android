package app.wortschatz.core

import app.wortschatz.core.fsrs.Fsrs.Companion.DAY
import app.wortschatz.core.fsrs.Fsrs.Companion.MINUTE
import app.wortschatz.core.fsrs.Phase
import app.wortschatz.core.fsrs.Rating
import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudySessionTest {
    private val book = """
        id	level	pos	lemma	gender	plural	meaning	present3	preterite	participle	aux	example	example_zh
        tisch	A1	n	Tisch	der	Tische	桌子
        lampe	A1	n	Lampe	die	Lampen	灯
        anrufen	A1	v	an|rufen			打电话	ruft an	rief an	angerufen	haben
    """.trimIndent()

    // 2024-01-01 10:00 UTC
    private var now = 1_704_103_200_000L
    private val settings = StudySettings(newWordsPerDay = 2, zone = ZoneOffset.UTC)

    private fun session(store: StudyStore = InMemoryStudyStore()) =
        StudySession(WordBook.parse(book), store, settings) { now }

    @Test
    fun createsOneCardPerKnowledgeDimension() {
        val s = session()
        assertEquals(3, s.totalWords())
        // tisch: meaning, article, plural; lampe: same; anrufen: meaning, verb forms
        val store = InMemoryStudyStore()
        session(store)
        assertEquals(8, store.loadCards().size)
    }

    @Test
    fun respectsNewWordLimitAndShowsOnlyMeaningCardsFirst() {
        val s = session()
        assertEquals(Counts(0, 0, 2), s.counts())
        val seen = mutableListOf<String>()
        while (true) {
            val card = s.nextCard() ?: break
            assertEquals(CardType.MEANING, card.type)
            seen += card.entryId
            s.answer(card, Rating.EASY, 1000)
        }
        assertEquals(listOf("tisch", "lampe"), seen)
        assertEquals(2, s.learnedWords())
    }

    @Test
    fun learningCardReturnsWithinSession() {
        val s = session()
        val first = s.nextCard()!!
        s.answer(first, Rating.AGAIN, 1000)
        val second = s.nextCard()!!
        assertNotEquals(first.id, second.id)
        s.answer(second, Rating.EASY, 1000)
        // Nothing else is new today; the failed card comes back via learn-ahead.
        val again = s.nextCard()!!
        assertEquals(first.id, again.id)
        assertEquals(Phase.LEARNING, again.memory.phase)
    }

    @Test
    fun grammarCardsUnlockOnALaterDayAndSiblingsAreBuried() {
        val s = session()
        while (true) s.answer(s.nextCard() ?: break, Rating.EASY, 1000)
        assertNull(s.nextCard())

        now += DAY
        val types = mutableListOf<Pair<String, CardType>>()
        while (true) {
            val card = s.nextCard() ?: break
            types += card.entryId to card.type
            s.answer(card, Rating.GOOD, 1000)
            if (types.size > 20) break
        }
        // Day 2: anrufen is new; tisch and lampe each get exactly one grammar card (the article).
        assertTrue(("anrufen" to CardType.MEANING) in types)
        assertTrue(("tisch" to CardType.ARTICLE) in types)
        assertTrue(("lampe" to CardType.ARTICLE) in types)
        assertTrue(types.none { it.second == CardType.PLURAL }, "plural card should be buried today: $types")
    }

    @Test
    fun undoRestoresStateAndLog() {
        val store = InMemoryStudyStore()
        val s = session(store)
        val card = s.nextCard()!!
        s.answer(card, Rating.GOOD, 1000)
        assertEquals(1, store.logsSince(0).size)
        val restored = s.undo()!!
        assertEquals(card, restored)
        assertEquals(0, store.logsSince(0).size)
        assertEquals(Counts(0, 0, 2), s.counts())
        assertEquals(card.id, s.nextCard()!!.id)
    }

    @Test
    fun stateSurvivesReload() {
        val store = InMemoryStudyStore()
        val s1 = session(store)
        s1.answer(s1.nextCard()!!, Rating.EASY, 1000)
        now += 5 * MINUTE
        val s2 = session(store)
        assertEquals(1, s2.learnedWords())
        assertEquals(1, s2.reviewsToday())
        assertEquals(Counts(0, 0, 1), s2.counts())
    }

    @Test
    fun upcomingEntriesCoverDueCardsAndTomorrowsNewWords() {
        val s = session()
        assertEquals(listOf("tisch", "lampe"), s.upcomingEntries(days = 0).map { it.id })
        assertEquals(listOf("tisch", "lampe", "anrufen"), s.upcomingEntries(days = 1).map { it.id })
        // A word answered "again" stays in learning and is still upcoming.
        s.answer(s.nextCard()!!, Rating.AGAIN, 1000)
        assertEquals("tisch", s.upcomingEntries(days = 0).first().id)
    }

    @Test
    fun knownWordsNeverComeBackUntilUndone() {
        val s = session()
        val first = s.nextCard()!!
        s.markKnown(first)
        assertEquals(1, s.knownWords())
        assertEquals(1, s.learnedWords())
        assertTrue(generateSequence { s.nextCard()?.also { s.answer(it, Rating.EASY, 1000) } }.none { it.entryId == first.entryId })
        // Undo the answers, then the "known" mark: the word is back.
        while (true) { if (s.undo()?.entryId == first.entryId) break }
        assertEquals(0, s.knownWords())
        assertEquals(first.entryId, s.nextCard()?.entryId)
    }

    @Test
    fun drillPrefersNounsAlreadyMet() {
        val s = session()
        assertEquals(setOf("tisch", "lampe"), s.drillNouns(5).map { it.id }.toSet())
        val first = s.nextCard()!!
        assertEquals("tisch", first.entryId)
        s.answer(first, Rating.GOOD, 1000)
        // Tisch has been met, Lampe is still new: Tisch is picked first.
        assertEquals(listOf("tisch"), s.drillNouns(1).map { it.id })
    }

    @Test
    fun newWordOrderCanBeAlphabeticalOrRandom() {
        fun newOrder(order: NewWordOrder, seed: Long = 0): List<String> {
            val s = StudySession(WordBook.parse(book), InMemoryStudyStore(), settings.copy(newWordsPerDay = 3, newWordOrder = order, shuffleSeed = seed)) { now }
            return generateSequence { s.nextCard()?.also { s.answer(it, Rating.EASY, 1000) } }.map { it.entryId }.toList()
        }
        assertEquals(listOf("tisch", "lampe", "anrufen"), newOrder(NewWordOrder.BOOK))
        assertEquals(listOf("anrufen", "lampe", "tisch"), newOrder(NewWordOrder.ALPHABETICAL))
        assertEquals(newOrder(NewWordOrder.RANDOM, 7), newOrder(NewWordOrder.RANDOM, 7), "same seed, same order")
        assertEquals(setOf("tisch", "lampe", "anrufen"), newOrder(NewWordOrder.RANDOM, 7).toSet())
        assertEquals(listOf("abend", "apfel", "arzt", "ubung"), listOf("Übung", "Arzt", "Apfel", "Abend").map(::germanSortKey).sorted())
    }

    @Test
    fun streakCountsConsecutiveStudyDays() {
        val s = session()
        assertEquals(0, s.streak())
        s.answer(s.nextCard()!!, Rating.GOOD, 1000)
        assertEquals(1, s.streak())
        now += DAY
        assertEquals(1, s.streak(), "nothing studied yet today keeps yesterday's streak")
        s.answer(s.nextCard()!!, Rating.GOOD, 1000)
        assertEquals(2, s.streak())
        now += 2 * DAY
        assertEquals(0, s.streak())
    }

    @Test
    fun forecastCountsUpcomingReviews() {
        val s = session()
        while (true) s.answer(s.nextCard() ?: break, Rating.EASY, 1000)
        val f = s.forecast(30)
        assertEquals(2, f.sum())
    }

    @Test
    fun bundledWordBookParses() {
        val file = File("../app/src/main/assets/wordbooks/goethe_a1.tsv")
        val entries = WordBook.parse(file.readText())
        assertTrue(entries.size >= 700, "only ${entries.size} entries")
        val tisch = entries.first { it.lemma == "Tisch" }
        assertEquals(Gender.DER, tisch.gender)
        assertEquals("Tische", tisch.plural)
        val anrufen = entries.first { it.lemma == "anrufen" }
        assertEquals("an·rufen", anrufen.display)
        assertEquals("angerufen", anrufen.verbForms?.participle)
        assertTrue(entries.all { it.meaning.isNotBlank() })
    }

    @Test
    fun intervalFormatting() {
        assertEquals("1分钟", formatInterval(MINUTE))
        assertEquals("10分钟", formatInterval(10 * MINUTE))
        assertEquals("4天", formatInterval(4 * DAY))
        assertEquals("2个月", formatInterval(60 * DAY))
    }
}
