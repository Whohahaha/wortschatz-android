package app.wortschatz

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.wortschatz.core.StudySession
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * Daily study reminder at the hour the user picked. It only shows up when there is
 * something to do, and says exactly how much ("12 个词快忘了"), which works better than
 * a generic "time to study".
 */
object Reminder {
    private const val CHANNEL = "daily"
    private const val NOTIFICATION_ID = 1
    const val KEY_ENABLED = "reminder_enabled"
    const val KEY_HOUR = "reminder_hour"
    const val DEFAULT_HOUR = 20

    /** (Re)schedules or cancels the daily alarm according to the settings. */
    fun schedule(context: Context) {
        val app = context.applicationContext as WortschatzApp
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.cancel(pending)
        if (!app.prefs.getBoolean(KEY_ENABLED, false)) return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, app.prefs.getInt(KEY_HOUR, DEFAULT_HOUR))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Inexact on purpose: no special permission, and a few minutes late does not matter.
        alarms.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.timeInMillis, AlarmManager.INTERVAL_DAY, pending)
    }

    internal fun notifyIfDue(context: Context) {
        val app = context.applicationContext as WortschatzApp
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val session = StudySession(app.entries, app.store, app.studySettings)
        val counts = session.counts()
        if (counts.total == 0) return
        val text = when {
            counts.review + counts.learning > 0 -> "有 ${counts.review + counts.learning} 个词快忘了，花几分钟复习一下吧。"
            else -> "今天的 ${counts.new} 个新词还没学。"
        }
        val streak = session.streak()
        val title = if (streak > 0 && session.reviewsToday() == 0) "别断了连续 $streak 天的记录" else "该背单词了"

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "每日提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/** Fires the daily reminder, and puts the alarm back after a reboot. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminder.schedule(context)
            return
        }
        val pending = goAsync()
        thread(name = "reminder") {
            try {
                Reminder.notifyIfDue(context)
            } finally {
                pending.finish()
            }
        }
    }
}
