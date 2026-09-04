package com.jarvis.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object AssistantTools {
    private const val PREFS = "jarvis_tools"
    private const val NOTES = "notes"
    private const val TASKS = "tasks"
    private val idGenerator = AtomicInteger(1000)

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun addNote(c: Context, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val old = p(c).getString(NOTES, "") ?: ""
        val stamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
        p(c).edit().putString(NOTES, "$old\n[$stamp] $clean".trim()).apply()
    }

    fun addTask(c: Context, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val old = p(c).getString(TASKS, "") ?: ""
        p(c).edit().putString(TASKS, "$old\n- $clean".trim()).apply()
    }

    fun summary(c: Context): String {
        val notes = p(c).getString(NOTES, "")?.trim().orEmpty()
        val tasks = p(c).getString(TASKS, "")?.trim().orEmpty()
        return "یادداشت‌ها:\n${if (notes.isEmpty()) "موردی ثبت نشده" else notes}\n\nکارها:\n${if (tasks.isEmpty()) "موردی ثبت نشده" else tasks}"
    }

    fun clear(c: Context) { p(c).edit().remove(NOTES).remove(TASKS).apply() }
    fun clearNotes(c: Context) { p(c).edit().remove(NOTES).apply() }
    fun clearTasks(c: Context) { p(c).edit().remove(TASKS).apply() }

    fun setTimer(c: Context, seconds: Int, title: String = "تایمر جارویس") {
        val safeSeconds = seconds.coerceIn(1, 7 * 24 * 3600)
        val triggerAt = System.currentTimeMillis() + safeSeconds * 1000L
        val intent = Intent(c, ReminderReceiver::class.java).putExtra("title", title)
        val requestCode = idGenerator.incrementAndGet()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(c, requestCode, intent, flags)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val title = intent?.getStringExtra("title") ?: "جارویس"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "jarvis_reminders"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "یادآوری‌های جارویس", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("زمان یادآوری فرا رسید.")
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            nm.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
        }
    }
}
