package app.wortschatz.core

/**
 * Present tense of a verb for all six persons, built from the infinitive and the er/sie/es form
 * the word book already has, which carries any stem change (fahren → fährt, lesen → liest).
 * Modal verbs and the few truly irregular ones come from a table.
 */
object Conjugation {
    val persons = listOf("ich", "du", "er/sie/es", "wir", "ihr", "sie/Sie")

    private val irregular = mapOf(
        "sein" to "bin bist ist sind seid sind",
        "haben" to "habe hast hat haben habt haben",
        "werden" to "werde wirst wird werden werdet werden",
        "wissen" to "weiß weißt weiß wissen wisst wissen",
        "können" to "kann kannst kann können könnt können",
        "müssen" to "muss musst muss müssen müsst müssen",
        "dürfen" to "darf darfst darf dürfen dürft dürfen",
        "sollen" to "soll sollst soll sollen sollt sollen",
        "wollen" to "will willst will wollen wollt wollen",
        "mögen" to "mag magst mag mögen mögt mögen",
        "möchten" to "möchte möchtest möchte möchten möchtet möchten",
        "tun" to "tue tust tut tun tut tun",
    ).mapValues { it.value.split(' ') }

    /** The six forms in [persons] order, with a separable prefix at the end ("rufe an"); null when unknown. */
    fun present(entry: Entry): List<String>? {
        if (entry.pos != PartOfSpeech.VERB || ' ' in entry.lemma) return null
        val prefix = entry.separablePrefix
        val base = prefix?.let { entry.lemma.removePrefix(it) } ?: entry.lemma
        val forms = irregular[base] ?: regular(base, entry.verbForms?.present3?.substringBefore(' ')) ?: return null
        return if (prefix == null) forms else forms.map { "$it $prefix" }
    }

    private fun regular(inf: String, er: String?): List<String>? {
        if (er.isNullOrEmpty()) return null
        val stem = when {
            inf.endsWith("en") -> inf.dropLast(2)
            inf.endsWith("n") -> inf.dropLast(1)
            else -> return null
        }
        // arbeiten → arbeitet, öffnen → öffnet: an extra e before the ending.
        val linkingE = er == stem + "et"
        val ich = if (inf.endsWith("eln")) stem.dropLast(2) + "le" else stem + "e"
        val du = when {
            linkingE -> stem + "est"
            // halten → hält: the stem change swallowed the t ending, du hältst.
            stem.endsWith("t") && er.endsWith("t") && er != stem + "t" -> er + "st"
            else -> er.removeSuffix("t").let { s -> if (s.last() in "sßzx") s + "t" else s + "st" }
        }
        val ihr = if (linkingE) stem + "et" else stem + "t"
        return listOf(ich, du, er, inf, ihr, inf)
    }
}
