package app.wortschatz

import android.content.Context
import app.wortschatz.core.Audio
import java.io.File

/**
 * Where pronunciation for a text comes from, best first: a recording bundled in the APK,
 * then audio prepared on this phone ahead of time (files/audio/<key>.wav), then live TTS.
 */
class AudioFiles(context: Context) {
    private val assets = context.applicationContext.assets
    val dir = File(context.filesDir, Audio.DIR)

    private val bundled: Set<String> by lazy { assets.list(Audio.DIR).orEmpty().toSet() }

    fun isBundled(text: String) = "${Audio.key(text)}.${Audio.EXTENSION}" in bundled

    fun prepared(text: String): File? = preparedFile(text).takeIf { it.length() > 0 }

    fun preparedFile(text: String) = File(dir, "${Audio.key(text)}.wav")

    fun isAvailable(text: String) = isBundled(text) || prepared(text) != null
}
