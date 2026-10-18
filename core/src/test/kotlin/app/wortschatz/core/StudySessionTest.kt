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
    fun forecastCountsUpcomingReviews() {
        val s = session()
        while (true) s.answer(s.nextCard() ?: break, Rating.EASY, 1000)
        val f = s.forecast(30)
        assertEquals(2, f.sum())
    }

    @Test
    fun bundledWordBookParses() {
        val file = File("../app/src/main/assets/wordbooks/goethe_a1_sample.tsv")
        val entries = WordBook.parse(file.readText())
        assertTrue(entries.size >= 80, "only ${entries.size} entries")
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
