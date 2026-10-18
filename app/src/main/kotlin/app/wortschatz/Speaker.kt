package app.wortschatz

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** German text-to-speech through the system engine. Calls before the engine is ready are dropped. */
class Speaker(context: Context) {
    @Volatile
    private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS &&
                tts.setLanguage(Locale.GERMANY) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    fun speak(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    fun shutdown() = tts.shutdown()
}
