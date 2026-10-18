package app.wortschatz.core.imports

import app.wortschatz.core.Entry
import app.wortschatz.core.Gender
import app.wortschatz.core.PartOfSpeech
import app.wortschatz.core.VerbForms

/** What a column of an imported table holds. */
enum class ColumnRole(val label: String) {
    GERMAN("德语"),
    MEANING("释义"),
    GENDER("冠词 / 词性"),
    PLURAL("复数"),
    EXAMPLE("例句"),
    EXAMPLE_ZH("例句翻译"),
    LEVEL("等级"),
}

/** Column names seen in Anki decks, Quizlet / Excel exports and dictionary apps. */
object HeaderNames {
    private val names = mapOf(
        ColumnRole.GERMAN to listOf("german", "deutsch", "wort", "word", "front", "term", "lemma", "vokabel", "德语", "单词", "词汇", "正面", "de"),
        ColumnRole.MEANING to listOf("meaning", "chinese", "english", "translation", "definition", "back", "bedeutung", "übersetzung", "释义", "中文", "意思", "翻译", "解释", "背面", "zh", "en"),
        ColumnRole.GENDER to listOf("gender", "article", "artikel", "genus", "冠词", "词性"),
        ColumnRole.PLURAL to listOf("plural", "plural form", "pl", "复数"),
        ColumnRole.EXAMPLE to listOf("example", "sentence", "beispiel", "beispielsatz", "satz", "例句"),
        ColumnRole.EXAMPLE_ZH to listOf("example translation", "sentence translation", "例句翻译", "例句中文", "例句释义"),
        ColumnRole.LEVEL to listOf("level", "niveau", "cefr", "等级", "级别"),
    )
    val all: Set<String> = names.values.flatten().toSet()

    fun roleOf(header: String): ColumnRole? {
        val h = header.trim().lowercase()
        return names.entries.firstOrNull { (_, list) -> h in list }?.key
    }
}

/** Grammar facts for German lemmas, used to fill in what an imported list leaves out. */
class Lexicon(private val nouns: Map<String, Pair<Gender, String>>, private val verbs: Map<String, VerbForms>) {
    fun noun(lemma: String): Pair<Gender, String>? = nouns[lemma]
    fun verb(lemma: String): VerbForms? = verbs[lemma]
    val size: Int get() = nouns.size + verbs.size

    companion object {
        val EMPTY = Lexicon(emptyMap(), emptyMap())

        /** Lines: lemma, pos (n/v), gender, plural, present3, preterite, participle, auxiliary. */
        fun parse(lines: Sequence<String>): Lexicon {
            val nouns = HashMap<String, Pair<Gender, String>>()
            val verbs = HashMap<String, VerbForms>()
            for (line in lines) {
                if (line.startsWith("#") || line.isBlank()) continue
                val c = line.split('\t').let { it + List((8 - it.size).coerceAtLeast(0)) { "" } }
                when (c[1]) {
                    "n" -> Gender.parse(c[2])?.let { nouns.putIfAbsent(c[0], it to c[3]) }
                    "v" -> verbs.putIfAbsent(c[0], VerbForms(c[4], c[5], c[6], c[7]))
                }
            }
            return Lexicon(nouns, verbs)
        }
    }
}

data class ImportOptions(
    val mapping: Map<ColumnRole, Int>,
    val autoComplete: Boolean = true,
    val level: String = "",
)

data class ImportResult(
    val entries: List<Entry>,
    /** Rows skipped because the same word (lemma + article) was already there. */
    val duplicates: Int,
    /** Rows without a German word or a meaning. */
    val skipped: Int,
    /** Entries where article, plural or verb forms came from the lexicon. */
    val completed: Int,
)

object Importer {
    private val cjk = Regex("[\\u4e00-\\u9fff]")
    private val latin = Regex("[A-Za-zÄÖÜäöüß]")

    /** Picks a column per role from the header names, then from what the cells look like. */
    fun guessMapping(table: Table): Map<ColumnRole, Int> {
        val mapping = LinkedHashMap<ColumnRole, Int>()
        table.headers.forEachIndexed { i, h ->
            HeaderNames.roleOf(h)?.let { role -> mapping.putIfAbsent(role, i) }
        }
        val sample = table.rows.take(50)
        fun share(column: Int, test: (String) -> Boolean): Double =
            if (sample.isEmpty()) 0.0 else sample.count { test(table.cell(it, column)) }.toDouble() / sample.size
        val free = { (0 until table.columnCount).filter { it !in mapping.values } }

        if (ColumnRole.MEANING !in mapping) {
            free().maxByOrNull { share(it) { c -> cjk.containsMatchIn(c) } }
                ?.takeIf { share(it) { c -> cjk.containsMatchIn(c) } > 0.5 }
                ?.let { mapping[ColumnRole.MEANING] = it }
        }
        if (ColumnRole.GERMAN !in mapping) {
            // Shortest mostly-Latin column: headwords are shorter than example sentences.
            free().filter { share(it) { c -> latin.containsMatchIn(c) && !cjk.containsMatchIn(c) } > 0.5 }
                .minByOrNull { col -> sample.sumOf { table.cell(it, col).length } }
                ?.let { mapping[ColumnRole.GERMAN] = it }
        }
        if (ColumnRole.MEANING !in mapping) {
            free().firstOrNull { col -> share(col) { it.isNotBlank() } > 0.5 }?.let { mapping[ColumnRole.MEANING] = it }
        }
        if (ColumnRole.EXAMPLE !in mapping) {
            free().firstOrNull { col -> share(col) { it.count { c -> c == ' ' } >= 3 && latin.containsMatchIn(it) } > 0.5 }
                ?.let { mapping[ColumnRole.EXAMPLE] = it }
        }
        return mapping
    }

    fun build(
        table: Table,
        options: ImportOptions,
        bookId: String,
        lexicon: Lexicon = Lexicon.EMPTY,
        existingKeys: Set<String> = emptySet(),
    ): ImportResult {
        val m = options.mapping
        val seen = HashSet(existingKeys)
        val entries = ArrayList<Entry>()
        var duplicates = 0
        var skipped = 0
        var completed = 0

        for (row in table.rows) {
            val germanCell = AnkiText.clean(table.cell(row, m[ColumnRole.GERMAN]))
            val meaning = AnkiText.clean(table.cell(row, m[ColumnRole.MEANING]))
            if (germanCell.isEmpty() || meaning.isEmpty()) {
                skipped++
                continue
            }
            val head = GermanParsing.parse(germanCell)
            var gender = head.gender ?: Gender.parse(AnkiText.clean(table.cell(row, m[ColumnRole.GENDER])).let { genderWord(it) })
            var plural = head.plural ?: AnkiText.clean(table.cell(row, m[ColumnRole.PLURAL])).takeIf { it.isNotEmpty() }
                ?.let { GermanParsing.expandPlural(head.lemma, it) }?.takeIf { it.isNotEmpty() }
            var verbForms = head.verbForms
            var prefix = head.separablePrefix
            var filled = false

            if (options.autoComplete) {
                val noun = if (head.lemma.firstOrNull()?.isUpperCase() == true) lexicon.noun(head.lemma) else null
                if (noun != null) {
                    if (gender == null) { gender = noun.first; filled = true }
                    if (plural == null && noun.second.isNotEmpty()) { plural = noun.second; filled = true }
                }
                val verb = if (head.lemma.firstOrNull()?.isLowerCase() == true) lexicon.verb(head.lemma) else null
                if (verb != null && verbForms == null) {
                    verbForms = verb
                    filled = true
                }
            }
            if (prefix == null && verbForms != null) prefix = GermanParsing.separablePrefixFrom(head.lemma, verbForms.present3)

            val key = dedupeKey(head.lemma, gender)
            if (!seen.add(key)) {
                duplicates++
                continue
            }
            if (filled) completed++
            val pos = when {
                gender != null -> PartOfSpeech.NOUN
                verbForms != null -> PartOfSpeech.VERB
                else -> PartOfSpeech.OTHER
            }
            entries += Entry(
                id = "$bookId~${entries.size + 1}",
                level = AnkiText.clean(table.cell(row, m[ColumnRole.LEVEL])).ifEmpty { options.level },
                pos = pos,
                lemma = head.lemma,
                gender = gender,
                plural = if (pos == PartOfSpeech.NOUN) plural else null,
                meaning = meaning,
                verbForms = verbForms,
                separablePrefix = prefix,
                example = AnkiText.clean(table.cell(row, m[ColumnRole.EXAMPLE])),
                exampleZh = AnkiText.clean(table.cell(row, m[ColumnRole.EXAMPLE_ZH])),
            )
        }
        return ImportResult(entries, duplicates, skipped, completed)
    }

    fun dedupeKey(lemma: String, gender: Gender?) = "${lemma.lowercase()}|${gender?.article.orEmpty()}"

    fun dedupeKey(entry: Entry) = dedupeKey(entry.lemma, entry.gender)

    /** "m", "maskulin", "der", "Maskulinum" → der … */
    private fun genderWord(text: String): String {
        val t = text.trim().lowercase()
        return when {
            t in setOf("m", "m.", "mask.", "masc", "maskulin", "maskulinum", "masculine", "阳性", "阳") -> "der"
            t in setOf("f", "f.", "fem.", "fem", "feminin", "femininum", "feminine", "阴性", "阴") -> "die"
            t in setOf("n", "n.", "neutr.", "neut", "neutrum", "neuter", "中性", "中") -> "das"
            else -> t
        }
    }
}

/** Anki fields are HTML; strip markup, entities and [sound:…] references. */
object AnkiText {
    fun clean(raw: String): String {
        if (raw.isEmpty()) return raw
        return raw
            .replace(Regex("\\[sound:[^\\]]*]"), "")
            .replace(Regex("(?i)<br\\s*/?>|</div>|</p>"), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
