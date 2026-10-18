package app.wortschatz.core

/**
 * Word books are tab-separated text with a header row, so they can be edited in any
 * spreadsheet. Lines starting with '#' are comments. Columns:
 *
 * id, level, pos (n/v/adj/adv/other), lemma, gender (der/die/das), plural, meaning,
 * present3, preterite, participle, aux, example, example_zh
 *
 * A separable verb marks its prefix with '|' in the lemma: "an|rufen".
 */
object WordBook {
    private val required = listOf("id", "level", "pos", "lemma", "meaning")

    fun parse(text: String): List<Entry> {
        val lines = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return emptyList()

        val header = lines.first().split('\t').map { it.trim() }
        val missing = required - header.toSet()
        require(missing.isEmpty()) { "Word book header is missing columns: $missing" }
        val index = header.withIndex().associate { (i, name) -> name to i }

        val seen = HashSet<String>()
        return lines.drop(1).mapIndexed { row, line ->
            val cells = line.split('\t')
            fun col(name: String): String = index[name]?.let { cells.getOrNull(it)?.trim() }.orEmpty()

            val id = col("id")
            require(id.isNotEmpty()) { "Row ${row + 2}: empty id" }
            require(seen.add(id)) { "Row ${row + 2}: duplicate id $id" }

            val rawLemma = col("lemma")
            val prefix = rawLemma.substringBefore('|', missingDelimiterValue = "").ifEmpty { null }
            val lemma = rawLemma.replace("|", "")
            val pos = PartOfSpeech.parse(col("pos"))
            val gender = Gender.parse(col("gender"))
            require(pos != PartOfSpeech.NOUN || gender != null) { "Row ${row + 2}: noun $lemma has no gender" }

            val verbForms = if (col("participle").isNotEmpty()) {
                VerbForms(col("present3"), col("preterite"), col("participle"), col("aux"))
            } else null

            Entry(
                id = id,
                level = col("level"),
                pos = pos,
                lemma = lemma,
                gender = gender,
                plural = col("plural").ifEmpty { null },
                meaning = col("meaning"),
                verbForms = verbForms,
                separablePrefix = prefix,
                example = col("example"),
                exampleZh = col("example_zh"),
            )
        }
    }
}
