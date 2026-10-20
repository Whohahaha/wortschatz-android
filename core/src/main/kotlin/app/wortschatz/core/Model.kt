package app.wortschatz.core

import app.wortschatz.core.fsrs.MemoryState
import app.wortschatz.core.fsrs.Rating

enum class Gender(val article: String) {
    DER("der"), DIE("die"), DAS("das");

    companion object {
        fun parse(text: String): Gender? = entries.firstOrNull { it.article == text.trim().lowercase() }
    }
}

enum class PartOfSpeech(val code: String, val label: String) {
    NOUN("n", "名词"), VERB("v", "动词"), ADJECTIVE("adj", "形容词"), ADVERB("adv", "副词"), OTHER("other", "其他");

    companion object {
        fun parse(code: String): PartOfSpeech = entries.firstOrNull { it.code == code.trim() } ?: OTHER
    }
}

/** Principal parts of a verb: er/sie/es present tense, Präteritum, Partizip II, perfect auxiliary. */
data class VerbForms(
    val present3: String,
    val preterite: String,
    val participle: String,
    val auxiliary: String,
)

/** One word in a word book. A single entry yields several independently scheduled cards. */
data class Entry(
    val id: String,
    val level: String,
    val pos: PartOfSpeech,
    val lemma: String,
    val gender: Gender? = null,
    /** Full plural form ("Tische"); null when not applicable or the noun has no plural. */
    val plural: String? = null,
    val meaning: String,
    val verbForms: VerbForms? = null,
    /** Separable prefix of a verb such as "an" in anrufen. */
    val separablePrefix: String? = null,
    val example: String = "",
    val exampleZh: String = "",
    /**
     * A noun used only in the plural (die Eltern, die Leute). Its [gender] is DIE, the plural
     * article, but there is no singular: no article question and no plural question.
     */
    val pluralOnly: Boolean = false,
) {
    /** "der Tisch", "an·rufen" */
    val display: String
        get() {
            val word = separablePrefix?.let { "$it·${lemma.removePrefix(it)}" } ?: lemma
            return gender?.let { "${it.article} $word" } ?: word
        }

    /** What text-to-speech should read: nouns always together with their article. */
    val spoken: String get() = gender?.let { "${it.article} $lemma" } ?: lemma

    /** The plural is spelled like the singular: der Lehrer, die Lehrer. */
    val pluralSameAsSingular: Boolean get() = plural == lemma

    /** "复数：die Tische", "复数：die Lehrer（单复同形）", "只有复数", "无复数"; null if not a noun. */
    val pluralLabel: String?
        get() = when {
            pos != PartOfSpeech.NOUN -> null
            pluralOnly -> "只有复数形式"
            plural.isNullOrEmpty() -> "无复数"
            pluralSameAsSingular -> "复数：die $plural（单复同形）"
            else -> "复数：die $plural"
        }
}

/**
 * Everyday nouns used only in the plural. Dictionaries often list a technical singular
 * ("das Elter") that a learner should never be shown or asked about.
 */
val PLURAL_ONLY_NOUNS: Set<String> = setOf(
    "Abgase", "Alpen", "Daten", "Eltern", "Fachleute", "Ferien", "Finanzen", "Fundsachen", "Geschwister",
    "Großeltern", "Kenntnisse", "Kosten", "Lebensmittel", "Leute", "Medien", "Möbel", "Personalien",
    "Pommes", "Schulden", "Senioren", "Shorts", "Spaghetti", "Süßigkeiten", "Trümmer", "Unterlagen",
    "Urlaubstage", "Zinsen", "Zutaten",
)

enum class CardType(val label: String) {
    MEANING("释义"), ARTICLE("冠词"), PLURAL("复数"), VERB_FORMS("变位"), SPELLING("拼写"), CLOZE("填空");

    /** Cards whose answer can be checked by the app instead of by self-rating. */
    val isObjective: Boolean get() = this == ARTICLE || isTyped

    /** Cards answered by typing the German word. */
    val isTyped: Boolean get() = this == SPELLING || this == CLOZE
}

fun cardTypesFor(entry: Entry): List<CardType> = buildList {
    add(CardType.MEANING)
    if (entry.gender != null && !entry.pluralOnly) add(CardType.ARTICLE)
    if (entry.pos == PartOfSpeech.NOUN && !entry.pluralOnly && !entry.plural.isNullOrEmpty()) add(CardType.PLURAL)
    if (entry.verbForms != null) add(CardType.VERB_FORMS)
    if (Typing.spellable(entry)) add(CardType.SPELLING)
    if (Cloze.of(entry) != null) add(CardType.CLOZE)
}

data class Card(
    val entryId: String,
    val type: CardType,
    val memory: MemoryState = MemoryState(),
) {
    val id: String get() = cardId(entryId, type)

    companion object {
        fun cardId(entryId: String, type: CardType) = "$entryId/${type.name}"
    }
}

/**
 * Append-only record of one answer. [before] is the card's state prior to the answer,
 * which makes undo exact and lets the full history be replayed under a new algorithm.
 */
data class ReviewLog(
    val id: Long = 0,
    val cardId: String,
    val timestamp: Long,
    val rating: Rating,
    val durationMs: Long,
    val before: MemoryState,
)
