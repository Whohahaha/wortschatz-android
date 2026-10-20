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
 * Plays the bundled recording for a text when there is one, then audio prepared on the phone
 * ahead of time (see [AudioPrepJob]), and falls back to live system text-to-speech otherwise. Many phones ship without a German TTS voice, so the first
 * time the fallback cannot speak German the user is told once where to get one.
 */
class Speaker(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val files = AudioFiles(appContext)

    @Volatile
    private var ttsInitDone = false
    @Volatile
    private var ttsReady = false
    @Volatile
    private var ttsHasGerman = true
    private var warned = false
    private lateinit var tts: TextToSpeech

    /** Playback speed: 1 normally, below 1 for the slow-speech setting. */
    @Volatile
    var rate: Float = 1f

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            ttsInitDone = true
            ttsHasGerman = ttsReady && tts.setLanguage(Locale.GERMANY) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    /** Whether the phone has a German voice; null until the engine has started. */
    val hasGermanVoice: Boolean? get() = if (ttsInitDone) ttsReady && ttsHasGerman else null

    /** Safe to call from any thread; playback and toasts run on the main thread. */
    fun speak(text: String) {
        main.post { speakNow(text) }
    }

    private fun speakNow(text: String) {
        if (playRecorded(text)) return
        if (ttsReady && ttsHasGerman) {
            tts.setSpeechRate(rate)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
        } else if (ttsInitDone && !warned) {
            warned = true
            Toast.makeText(appContext, "这个词没有内置发音，手机也没有德语语音。可在系统设置的“文字转语音”里下载德语语音包。", Toast.LENGTH_LONG).show()
        }
    }

    private fun playRecorded(text: String): Boolean {
        val afd = try {
            if (files.isBundled(text)) appContext.assets.openFd(Audio.assetPath(text)) else null
        } catch (e: IOException) {
            null
        }
        val prepared = if (afd == null) files.prepared(text) ?: return false else null
        play(afd = afd, path = prepared?.path, speed = rate)
        return true
    }

    /** Plays a recording made on the phone (shadowing), then [then] when it has finished. */
    fun playFile(path: String, then: (() -> Unit)? = null) {
        main.post { play(afd = null, path = path, speed = 1f, then = then) }
    }

    private fun play(afd: android.content.res.AssetFileDescriptor?, path: String?, speed: Float, then: (() -> Unit)? = null) {
        tts.stop()
        player?.release()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            if (afd != null) afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            else setDataSource(path!!)
            setOnPreparedListener {
                if (speed != 1f) it.playbackParams = it.playbackParams.setSpeed(speed)
                it.start()
            }
            setOnCompletionListener { then?.invoke() }
            setOnErrorListener { _, _, _ -> then?.invoke(); true }
            prepareAsync()
        }
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        tts.shutdown()
    }
}
