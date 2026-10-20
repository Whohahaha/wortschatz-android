package app.wortschatz.core

/** Whether the words of a confusable group can stand in for each other. */
enum class Swap(val code: String, val label: String) {
    YES("yes", "可以互换"), PARTLY("partly", "部分可换"), NO("no", "不能互换");

    companion object {
        fun parse(code: String): Swap? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/** An example sentence for a confusable group, with its translation. */
data class NoteExample(val german: String, val chinese: String)

/** Words that are easily mixed up, with what tells them apart. [words] are headwords without "|". */
data class ConfusableGroup(
    val words: List<String>,
    val swap: Swap,
    val difference: String,
    val examples: List<NoteExample>,
)

/**
 * Hand-written notes on confusable words (kennen / wissen, legen / liegen …), keyed by headword
 * so they apply to every word book. Tab-separated with a header row; '#' lines are comments:
 *
 * words (comma-separated), swap (yes / partly / no), difference, example1, example2, example3
 *
 * An example is "German｜Chinese".
 */
class ConfusableNotes(groups: List<ConfusableGroup>) {
    private val byWord: Map<String, List<ConfusableGroup>> = buildMap<String, MutableList<ConfusableGroup>> {
        for (g in groups) for (w in g.words) getOrPut(w) { mutableListOf() }.add(g)
    }

    /** Groups that mention [entry]'s headword, at most [limit]. */
    fun groupsFor(entry: Entry, limit: Int = 2): List<ConfusableGroup> = byWord[entry.lemma].orEmpty().take(limit)

    companion object {
        val EMPTY = ConfusableNotes(emptyList())

        fun parse(text: String): ConfusableNotes {
            val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
            if (lines.isEmpty()) return EMPTY
            val groups = lines.drop(1).mapNotNull { line ->
                val c = line.split('\t').map { it.trim() }
                val swap = c.getOrNull(1)?.let(Swap::parse) ?: return@mapNotNull null
                val words = c[0].split(',').map { it.trim().replace("|", "") }.filter { it.isNotEmpty() }
                val difference = c.getOrNull(2).orEmpty()
                if (words.isEmpty() || difference.isEmpty()) return@mapNotNull null
                val examples = c.drop(3).filter { it.contains('｜') }.map {
                    NoteExample(it.substringBefore('｜').trim(), it.substringAfter('｜').trim())
                }
                ConfusableGroup(words, swap, difference, examples)
            }
            return ConfusableNotes(groups)
        }
    }
}
