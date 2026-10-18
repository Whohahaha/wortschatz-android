package app.wortschatz.core

import java.io.File
import java.security.MessageDigest

/**
 * Pronunciation audio is generated ahead of time (tools/gen_audio.py) and bundled as
 * assets/audio/<key>.webm. The key is derived from the exact text spoken, so the app and the
 * generator agree as long as both go through [audioTexts] and [audioKey].
 */
object Audio {
    const val DIR = "audio"
    const val EXTENSION = "webm"

    fun key(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun assetPath(text: String) = "$DIR/${key(text)}.$EXTENSION"

    fun plural(entry: Entry): String? = entry.plural?.let { "die $it" }

    /** "fährt, fuhr, hat gefahren" */
    fun verbForms(entry: Entry): String? = entry.verbForms?.let {
        "${it.present3}, ${it.preterite}, ${if (it.auxiliary == "sein") "ist" else "hat"} ${it.participle}"
    }

    /** Everything the app may read aloud for an entry. */
    fun texts(entry: Entry): List<String> =
        listOfNotNull(entry.spoken, plural(entry), verbForms(entry), entry.example.ifEmpty { null })
}

/**
 * Writes "key<TAB>text" lines for every bundled word book, as input for the audio generator.
 * Usage: audioManifest <word book dir> <output file>
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: audioManifest <word book dir> <output file>" }
    val texts = File(args[0]).listFiles { f -> f.extension == "tsv" }.orEmpty().sorted()
        .flatMap { WordBook.parse(it.readText()) }
        .flatMap(Audio::texts)
        .distinct()
    File(args[1]).apply { parentFile?.mkdirs() }.writeText(texts.joinToString("") { "${Audio.key(it)}\t$it\n" })
    println("Wrote ${texts.size} texts to ${args[1]}")
}
