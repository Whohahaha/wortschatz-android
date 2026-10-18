package app.wortschatz.core

import app.wortschatz.core.fsrs.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GesturesTest {
    @Test
    fun presetsAreCompleteAndDistinct() {
        val presets = GestureSchemes.presets
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        presets.forEach { p ->
            assertEquals(Gesture.entries.toSet(), p.actions.keys, "${p.id} misses gestures")
            // Every preset must let the user both see the answer and answer the card.
            val actions = p.actions.values.toSet()
            assertTrue(GestureAction.REVEAL in actions || GestureAction.REVEAL_OR_GOOD in actions, p.id)
            assertTrue(actions.any { it.answers }, p.id)
        }
        assertEquals(GestureSchemes.ONE_HAND, GestureSchemes.DEFAULT)
    }

    @Test
    fun firstPrototypeBehaviourIsKeptAsAPreset() {
        val s = GestureSchemes.CARD_SWIPE
        assertEquals(GestureAction.GOOD, s.actionFor(Gesture.SWIPE_RIGHT))
        assertEquals(GestureAction.AGAIN, s.actionFor(Gesture.SWIPE_LEFT))
        assertEquals(GestureAction.EASY, s.actionFor(Gesture.SWIPE_UP))
        assertEquals(GestureAction.NONE, s.actionFor(Gesture.SWIPE_DOWN))
        assertEquals(GestureAction.NONE, s.actionFor(Gesture.VOLUME_UP))
    }

    @Test
    fun customSchemeRoundTripsAndIgnoresGarbage() {
        val edited = GestureSchemes.TAP_ZONES.with(Gesture.SWIPE_DOWN, GestureAction.SPEAK)
        val restored = GestureSchemes.custom(edited.encode())
        assertEquals(edited.actions, restored.actions)
        assertEquals(GestureSchemes.CUSTOM_ID, restored.id)

        val partial = GestureSchemes.custom("SWIPE_UP=UNDO;NOPE=GOOD;SWIPE_LEFT=???;junk")
        assertEquals(GestureAction.UNDO, partial.actionFor(Gesture.SWIPE_UP))
        assertEquals(GestureSchemes.DEFAULT.actionFor(Gesture.SWIPE_LEFT), partial.actionFor(Gesture.SWIPE_LEFT))
        assertEquals(GestureSchemes.DEFAULT.actions, GestureSchemes.custom(null).actions)
    }

    @Test
    fun revealOrAnswerActsInTwoSteps() {
        assertEquals(Command.Reveal, resolve(GestureAction.REVEAL_OR_GOOD, CardType.MEANING, revealed = false))
        assertEquals(Command.Rate(Rating.GOOD), resolve(GestureAction.REVEAL_OR_GOOD, CardType.MEANING, revealed = true))
        assertEquals(Command.Rate(Rating.AGAIN), resolve(GestureAction.REVEAL_OR_AGAIN, CardType.PLURAL, revealed = true))
        assertEquals(Command.Nothing, resolve(GestureAction.REVEAL, CardType.MEANING, revealed = true))
        // Direct ratings work before the answer is shown, which is what makes swiping fast.
        assertEquals(Command.Rate(Rating.EASY), resolve(GestureAction.EASY, CardType.VERB_FORMS, revealed = false))
    }

    @Test
    fun articleCardsCannotBeRatedOrHeardBeforeAnswering() {
        GestureAction.entries.filter { it.answers || it == GestureAction.REVEAL }.forEach {
            assertEquals(Command.Nothing, resolve(it, CardType.ARTICLE, revealed = false), it.name)
        }
        assertEquals(Command.Nothing, resolve(GestureAction.SPEAK, CardType.ARTICLE, revealed = false))
        assertEquals(Command.Speak, resolve(GestureAction.SPEAK, CardType.ARTICLE, revealed = true))
        assertEquals(Command.Undo, resolve(GestureAction.UNDO, CardType.ARTICLE, revealed = false))
    }
}
