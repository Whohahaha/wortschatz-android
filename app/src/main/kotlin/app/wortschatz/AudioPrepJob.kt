package app.wortschatz

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.wortschatz.core.Audio
import app.wortschatz.core.StudySession
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Prepares pronunciation for the words coming up today and tomorrow that have no bundled
 * recording (imported books), using the phone's own German voice, which works offline.
 * Runs daily in the background and right after a book is imported, so studying never
 * waits for audio and needs no connection.
 */
class AudioPrepJob : JobService() {
    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        thread(name = "audio-prep") {
            try {
                prepare(applicationContext as WortschatzApp) { stopped }
            } catch (e: Exception) {
                Log.w(TAG, "audio preparation failed", e)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }

    companion object {
        private const val TAG = "AudioPrep"
        private const val DAILY_ID = 1
        private const val NOW_ID = 2
        private const val KEEP_MS = 3L * 24 * 60 * 60 * 1000

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(DAILY_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(DAILY_ID, ComponentName(context, AudioPrepJob::class.java))
                    .setPeriodic(TimeUnit.HOURS.toMillis(12))
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build()
            )
        }

        /** Prepares audio soon, e.g. after a book was imported or switched to. */
        fun runSoon(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(NOW_ID, ComponentName(context, AudioPrepJob::class.java))
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
        }

        internal fun prepare(app: WortschatzApp, stopped: () -> Boolean) {
            val files = AudioFiles(app)
            val session = StudySession(app.entries, app.store, app.studySettings)
            val texts = session.upcomingEntries(days = 1).flatMap(Audio::texts).distinct()
            val wanted = texts.map { files.preparedFile(it).name }.toSet()
            val missing = texts.filterNot(files::isAvailable)

            if (missing.isNotEmpty()) {
                files.dir.mkdirs()
                TtsRenderer(app).use { tts ->
                    if (!tts.ready) return
                    for (text in missing) {
                        if (stopped()) return
                        val target = files.preparedFile(text)
                        val tmp = java.io.File(target.path + ".tmp")
                        if (tts.render(text, tmp) && tmp.length() > 0) tmp.renameTo(target) else tmp.delete()
                    }
                }
            }
            // Keep the folder small: drop audio for words that are not coming up again soon.
            val cutoff = System.currentTimeMillis() - KEEP_MS
            files.dir.listFiles().orEmpty()
                .filter { it.name !in wanted && it.lastModified() < cutoff }
                .forEach { it.delete() }
        }
    }
}

/** Synchronous wrapper around [TextToSpeech.synthesizeToFile] for a background thread. */
private class TtsRenderer(context: Context) : AutoCloseable {
    private val init = CountDownLatch(1)
    @Volatile
    private var status = TextToSpeech.ERROR
    private val tts = TextToSpeech(context) { status = it; init.countDown() }
    private var pending: CountDownLatch? = null
    @Volatile
    private var ok = false

    val ready: Boolean = init.await(15, TimeUnit.SECONDS) && status == TextToSpeech.SUCCESS &&
        tts.setLanguage(Locale.GERMANY) >= TextToSpeech.LANG_AVAILABLE

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { ok = true; pending?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { ok = false; pending?.countDown() }
            override fun onError(utteranceId: String, errorCode: Int) { ok = false; pending?.countDown() }
        })
    }

    fun render(text: String, file: java.io.File): Boolean {
        val latch = CountDownLatch(1)
        pending = latch
        ok = false
        if (tts.synthesizeToFile(text, Bundle(), file, text) != TextToSpeech.SUCCESS) return false
        return latch.await(30, TimeUnit.SECONDS) && ok
    }

    override fun close() = tts.shutdown()
}
