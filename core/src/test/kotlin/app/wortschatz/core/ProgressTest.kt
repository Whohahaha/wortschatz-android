package app.wortschatz.core

import app.wortschatz.core.fsrs.Fsrs.Companion.DAY
import app.wortschatz.core.fsrs.Rating
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressTest {
    private val book = """
        id	level	pos	lemma	gender	plural	meaning	present3	preterite	participle	aux	example	example_zh
        tisch	A1	n	Tisch	der	Tische	桌子
        lampe	A1	n	Lampe	die	Lampen	灯
        kennen	A1	v	kennen			认识，了解	kennt	kannte	gekannt	haben
        wissen	A1	v	wissen			知道，认识	weiß	wusste	gewusst	haben
    """.trimIndent()
    private var now = 1_704_103_200_000L

    private fun session(store: StudyStore = InMemoryStudyStore()) =
        StudySession(WordBook.parse(book), store, StudySettings(newWordsPerDay = 2, zone = ZoneOffset.UTC)) { now }

    @Test
    fun wordStatusesAndRestoringKnownWords() {
        val s = session()
        s.answer(s.nextCard()!!, Rating.AGAIN, 1000)
        s.markKnown(s.nextCard()!!.also { assertEquals("lampe", it.entryId) })
        val statuses = s.wordProgress().associate { it.entry.id to it.status }
        assertEquals(WordStatus.LEARNING, statuses["tisch"])
        assertEquals(WordStatus.KNOWN, statuses["lampe"])
        assertEquals(WordStatus.NEW, statuses["kennen"])
        s.unmarkKnown("lampe")
        assertEquals(WordStatus.NEW, s.wordProgress().first { it.entry.id == "lampe" }.status)
        assertEquals(0, s.knownWords())
    }

    @Test
    fun statsCountDaysRetentionAndStreaks() {
        val s = session()
        s.answer(s.nextCard()!!, Rating.EASY, 1000)
        now += DAY
        s.answer(s.nextCard()!!, Rating.GOOD, 1000)
        val stats = s.stats(days = 7)
        assertEquals(7, stats.daily.size)
        assertEquals(1, stats.daily[6])
        assertEquals(1, stats.daily[5])
        assertEquals(2, stats.longestStreak)
        assertEquals(2, stats.totalAnswers)
        assertTrue(Milestones.of(stats, 4).first().reached)
    }

    @Test
    fun confusablesShareAChineseMeaning() {
        val entries = WordBook.parse(book)
        val kennen = entries.first { it.id == "kennen" }
        assertEquals(listOf("wissen"), confusables(kennen, entries).map { it.id })
        assertTrue(confusables(entries.first { it.id == "tisch" }, entries).isEmpty())
    }
}
