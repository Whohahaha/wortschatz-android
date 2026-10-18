package app.wortschatz.core.imports

import app.wortschatz.core.Gender
import app.wortschatz.core.VerbForms

/** What could be read out of a single "German" cell. */
data class Headword(
    val lemma: String,
    val gender: Gender? = null,
    /** Fully spelled plural ("Tische"), already expanded from notations like "-e". */
    val plural: String? = null,
    val verbForms: VerbForms? = null,
    val separablePrefix: String? = null,
)

/**
 * Reads the many ways word lists write a German headword:
 *
 *  der Tisch · der Tisch, -e · der Tisch, die Tische · Tisch, der · Tisch (m) · Tisch m ·
 *  r Tisch (Duden style r/e/s) · der Stuhl, ¨-e · an|rufen · fahren (fährt, fuhr, ist gefahren)
 */
object GermanParsing {
    private val articles = mapOf(
        "der" to Gender.DER, "die" to Gender.DIE, "das" to Gender.DAS,
        "r" to Gender.DER, "e" to Gender.DIE, "s" to Gender.DAS,
        "m" to Gender.DER, "f" to Gender.DIE, "n" to Gender.DAS,
        "m." to Gender.DER, "f." to Gender.DIE, "n." to Gender.DAS,
    )
    private val separablePrefixes = listOf(
        "zurück", "zusammen", "weiter", "vorbei", "heraus", "herein", "hinaus", "hinein",
        "fern", "fest", "frei", "fort", "nach", "auf", "aus", "ein", "mit", "vor", "weg", "zu",
        "ab", "an", "bei", "her", "hin", "los", "um",
    )

    fun parse(raw: String): Headword {
        var text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return Headword("")

        // "fahren (fährt, fuhr, ist gefahren)" / "fahren - fährt - fuhr - ist gefahren"
        verbWithForms(text)?.let { return it }

        var gender: Gender? = null
        var plural: String? = null

        // Leading article: "der Tisch", "r Tisch"
        val first = text.substringBefore(' ')
        if (text.contains(' ') && articles[first.lowercase()] != null && first.length <= 3 && !first.endsWith(".")) {
            gender = articles.getValue(first.lowercase())
            text = text.substringAfter(' ')
        }
        // Trailing parts after a comma: "Tisch, der" / "Tisch, -e" / "Tisch, die Tische"
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        text = parts.firstOrNull().orEmpty()
        for (part in parts.drop(1)) {
            val lower = part.lowercase()
            when {
                gender == null && articles[lower] != null -> gender = articles.getValue(lower)
                lower.startsWith("die ") || lower.startsWith("pl. ") || lower.startsWith("pl ") ->
                    plural = expandPlural(text, part.substringAfter(' '))
                part.startsWith("-") || part.startsWith("¨") || part == "–" -> plural = expandPlural(text, part)
            }
        }
        // Trailing gender marker: "Tisch (m)", "Tisch m", "Tisch (der)"
        Regex("^(.+?)(?:\\s+|\\s*\\()(der|die|das|m|f|n)\\.?\\)?$").find(text)?.let { m ->
            val (word, marker) = m.destructured
            if (gender == null && word.isNotEmpty() && word.first().isUpperCase()) {
                gender = articles[marker.lowercase()]
                text = word.trim()
            }
        }
        // Plural in brackets: "Tisch (-e)", "Tisch (Tische)"
        Regex("^(.+?)\\s*\\((.+)\\)$").find(text)?.let { m ->
            val (word, inner) = m.destructured
            val notation = inner.trim().removePrefix("Pl.").removePrefix("pl.").trim()
            // Only plural notations or the plural itself; "Bank (Geldinstitut)" is a gloss, not a plural.
            val looksPlural = notation.startsWith("-") || notation.startsWith("¨") ||
                notation.startsWith("die ") || notation.take(3) == word.trim().take(3)
            if (plural == null && looksPlural) {
                plural = expandPlural(word.trim(), notation)
                text = word.trim()
            }
        }

        val prefix = if (text.contains('|')) text.substringBefore('|') else null
        val lemma = text.replace("|", "")
        return Headword(lemma, gender, plural?.takeIf { it.isNotEmpty() }, separablePrefix = prefix)
    }

    /**
     * "-e" → Tische, "¨-e" / "-¨e" → Stühle, "-" or "–" → unchanged, "Tische" → Tische.
     * Returns "" for "no plural" markers such as "o. Pl.".
     */
    fun expandPlural(lemma: String, notation: String): String {
        val n = notation.trim().removePrefix("die ").trim()
        if (n.isEmpty() || n.lowercase().let { it.startsWith("o.") || it.startsWith("kein") || it == "sg." || it == "nur sg." }) return ""
        if (n == "-" || n == "–" || n == "—") return lemma
        val umlaut = n.contains('¨') || n.contains('̈')
        val suffix = n.replace("¨", "").replace("̈", "").removePrefix("-").removePrefix("–").trim()
        val isNotation = n.startsWith("-") || n.startsWith("–") || n.startsWith("¨")
        if (!isNotation) return n
        val base = if (umlaut) umlautLastVowel(lemma) else lemma
        return base + suffix
    }

    /** Stuhl → Stühl, Haus → Häus, Apfel → Äpfel (the vowel of the stem, not of -el/-er/-en). */
    fun umlautLastVowel(word: String): String {
        val stemEnd = Regex("(el|er|en)$").find(word)?.range?.first ?: word.length
        val stem = word.substring(0, stemEnd)
        val idx = stem.indexOfLast { it.lowercaseChar() in "aou" }
        if (idx < 0) return word
        // In "au" the umlaut goes on the a: Haus → Häuser
        val target = if (stem[idx].lowercaseChar() == 'u' && idx > 0 && stem[idx - 1].lowercaseChar() == 'a') idx - 1 else idx
        return word.substring(0, target) + umlaut(word[target]) + word.substring(target + 1)
    }

    private fun umlaut(c: Char): Char = when (c) {
        'a' -> 'ä'; 'o' -> 'ö'; 'u' -> 'ü'; 'A' -> 'Ä'; 'O' -> 'Ö'; 'U' -> 'Ü'; else -> c
    }

    private fun verbWithForms(text: String): Headword? {
        val m = Regex("^([a-zäöüß|]+(?:en|ern|eln|n))\\s*[(:\\-–]\\s*(.+?)\\)?$").find(text) ?: return null
        val (infinitive, rest) = m.destructured
        val forms = rest.split(',', '-', '–', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (forms.size < 3) return null
        val perfect = forms[2]
        val aux = when {
            perfect.startsWith("ist ") -> "sein"
            perfect.startsWith("hat ") -> "haben"
            else -> ""
        }
        val participle = perfect.removePrefix("ist ").removePrefix("hat ").trim()
        val prefix = if (infinitive.contains('|')) infinitive.substringBefore('|') else null
        return Headword(
            lemma = infinitive.replace("|", ""),
            verbForms = VerbForms(forms[0].removePrefix("er ").removePrefix("sie ").removePrefix("es "), forms[1], participle, aux),
            separablePrefix = prefix,
        )
    }

    /** Separable prefix of an infinitive, judged from its present tense ("ruft an" → "an"). */
    fun separablePrefixFrom(lemma: String, present3: String): String? {
        val tail = present3.substringAfterLast(' ', "")
        return tail.takeIf { it.isNotEmpty() && it in separablePrefixes && lemma.startsWith(it) }
    }
}
