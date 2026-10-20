package app.wortschatz.core

import app.wortschatz.core.fsrs.MemoryState
import app.wortschatz.core.fsrs.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogTest {
    private val json = """
        {"format": 1, "books": [
          {"id": "goethe_a2", "title": "歌德 A2 词书", "description": "A2 新增词", "level": "A2", "version": 3, "words": 588,
           "book": {"path": "goethe_a2/goethe_a2-3.tsv", "bytes": 120000, "sha256": "AB12"},
           "audio": [{"path": "goethe_a2/goethe_a2-3-audio-1.zip", "bytes": 9000000, "sha256": "cd34"},
                     {"path": "goethe_a2/goethe_a2-3-audio-2.zip", "bytes": 1000000, "sha256": "ef56"}]},
          {"id": "no_audio", "title": "无发音", "version": 1, "book": {"path": "x/x-1.tsv", "bytes": 10, "sha256": ""}},
          {"id": "../evil", "title": "bad id", "version": 1, "book": {"path": "a.tsv", "bytes": 1, "sha256": ""}},
          {"id": "escape", "title": "bad path", "version": 1, "book": {"path": "../../a.tsv", "bytes": 1, "sha256": ""}},
          {"id": "broken", "title": "no version"}
        ]}
    """

    @Test
    fun parsesBooksAndSkipsUnsafeOrBrokenOnes() {
        val books = Catalog.parse(json)
        assertEquals(listOf("goethe_a2", "no_audio"), books.map { it.id })
        val a2 = books[0]
        assertEquals(3, a2.version)
        assertEquals(588, a2.words)
        assertEquals("ab12", a2.book.sha256)
        assertEquals(listOf("goethe_a2/goethe_a2-3-audio-1.zip", "goethe_a2/goethe_a2-3-audio-2.zip"), a2.audio.map { it.path })
        assertEquals(10_120_000, a2.bytes)
        assertEquals(emptyList(), books[1].audio)
    }

    @Test
    fun refusesNewerFormat() {
        assertFailsWith<IllegalArgumentException> { Catalog.parse("""{"format": 2, "books": []}""") }
    }

    @Test
    fun progressIsCountedPerBookFromSharedCards() {
        val reviewed = MemoryState(phase = Phase.REVIEW, due = 500)
        val cards = listOf(
            Card("a", CardType.MEANING, reviewed),
            Card("a", CardType.ARTICLE, MemoryState(phase = Phase.REVIEW, due = 2000)),
            Card("b", CardType.MEANING),
            Card("c", CardType.MEANING, reviewed),
            Card("other", CardType.MEANING, reviewed),
        )
        // "c" was marked as known: learned, but never due.
        assertEquals(BookProgress(learned = 2, total = 3, due = 1), bookProgress(listOf("a", "b", "c"), cards, setOf("c"), dayEnd = 1000))
    }

    @Test
    fun formatsSizes() {
        assertEquals("12.3 MB", formatBytes(12_345_678))
        assertEquals("820 KB", formatBytes(820_400))
    }
}
