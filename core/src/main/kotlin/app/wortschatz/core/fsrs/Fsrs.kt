package app.wortschatz.core.fsrs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

enum class Phase { NEW, LEARNING, REVIEW, RELEARNING }

/** Memory state of one card. Times are epoch milliseconds. */
data class MemoryState(
    val phase: Phase = Phase.NEW,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0L,
    val lastReview: Long? = null,
    /** Index into the learning or relearning steps while in those phases. */
    val step: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
)

/**
 * FSRS-5 scheduler (https://github.com/open-spaced-repetition).
 *
 * Model: difficulty D in [1, 10], stability S in days (the interval at which recall
 * probability drops to 90%), retrievability R = (1 + FACTOR * t / S) ^ DECAY.
 * Learning and relearning use short fixed steps before the card graduates to day intervals.
 */
class Fsrs(
    private val w: DoubleArray = DEFAULT_PARAMS,
    val desiredRetention: Double = 0.9,
    private val learningSteps: List<Long> = listOf(MINUTE, 10 * MINUTE),
    private val relearningSteps: List<Long> = listOf(10 * MINUTE),
    private val maximumIntervalDays: Int = 36500,
) {
    init {
        require(w.size == 19) { "FSRS-5 needs 19 parameters, got ${w.size}" }
        require(desiredRetention in 0.7..0.99) { "desiredRetention out of range: $desiredRetention" }
    }

    fun retrievability(state: MemoryState, now: Long): Double {
        if (state.phase == Phase.NEW || state.lastReview == null) return 0.0
        return retrievability(elapsedDays(state, now), state.stability)
    }

    /** Outcome of each possible rating, used both to apply a review and to label the buttons. */
    fun preview(state: MemoryState, now: Long): Map<Rating, MemoryState> {
        val result = Rating.entries.associateWith { next(state, it, now) }.toMutableMap()
        if (state.phase == Phase.REVIEW) enforceIntervalOrder(result, now)
        return result
    }

    fun review(state: MemoryState, rating: Rating, now: Long): MemoryState = preview(state, now).getValue(rating)

    private fun next(s: MemoryState, rating: Rating, now: Long): MemoryState = when (s.phase) {
        Phase.NEW -> {
            val started = s.copy(stability = initStability(rating), difficulty = initDifficulty(rating))
            stepOrGraduate(started, Phase.LEARNING, learningSteps, step = 0, rating, now)
        }
        Phase.LEARNING, Phase.RELEARNING -> {
            val updated = s.copy(
                stability = shortTermStability(s.stability, rating),
                difficulty = nextDifficulty(s.difficulty, rating),
            )
            val steps = if (s.phase == Phase.LEARNING) learningSteps else relearningSteps
            stepOrGraduate(updated, s.phase, steps, s.step, rating, now)
        }
        Phase.REVIEW -> {
            val elapsed = elapsedDays(s, now)
            val r = retrievability(elapsed, s.stability)
            val d = nextDifficulty(s.difficulty, rating)
            if (rating == Rating.AGAIN) {
                val lapsed = s.copy(stability = forgetStability(s.difficulty, s.stability, r), difficulty = d, lapses = s.lapses + 1)
                if (relearningSteps.isEmpty()) graduate(lapsed, now)
                else lapsed.copy(phase = Phase.RELEARNING, step = 0, due = now + relearningSteps[0]).touched(now)
            } else {
                val stability = if (elapsed < 1.0) shortTermStability(s.stability, rating)
                else recallStability(s.difficulty, s.stability, r, rating)
                graduate(s.copy(stability = stability, difficulty = d), now)
            }
        }
    }

    private fun stepOrGraduate(
        s: MemoryState, phase: Phase, steps: List<Long>, step: Int, rating: Rating, now: Long,
    ): MemoryState {
        if (steps.isEmpty()) return graduate(s, now)
        return when (rating) {
            Rating.AGAIN -> s.copy(phase = phase, step = 0, due = now + steps[0]).touched(now)
            Rating.HARD -> {
                // Anki convention: on the first step Hard waits halfway between step 1 and 2.
                val delay = if (step == 0 && steps.size > 1) (steps[0] + steps[1]) / 2
                else if (step == 0) steps[0] * 3 / 2 else steps[step]
                s.copy(phase = phase, step = step, due = now + delay).touched(now)
            }
            Rating.GOOD ->
                if (step + 1 < steps.size) s.copy(phase = phase, step = step + 1, due = now + steps[step + 1]).touched(now)
                else graduate(s, now)
            Rating.EASY -> graduate(s, now)
        }
    }

    private fun graduate(s: MemoryState, now: Long): MemoryState =
        s.copy(phase = Phase.REVIEW, step = 0, due = now + intervalDays(s.stability) * DAY).touched(now)

    private fun MemoryState.touched(now: Long) = copy(lastReview = now, reps = reps + 1)

    /** Hard <= Good < Easy, so the buttons never show a shorter interval for a better answer. */
    private fun enforceIntervalOrder(result: MutableMap<Rating, MemoryState>, now: Long) {
        fun days(r: Rating) = (result.getValue(r).due - now) / DAY
        fun setDays(r: Rating, d: Long) {
            result[r] = result.getValue(r).copy(due = now + d * DAY)
        }
        val good = days(Rating.GOOD)
        if (days(Rating.HARD) > good) setDays(Rating.HARD, good)
        val goodFixed = max(good, days(Rating.HARD) + 1)
        setDays(Rating.GOOD, goodFixed)
        setDays(Rating.EASY, max(days(Rating.EASY), goodFixed + 1))
    }

    fun intervalDays(stability: Double): Long {
        val raw = stability / FACTOR * (desiredRetention.pow(1.0 / DECAY) - 1.0)
        return raw.roundToLong().coerceIn(1L, maximumIntervalDays.toLong())
    }

    private fun elapsedDays(s: MemoryState, now: Long): Double =
        s.lastReview?.let { max(0.0, (now - it).toDouble() / DAY) } ?: 0.0

    private fun retrievability(elapsedDays: Double, stability: Double): Double =
        (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)

    private fun initStability(r: Rating) = max(w[r.value - 1], 0.1)

    private fun initDifficulty(r: Rating) = (w[4] - exp(w[5] * (r.value - 1)) + 1.0).coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, r: Rating): Double {
        val delta = -w[6] * (r.value - 3)
        val damped = d + delta * (10.0 - d) / 9.0
        return (w[7] * initDifficulty(Rating.EASY) + (1 - w[7]) * damped).coerceIn(1.0, 10.0)
    }

    private fun recallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) * hardPenalty * easyBonus)
    }

    private fun forgetStability(d: Double, s: Double, r: Double): Double {
        val longTerm = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r))
        return min(longTerm, s / exp(w[17] * w[18])).coerceAtLeast(0.1)
    }

    private fun shortTermStability(s: Double, rating: Rating): Double {
        val next = s * exp(w[17] * (rating.value - 3 + w[18]))
        // A successful same-day review should never lower stability.
        return (if (rating.value >= Rating.GOOD.value) max(next, s) else next).coerceAtLeast(0.1)
    }

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        private const val DECAY = -0.5
        private const val FACTOR = 19.0 / 81.0

        /** FSRS-5 default parameters, trained on the open Anki review dataset. */
        val DEFAULT_PARAMS = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
            1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
        )
    }
}
