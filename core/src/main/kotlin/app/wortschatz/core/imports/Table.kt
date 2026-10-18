package app.wortschatz.core.imports

/** Rows of cells from any import source; [headers] names each column. */
data class Table(val headers: List<String>, val rows: List<List<String>>) {
    val columnCount: Int get() = headers.size

    fun cell(row: List<String>, column: Int?): String = column?.let { row.getOrNull(it)?.trim() }.orEmpty()
}

/**
 * CSV / TSV / semicolon-separated text as exported by Excel, WPS, Google Sheets, Quizlet
 * and dictionary apps. The delimiter is guessed from the first lines; quoted cells may
 * contain delimiters, doubled quotes and line breaks (RFC 4180).
 */
object DelimitedText {
    private val candidates = listOf('\t', ';', ',', '|')

    fun guessDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.take(20).toList()
        if (sample.isEmpty()) return '\t'
        // The right delimiter splits every line into the same number (> 1) of cells.
        return candidates.maxByOrNull { d ->
            val counts = sample.map { line -> splitLine(line, d).size }
            val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (common == null || common.key < 2) 0.0 else common.value.toDouble() / sample.size + common.key / 100.0
        } ?: '\t'
    }

    fun parse(text: String, delimiter: Char = guessDelimiter(text), hasHeader: Boolean? = null): Table {
        val records = records(text.removePrefix("﻿"), delimiter)
            .filter { r -> r.any { it.isNotBlank() } && !(r.size == 1 && r[0].startsWith("#")) }
        if (records.isEmpty()) return Table(emptyList(), emptyList())
        val width = records.maxOf { it.size }
        val padded = records.map { it + List(width - it.size) { "" } }
        val header = hasHeader ?: looksLikeHeader(padded.first())
        return if (header) Table(padded.first().map { it.trim() }, padded.drop(1))
        else Table((1..width).map { "第 $it 列" }, padded)
    }

    /** Only a first row whose cells are recognisable column names counts as a header. */
    fun looksLikeHeader(first: List<String>): Boolean = first.count { HeaderNames.roleOf(it) != null } >= 1

    private fun splitLine(line: String, d: Char): List<String> = records(line, d).firstOrNull().orEmpty()

    private fun records(text: String, d: Char): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isBlank()) -> { quoted = !quoted; if (!quoted) Unit else cell.setLength(0) }
                !quoted && c == d -> { row.add(cell.toString()); cell.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.setLength(0)
                    out.add(row); row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            out.add(row)
        }
        return out
    }
}
