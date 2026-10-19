package app.wortschatz.core

import app.wortschatz.core.imports.Lexicon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypingTest {
    private val tisch = Entry("tisch", "A1", PartOfSpeech.NOUN, "Tisch", Gender.DER, "Tische", "桌子", example = "Der Tisch ist neu.")
    private val anrufen = Entry(
        "anrufen", "A1", PartOfSpeech.VERB, "anrufen", meaning = "打电话",
        verbForms = VerbForms("ruft an", "rief an", "angerufen", "haben"), separablePrefix = "an",
        example = "Ich rufe dich heute Abend an.",
    )
    private val kaufen = Entry(
        "kaufen", "A1", PartOfSpeech.VERB, "kaufen", meaning = "买",
        verbForms = VerbForms("kauft", "kaufte", "gekauft", "haben"), example = "Wir kaufen heute Brot.",
    )

    @Test
    fun gradesExactCloseAndWrong() {
        assertEquals(Verdict.CORRECT, Typing.grade(" Tisch ", "Tisch"))
        assertEquals(Verdict.CLOSE, Typing.grade("tisch", "Tisch"))
        assertEquals(Verdict.CLOSE, Typing.grade("Muetze", "Mütze"))
        assertEquals(Verdict.CLOSE, Typing.grade("Strase", "Straße"))
        assertEquals(Verdict.WRONG, Typing.grade("Stuhl", "Tisch"))
        assertEquals(Verdict.WRONG, Typing.grade("", "Tisch"))
        assertEquals(Verdict.CORRECT, Typing.gradeSpelling("der Tisch", tisch))
        assertEquals(Verdict.CORRECT, Typing.gradeSpelling("anrufen", anrufen))
    }

    @Test
    fun onlyPlainWordsAreSpellable() {
        assertTrue(Typing.spellable(tisch))
        assertTrue(Typing.spellable(tisch.copy(lemma = "E-Mail")))
        assertFalse(Typing.spellable(tisch.copy(lemma = "der, die, das")))
        assertFalse(Typing.spellable(tisch.copy(lemma = "ihm / ihn")))
    }

    @Test
    fun clozeBlanksTheWordIncludingSplitVerbs() {
        val split = Cloze.of(anrufen)!!
        assertEquals("Ich ____ dich heute Abend ____.", split.prompt)
        assertEquals("rufe an", split.answer)
        assertEquals("Der ____ ist neu.", Cloze.of(tisch)!!.prompt)
        assertEquals("kaufen", Cloze.of(kaufen)!!.answer)
        assertEquals("kaufte", Cloze.of(kaufen.copy(example = "Ich kaufte ein Buch."))!!.answer)
        assertNull(Cloze.of(tisch.copy(example = "Das ist neu.")))
        val wo = Cloze.of(tisch.copy(lemma = "wo", gender = null, plural = null, example = "Wo wohnst du?"))!!
        assertEquals(Verdict.CORRECT, wo.grade("wo"))
        assertEquals(Verdict.CLOSE, Cloze.of(tisch)!!.grade("tisch"))
        assertNull(Cloze.of(tisch.copy(example = "")))
        assertTrue(CardType.CLOZE in cardTypesFor(tisch))
    }

    @Test
    fun numbersAreWrittenAsSpoken() {
        assertEquals("null", GermanNumbers.words(0))
        assertEquals("eins", GermanNumbers.words(1))
        assertEquals("sechzehn", GermanNumbers.words(16))
        assertEquals("einundzwanzig", GermanNumbers.words(21))
        assertEquals("dreiundachtzig", GermanNumbers.words(83))
        assertEquals("einhundert", GermanNumbers.words(100))
        assertEquals("siebenhundertdreiundachtzig", GermanNumbers.words(783))
        assertEquals("eintausendeins", GermanNumbers.words(1001))
        assertEquals("zweitausendvierundzwanzig", GermanNumbers.words(2024))
        assertEquals("neunzehnhundertvierundachtzig", GermanNumbers.year(1984))
        assertEquals("zweitausendfünf", GermanNumbers.year(2005))
    }

    @Test
    fun lookupFindsInflectedFormsOffline() {
        val lexicon = Lexicon.parse(
            sequenceOf(
                "Kind\tn\tdas\tKinder\t\t\t\t",
                "fahren\tv\t\t\tfährt\tfuhr\tgefahren\tsein",
                "kaufen\tv\t\t\tkauft\tkaufte\tgekauft\thaben",
            )
        )
        val lookup = Lookup(listOf(tisch, kaufen), lexicon)
        assertEquals("Kind", lookup.find("Kindern".removeSuffix("n")).nounLemma)
        assertEquals("fahren", lookup.find("fuhr").verbLemma)
        assertEquals("fahren", lookup.find("fahre").verbLemma)
        assertEquals(kaufen, lookup.find("kaufst").entry)
        assertEquals(tisch, lookup.find("Tische").entry)
        assertFalse(lookup.find("xyz").found)
        assertEquals("Abend", Lookup.wordAt("Ich rufe dich heute Abend an.", 22))
    }
}
