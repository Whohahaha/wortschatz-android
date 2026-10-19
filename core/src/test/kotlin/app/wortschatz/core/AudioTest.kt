package app.wortschatz.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {
    @Test
    fun keyIsShortSha1OfTheSpokenText() {
        // Same value as Python's hashlib.sha1("der Tisch".encode()).hexdigest()[:16]
        assertEquals("2715b44ea5b3df78", Audio.key("der Tisch"))
        assertEquals("audio/2715b44ea5b3df78.webm", Audio.assetPath("der Tisch"))
    }

    @Test
    fun textsCoverWordPluralVerbFormsAndExample() {
        val entries = WordBook.parse(File("../app/src/main/assets/wordbooks/goethe_a1.tsv").readText())
        val tisch = entries.first { it.lemma == "Tisch" }
        assertEquals(listOf("der Tisch", "die Tische", tisch.example), Audio.texts(tisch))
        val fahren = entries.first { it.lemma == "fahren" }
        assertEquals("fährt, fuhr, ist gefahren", Audio.verbForms(fahren))
        val milch = entries.first { it.lemma == "Milch" }
        assertEquals(listOf("die Milch", milch.example), Audio.texts(milch))
    }

    @Test
    fun manifestListsEachTextOnce() {
        val out = Files.createTempFile("manifest", ".tsv").toFile()
        main(arrayOf("../app/src/main/assets/wordbooks", out.path))
        val lines = out.readLines()
        assertTrue(lines.size > 200, "only ${lines.size} lines")
        assertEquals(lines.size, lines.toSet().size)
        assertTrue("2715b44ea5b3df78\tder Tisch" in lines)
    }
}
