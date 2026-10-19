package app.wortschatz.core

import app.wortschatz.core.imports.AnkiText
import app.wortschatz.core.imports.ColumnRole
import app.wortschatz.core.imports.DelimitedText
import app.wortschatz.core.imports.GermanParsing
import app.wortschatz.core.imports.ImportOptions
import app.wortschatz.core.imports.ImportSource
import app.wortschatz.core.imports.Importer
import app.wortschatz.core.imports.Lexicon
import app.wortschatz.core.imports.SqlDatabase
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportTest {
    private val lexicon = Lexicon.parse(
        """
        Tisch	n	der	Tische
        Haus	n	das	Häuser
        Lampe	n	die	Lampen
        fahren	v			fährt	fuhr	gefahren	sein
        anrufen	v			ruft an	rief an	angerufen	haben
        """.trimIndent().lineSequence()
    )

    @Test
    fun headwordNotations() {
        fun p(s: String) = GermanParsing.parse(s)
        assertEquals(Gender.DER to null, p("der Tisch").let { it.gender to it.plural })
        assertEquals("Tische", p("der Tisch, -e").plural)
        assertEquals("Stühle", p("der Stuhl, ¨-e").plural)
        assertEquals("Stühle", p("der Stuhl, -¨e").plural)
        assertEquals("Häuser", p("das Haus, ¨-er").plural)
        assertEquals("Äpfel", p("der Apfel, ¨").plural)
        assertEquals("Zimmer", p("das Zimmer, -").plural)
        assertEquals("Tische", p("der Tisch, die Tische").plural)
        assertEquals(Gender.DIE, p("Lampe, die").gender)
        assertEquals(Gender.DAS, p("Haus (n)").gender)
        assertEquals(Gender.DER, p("Tisch m").gender)
        assertEquals(Gender.DIE, p("e Lampe").gender)
        assertEquals("Lampen", p("Lampen").lemma)
        assertNull(p("Lampen").gender)
        assertEquals("Bank (Geldinstitut)", p("Bank (Geldinstitut)").lemma)
        assertEquals("Tische", p("Tisch (-e)").plural)
        assertNull(p("die Milch, o. Pl.").plural)

        val anrufen = p("an|rufen")
        assertEquals("anrufen" to "an", anrufen.lemma to anrufen.separablePrefix)
        val fahren = p("fahren (fährt, fuhr, ist gefahren)")
        assertEquals(VerbForms("fährt", "fuhr", "gefahren", "sein"), fahren.verbForms)
        assertEquals("fahren", fahren.lemma)
    }

    @Test
    fun delimiterQuotesAndHeaders() {
        val csv = "Deutsch,Chinesisch\n\"der Tisch, -e\",桌子\n\"die Lampe\",\"灯, 台灯\"\n"
        val t = DelimitedText.parse(csv)
        assertEquals(listOf("Deutsch", "Chinesisch"), t.headers)
        assertEquals(listOf("der Tisch, -e", "桌子"), t.rows[0])
        assertEquals("灯, 台灯", t.rows[1][1])

        // Quizlet-style export: tab between term and definition, no header.
        val quizlet = DelimitedText.parse("der Tisch\t桌子\nfahren\t驾驶\n")
        assertEquals(2, quizlet.rows.size)
        assertEquals('\t', DelimitedText.guessDelimiter("der Tisch\t桌子\nfahren\t驾驶\n"))
        assertEquals(';', DelimitedText.guessDelimiter("der Tisch;桌子\ndie Lampe;灯\n"))
    }

    @Test
    fun guessesColumnsFromContentAndCompletesGrammar() {
        val t = DelimitedText.parse("桌子\tTisch\tDer Tisch ist sehr neu.\n灯\tdie Lampe\tDie Lampe ist leider kaputt.\n驾驶\tfahren\tIch fahre morgen nach Berlin.\n打电话\tanrufen\tIch rufe dich morgen an.\n")
        val mapping = Importer.guessMapping(t)
        assertEquals(1, mapping[ColumnRole.GERMAN])
        assertEquals(0, mapping[ColumnRole.MEANING])
        assertEquals(2, mapping[ColumnRole.EXAMPLE])

        val r = Importer.build(t, ImportOptions(mapping), "book1", lexicon)
        assertEquals(4, r.entries.size)
        val tisch = r.entries[0]
        assertEquals(Gender.DER to "Tische", tisch.gender to tisch.plural)
        assertEquals("book1~1", tisch.id)
        assertEquals(listOf(CardType.MEANING, CardType.ARTICLE, CardType.PLURAL), cardTypesFor(tisch))
        assertEquals("fährt", r.entries[2].verbForms?.present3)
        assertEquals("an·rufen", r.entries[3].display)
        assertEquals(4, r.completed)

        val plain = Importer.build(t, ImportOptions(mapping, autoComplete = false), "book1", lexicon)
        assertNull(plain.entries[0].gender)
        assertEquals(PartOfSpeech.OTHER, plain.entries[0].pos)
    }

    @Test
    fun skipsDuplicatesAndEmptyRows() {
        val t = DelimitedText.parse("der Tisch\t桌子\nder Tisch\t台子\ndie Lampe\t\n\t空\nTisch\t表格\n")
        val r = Importer.build(t, ImportOptions(mapOf(ColumnRole.GERMAN to 0, ColumnRole.MEANING to 1), autoComplete = false), "b")
        assertEquals(listOf("桌子", "表格"), r.entries.map { it.meaning })
        assertEquals(1, r.duplicates)
        assertEquals(2, r.skipped)

        val against = Importer.build(t, ImportOptions(mapOf(ColumnRole.GERMAN to 0, ColumnRole.MEANING to 1)), "b", lexicon,
            existingKeys = setOf(Importer.dedupeKey("Tisch", Gender.DER)))
        assertEquals(0, against.entries.size)
    }

    @Test
    fun ankiHtmlIsCleaned() {
        assertEquals("Der Tisch ist neu.", AnkiText.clean("Der <b>Tisch</b>&nbsp;ist neu.[sound:tisch.mp3]"))
        assertEquals("a b", AnkiText.clean("a<br/>b"))
    }

    @Test
    fun readsLegacyAndModernAnkiPackages() {
        for (name in listOf("legacy.apkg", "modern.apkg")) {
            val bytes = javaClass.getResource("/anki/$name")!!.readBytes()
            val source = ImportSource.detect(name, bytes, ::jdbc, ::ZstdInputStream, Files.createTempDirectory("t").toFile())
            assertIs<ImportSource.Tabular>(source, name)
            val t = source.table
            assertEquals(listOf("German", "English", "Plural", "Example"), t.headers, name)
            assertEquals(4, t.rows.size, name)

            val mapping = Importer.guessMapping(t)
            assertEquals(0, mapping[ColumnRole.GERMAN], name)
            assertEquals(1, mapping[ColumnRole.MEANING], name)
            assertEquals(2, mapping[ColumnRole.PLURAL], name)
            val r = Importer.build(t, ImportOptions(mapping), "anki", lexicon)
            val tisch = r.entries.first()
            assertEquals("Tisch", tisch.lemma, name)
            assertEquals("Tische", tisch.plural, name)
            assertEquals("Der Tisch ist neu.", tisch.example, name)
            // "Haus" had no article in the deck; the lexicon supplies it.
            assertEquals(Gender.DAS to "Häuser", r.entries.first { it.lemma == "Haus" }.let { it.gender to it.plural }, name)
        }
    }

    @Test
    fun nativeFormatRoundTrips() {
        val entries = WordBook.parse(File("../app/src/main/assets/wordbooks/goethe_a1.tsv").readText())
        val written = WordBook.write(entries, "我的词书")
        assertEquals("我的词书", WordBook.title(written))
        val source = ImportSource.detect("x.tsv", written.toByteArray(), ::jdbc, ::ZstdInputStream, Files.createTempDirectory("t").toFile())
        assertIs<ImportSource.Native>(source)
        assertEquals(entries, source.entries)
    }

    @Test
    fun gbkTextIsDecoded() {
        val bytes = "der Tisch\t桌子\n".toByteArray(charset("GB18030"))
        assertTrue(ImportSource.decode(bytes).contains("桌子"))
    }

    private fun jdbc(file: File): SqlDatabase {
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.path}")
        return object : SqlDatabase {
            override fun tables(): Set<String> = query("SELECT name FROM sqlite_master WHERE type = 'table'").mapNotNull { it[0] }.toSet()
            override fun query(sql: String): List<List<String?>> = conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val n = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
                }
            }
            override fun close() = conn.close()
        }
    }
}
