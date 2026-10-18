package app.wortschatz.core

import app.wortschatz.core.fsrs.Fsrs
import app.wortschatz.core.fsrs.Fsrs.Companion.DAY
import app.wortschatz.core.fsrs.Fsrs.Companion.MINUTE
import app.wortschatz.core.fsrs.MemoryState
import app.wortschatz.core.fsrs.Phase
import app.wortschatz.core.fsrs.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsrsTest {
    private val fsrs = Fsrs()
    private val t0 = 1_700_000_000_000L

    @Test
    fun newCardFollowsLearningSteps() {
        val p = fsrs.preview(MemoryState(), t0)
        assertEquals(t0 + MINUTE, p.getValue(Rating.AGAIN).due)
        assertEquals(t0 + 5 * MINUTE + 30_000, p.getValue(Rating.HARD).due)
        assertEquals(t0 + 10 * MINUTE, p.getValue(Rating.GOOD).due)
        assertEquals(Phase.LEARNING, p.getValue(Rating.GOOD).phase)
        assertEquals(Phase.REVIEW, p.getValue(Rating.EASY).phase)
        // Easy on a new card: initial stability w[3] ~ 15.7 days at 90% retention.
        assertEquals(16, (p.getValue(Rating.EASY).due - t0) / DAY)
    }

    @Test
    fun graduatingIntervalMatchesInitialStability() {
        var s = fsrs.review(MemoryState(), Rating.GOOD, t0)
        s = fsrs.review(s, Rating.GOOD, t0 + 10 * MINUTE)
        assertEquals(Phase.REVIEW, s.phase)
        val days = (s.due - (t0 + 10 * MINUTE)) / DAY
        assertTrue(days in 3..5, "first interval was $days days")
    }

    @Test
    fun intervalEqualsStabilityAtNinetyPercent() {
        assertEquals(10, fsrs.intervalDays(10.0))
        assertEquals(100, fsrs.intervalDays(100.0))
        assertTrue(Fsrs(desiredRetention = 0.8).intervalDays(10.0) > 10)
        assertTrue(Fsrs(desiredRetention = 0.95).intervalDays(10.0) < 10)
    }

    @Test
    fun successfulReviewsGrowIntervalsAndButtonsAreOrdered() {
        var s = fsrs.review(MemoryState(), Rating.EASY, t0)
        var now = s.due
        var last = 0L
        repeat(4) {
            val p = fsrs.preview(s, now)
            val hard = p.getValue(Rating.HARD).due
            val good = p.getValue(Rating.GOOD).due
            val easy = p.getValue(Rating.EASY).due
            assertTrue(hard <= good && good < easy, "button order broken: $hard $good $easy")
            val interval = (good - now) / DAY
            assertTrue(interval > last, "interval did not grow: $interval after $last")
            last = interval
            s = p.getValue(Rating.GOOD)
            now = s.due
        }
    }

    @Test
    fun lapseGoesToRelearningWithLowerStability() {
        val learned = fsrs.review(MemoryState(), Rating.EASY, t0)
        val lapsed = fsrs.review(learned, Rating.AGAIN, learned.due)
        assertEquals(Phase.RELEARNING, lapsed.phase)
        assertEquals(1, lapsed.lapses)
        assertEquals(learned.due + 10 * MINUTE, lapsed.due)
        assertTrue(lapsed.stability < learned.stability)
        assertTrue(lapsed.difficulty > learned.difficulty)
        val relearned = fsrs.review(lapsed, Rating.GOOD, lapsed.due)
        assertEquals(Phase.REVIEW, relearned.phase)
    }

    @Test
    fun retrievabilityDecaysToTargetAtInterval() {
        val s = fsrs.review(MemoryState(), Rating.EASY, t0)
        val r = fsrs.retrievability(s, t0 + fsrs.intervalDays(s.stability) * DAY)
        assertTrue(r in 0.88..0.92, "R at due date was $r")
    }
}
