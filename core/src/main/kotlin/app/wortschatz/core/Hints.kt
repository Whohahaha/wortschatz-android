package app.wortschatz.core

/** A rule of thumb for a noun's article, and whether this noun follows it. */
data class GenderHint(val rule: String, val follows: Boolean)

/**
 * Small helpers that make German grammar visible on the cards: article rules of thumb by
 * ending, and where a separable verb sits in its example sentence once it is split.
 */
object Hints {
    // Endings, longest first, with the article they (almost always) go with.
    private val endings = listOf(
        "schaft" to Gender.DIE, "ismus" to Gender.DER, "chen" to Gender.DAS, "lein" to Gender.DAS,
        "ment" to Gender.DAS, "ling" to Gender.DER, "heit" to Gender.DIE, "keit" to Gender.DIE,
        "ung" to Gender.DIE, "ion" to Gender.DIE, "tät" to Gender.DIE, "enz" to Gender.DIE,
        "anz" to Gender.DIE, "ik" to Gender.DIE, "ei" to Gender.DIE, "um" to Gender.DAS,
        "or" to Gender.DER, "ig" to Gender.DER, "ist" to Gender.DER,
    )

    fun gender(entry: Entry): GenderHint? {
        val gender = entry.gender ?: return null
        val word = entry.lemma.lowercase()
        endings.firstOrNull { (ending, _) -> word.endsWith(ending) && word.length > ending.length + 1 }?.let { (ending, rule) ->
            return GenderHint("以 -$ending 结尾的名词一般是 ${rule.article}", rule == gender)
        }
        if (word.endsWith("e") && word.length > 3) {
            return GenderHint("以 -e 结尾的名词大多是 die", gender == Gender.DIE)
        }
        return null
    }

    private val wordPattern = Regex("\\p{L}+")

    /**
     * Character ranges in [example] to highlight for a separable verb: the conjugated stem and
     * its split-off prefix ("Ich **rufe** dich heute Abend **an**."), or the whole word when
     * the verb is not split ("Kannst du mich **anrufen**?").
     */
    fun separableParts(example: String, entry: Entry): List<IntRange> {
        val prefix = entry.separablePrefix ?: return emptyList()
        val stem = entry.lemma.removePrefix(prefix)
        val root = plain(stem).removeSuffix("en").removeSuffix("n").removeSuffix("e")
        if (root.length < 2) return emptyList()
        val forms = listOfNotNull(entry.verbForms?.present3, entry.verbForms?.preterite)
            .map { plain(it.substringBefore(' ')) }.filter { it.isNotEmpty() }.toSet()
        val words = wordPattern.findAll(example).toList()

        fun isVerb(w: String) = plain(w).let { it.startsWith(root.take(maxOf(3, root.length - 1))) || it in forms }

        val detached = words.indexOfLast { it.value.lowercase() == prefix }
        if (detached > 0) {
            val verb = words.subList(0, detached).indexOfFirst { isVerb(it.value) }
            if (verb >= 0) return listOf(words[verb].range, words[detached].range)
        }
        val participle = entry.verbForms?.participle?.let(::plain)
        return words.filter {
            val w = plain(it.value)
            w == participle || (w.startsWith(prefix) && w.contains(root))
        }.map { it.range }
    }

    private fun plain(s: String) = s.lowercase().replace('ä', 'a').replace('ö', 'o').replace('ü', 'u')
}
