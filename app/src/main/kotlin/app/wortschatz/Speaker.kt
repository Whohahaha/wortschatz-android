package app.wortschatz

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Toast
import app.wortschatz.core.Audio
import java.io.IOException
import java.util.Locale

/**
 * Plays the bundled recording for a text when there is one, and falls back to the system
 * text-to-speech engine otherwise. Many phones ship without a German TTS voice, so the first
 * time the fallback cannot speak German the user is told once where to get one.
 */
class Speaker(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    @Volatile
    private var ttsInitDone = false
    @Volatile
    private var ttsReady = false
    @Volatile
    private var ttsHasGerman = true
    private var warned = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            ttsInitDone = true
            ttsHasGerman = ttsReady && tts.setLanguage(Locale.GERMANY) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    /** Safe to call from any thread; playback and toasts run on the main thread. */
    fun speak(text: String) {
        main.post { speakNow(text) }
    }

    private fun speakNow(text: String) {
        if (playBundled(text)) return
        if (ttsReady && ttsHasGerman) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
        } else if (ttsInitDone && !warned) {
            warned = true
            Toast.makeText(appContext, "这个词没有内置发音，手机也没有德语语音。可在系统设置的“文字转语音”里下载德语语音包。", Toast.LENGTH_LONG).show()
        }
    }

    private fun playBundled(text: String): Boolean {
        val afd = try {
            appContext.assets.openFd(Audio.assetPath(text))
        } catch (e: IOException) {
            return false
        }
        tts.stop()
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ -> true }
            prepareAsync()
        }
        return true
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        tts.shutdown()
    }
}
