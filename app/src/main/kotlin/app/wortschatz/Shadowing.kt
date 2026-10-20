package app.wortschatz

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Shadowing: the learner records themselves saying the word or sentence, then hears their own
 * take followed by the reference pronunciation, to compare the two. Recordings stay in the cache.
 */
class Shadowing(private val context: Context) {
    private var recorder: MediaRecorder? = null
    val file: File get() = File(context.cacheDir, "shadow.m4a")
    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean = try {
        stop()
        recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(96_000)
            setOutputFile(file.path)
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        recorder?.release()
        recorder = null
        false
    }

    /** Stops recording; returns whether a usable recording was made. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // Stopping right after starting leaves no audio.
            false
        } finally {
            r.release()
        }
    }
}
