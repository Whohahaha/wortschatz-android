package app.wortschatz.core

/** What a verb prefix usually adds to the meaning; a rule of thumb, not a definition. */
val PREFIX_MEANINGS: Map<String, String> = linkedMapOf(
    "zurück" to "回、返回", "zusammen" to "一起、合拢", "weiter" to "继续", "vorbei" to "经过、顺便",
    "heraus" to "出来", "herein" to "进来", "hinaus" to "出去", "hinein" to "进去", "fern" to "远",
    "fest" to "固定", "frei" to "空出、放开", "fort" to "离开、继续", "kennen" to "认识",
    "nach" to "跟着、之后、再……一次", "auf" to "向上、打开、开始", "aus" to "出、关掉、结束", "ein" to "进入、放进",
    "mit" to "一起、随身", "vor" to "向前、预先、在……前面", "weg" to "离开、去掉", "zu" to "关上、朝向、加上",
    "ab" to "离开、去掉、向下", "an" to "接触、开始、朝向", "bei" to "在旁、附加", "her" to "朝说话人",
    "hin" to "离开说话人、朝那边", "los" to "出发、开始", "um" to "转、换、围绕", "über" to "越过、重新、转",
    "unter" to "在下面", "wieder" to "再", "be" to "使成为及物（把对象直接作宾语）", "ver" to "改变、出错、耗掉",
    "er" to "完成、得到结果", "ent" to "去除、离开", "zer" to "碎、坏", "ge" to "（无固定意思）",
    "emp" to "（无固定意思）", "miss" to "错、不",
)

/**
 * Words of a book that share a root: a verb and its prefixed forms (fahren, abfahren, erfahren)
 * together with nouns and adjectives built on the same stem (Fahrer, Fahrt, Abfahrt).
 *
 * The grouping is a heuristic on spelling: it finds most families but not the ones whose vowel
 * changes (kommen / Ankunft, gehen / Gang), and a short stem only claims words that barely
 * extend it, so "Rat" is not put with "Rathaus".
 */
class WordFamilies(entries: List<Entry>) {
    private val familyOf: Map<String, List<Entry>>
    private val prefixOf = HashMap<String, String>()

    init {
        val verbs = entries.filter { it.pos == PartOfSpeech.VERB && it.lemma.all { c -> c.isLetter() } }
        // The root verb of every verb: the lemma without a separable or inseparable prefix.
        val rootOf = HashMap<String, String>()
        for (v in verbs) {
            val (prefix, root) = split(v)
            rootOf[v.id] = root
            if (prefix != null) prefixOf[v.id] = prefix
        }
        val groups = LinkedHashMap<String, MutableList<Entry>>()
        // Roots shared by at least two verbs, or a root verb that is in the book itself.
        val verbRoots = verbs.groupBy { rootOf.getValue(it.id) }
            .filter { (root, vs) -> vs.size > 1 || vs.any { it.lemma == root } }
        for ((root, vs) in verbRoots) groups.getOrPut(root) { mutableListOf() }.addAll(vs)

        val stems = verbRoots.keys.associateWith(::stem).filterValues { it.length >= 3 }
        for (e in entries) {
            if (e.pos == PartOfSpeech.VERB || e.lemma in UNRELATED || UNRELATED_STARTS.any { e.lemma.lowercase().startsWith(it) }) continue
            val word = e.lemma.lowercase()
            val (prefix, rest) = PREFIX_MEANINGS.keys
                .firstOrNull { word.startsWith(it) && word.length - it.length >= 3 && stems.values.any { s -> word.removePrefix(it).startsWith(s) } }
                ?.let { it to word.removePrefix(it) } ?: (null to word)
            val root = stems.entries
                .filter { (_, s) -> rest.startsWith(s) && (s.length >= 4 || rest.removePrefix(s) in SHORT_STEM_ENDINGS) }
                .maxByOrNull { it.value.length }?.key ?: continue
            groups.getValue(root).add(e)
            if (prefix != null) prefixOf[e.id] = prefix
        }
        familyOf = groups.values.filter { it.size > 1 }
            .flatMap { members -> members.map { it.id to members.toList() } }
            .toMap()
    }

    /** The word's family in book order, the word itself included; empty when it has none. */
    fun familyOf(entry: Entry): List<Entry> = familyOf[entry.id].orEmpty()

    /** The prefix that sets this word apart from its root ("ab" in abfahren, Abfahrt), if any. */
    fun prefixOf(entry: Entry): String? = prefixOf[entry.id]

    /** [entries] reordered so each family follows its first member: the order for learning by root. */
    fun grouped(entries: List<Entry>): List<Entry> {
        val placed = LinkedHashSet<String>()
        val result = ArrayList<Entry>(entries.size)
        for (e in entries) {
            if (!placed.add(e.id)) continue
            result += e
            for (m in familyOf(e)) if (placed.add(m.id)) result += m
        }
        return result
    }

    private fun split(verb: Entry): Pair<String?, String> {
        verb.separablePrefix?.let { return it to verb.lemma.removePrefix(it) }
        if (verb.lemma in UNSPLIT) return null to verb.lemma
        val prefix = INSEPARABLE.firstOrNull { verb.lemma.startsWith(it) && verb.lemma.length - it.length >= 4 }
        return if (prefix != null) prefix to verb.lemma.removePrefix(prefix) else null to verb.lemma
    }

    private companion object {
        val INSEPARABLE = listOf("miss", "emp", "ent", "ver", "zer", "be", "er", "ge")

        /** Verbs that only look prefixed: empfehlen has nothing to do with fehlen. */
        val UNSPLIT = setOf("empfehlen", "ereignen", "beginnen", "gewinnen", "verlieren")

        /** Words that only look like they share a root (Reis / reisen, seit / sein). */
        val UNRELATED = setOf("Reis", "seit", "sehr", "mal", "Mal", "halt", "bitter", "Abend")
        val UNRELATED_STARTS = listOf(
            "beispiel", "freund", "kommunikation", "landwirtschaft", "wetter", "wirklich", "passwort", "ereignis",
        )

        /** A stem of fewer than four letters only claims the plainest derivations: Frage, Fahrt, Bad. */
        val SHORT_STEM_ENDINGS = setOf("", "e", "t", "er", "en", "ung", "s", "st")

        /** fahren → fahr, wandern → wander, sammeln → samml… kept as "sammel". */
        fun stem(infinitive: String): String = when {
            infinitive.endsWith("eln") || infinitive.endsWith("ern") -> infinitive.dropLast(1)
            infinitive.endsWith("en") -> infinitive.dropLast(2)
            infinitive.endsWith("n") -> infinitive.dropLast(1)
            else -> infinitive
        }
    }
}
