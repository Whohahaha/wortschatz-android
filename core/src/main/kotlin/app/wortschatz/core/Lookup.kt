package app.wortschatz.core

import app.wortschatz.core.imports.Lexicon

/** What is known about a word tapped in an example sentence. */
data class LookupResult(
    val word: String,
    /** The word book's entry, with its Chinese meaning, when the word is in the book. */
    val entry: Entry? = null,
    val nounLemma: String? = null,
    val noun: Pair<Gender, String>? = null,
    val verbLemma: String? = null,
    val verb: VerbForms? = null,
    /** The word is a noun used only in the plural (Eltern), so no singular is shown. */
    val pluralOnly: Boolean = false,
) {
    val found: Boolean get() = entry != null || noun != null || verb != null || pluralOnly
}

/** Offline lookup for words in example sentences: the current word book first, then the lexicon. */
class Lookup(entries: List<Entry>, private val lexicon: Lexicon) {
    private val byForm: Map<String, Entry> = HashMap<String, Entry>().also { m ->
        for (e in entries) {
            val forms = buildList {
                add(e.lemma)
                e.plural?.let(::add)
                e.verbForms?.let { v ->
                    add(v.present3.substringBefore(' '))
                    add(v.preterite.substringBefore(' '))
                    add(v.participle)
                }
            }
            forms.filter { it.isNotEmpty() }.forEach { m.putIfAbsent(it.lowercase(), e) }
        }
    }

    fun find(word: String): LookupResult {
        val w = word.trim()
        val direct = byForm[w.lowercase()]
        val capitalized = w.replaceFirstChar { it.uppercase() }
        // "Eltern" must not turn into the dictionary's "das Elter".
        val pluralOnly = direct?.pluralOnly == true || capitalized in PLURAL_ONLY_NOUNS
        val nounLemma = if (pluralOnly) null else lexicon.nounLemma(w) ?: lexicon.nounLemma(capitalized)
        val verbLemma = lexicon.verbLemma(w)
        val entry = direct
            ?: nounLemma?.let { byForm[it.lowercase()] }
            ?: verbLemma?.let { byForm[it] }
        // The book's own noun wins over a different dictionary noun spelled the same way.
        val noun = nounLemma?.takeIf { entry?.pos != PartOfSpeech.NOUN || it == entry.lemma }
        return LookupResult(
            word = w,
            entry = entry,
            nounLemma = noun,
            noun = noun?.let(lexicon::noun),
            verbLemma = verbLemma,
            verb = verbLemma?.let(lexicon::verb),
            pluralOnly = pluralOnly,
        )
    }

    companion object {
        private val wordPattern = Regex("\\p{L}+")

        /** The word in [text] at character [index], if the index falls on one. */
        fun wordAt(text: String, index: Int): String? =
            wordPattern.findAll(text).firstOrNull { index in it.range }?.value
    }
}
