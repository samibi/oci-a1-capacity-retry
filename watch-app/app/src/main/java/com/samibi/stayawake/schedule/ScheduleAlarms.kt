package com.samibi.stayawake.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.samibi.stayawake.data.SettingsStore
import com.samibi.stayawake.monitor.MonitoringService
import java.time.ZonedDateTime

object ScheduleAlarms {
    const val ACTION_FIRE = "com.samibi.stayawake.action.SCHEDULE_FIRE"
    const val EXTRA_EVENT = "event" // "start" | "stop"

    private const val APP_PACKAGE = "com.samibi.stayawake"
    private const val REQUEST_CODE = 0

    suspend fun rearm(context: Context, startIfInsideWindow: Boolean = false) {
        val windows = SettingsStore.get(context).currentWindows()
            .filter { it.enabled && it.days.isNotEmpty() }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = ZonedDateTime.now()

        // Cancel any previously scheduled alarm; extras don't affect PendingIntent matching.
        val cancelIntent = Intent(context, ScheduleReceiver::class.java).setAction(ACTION_FIRE)
        PendingIntent.getBroadcast(
            context, REQUEST_CODE, cancelIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { alarmManager.cancel(it) }

        var earliest: ZonedDateTime? = null
        var earliestEvent: String? = null
        for (window in windows) {
            for (dayOffset in 0..7) {
                val date = now.toLocalDate().plusDays(dayOffset.toLong())
                if (date.dayOfWeek.value !in window.days) continue
                val start = date.atStartOfDay(now.zone).plusMinutes(window.startMin.toLong())
                val end = start.plusMinutes(windowDurationMinutes(window).toLong())

                if (start.isAfter(now) && (earliest == null || start.isBefore(earliest))) {
                    earliest = start
                    earliestEvent = "start"
                }
                if (end.isAfter(now) && (earliest == null || end.isBefore(earliest))) {
                    earliest = end
                    earliestEvent = "stop"
                }
            }
        }

        if (earliest != null && earliestEvent != null) {
            val fireIntent = Intent(context, ScheduleReceiver::class.java)
                .setAction(ACTION_FIRE)
                .putExtra(EXTRA_EVENT, earliestEvent)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, fireIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val epochMilli = earliest.toInstant().toEpochMilli()
            if (canUseExactAlarms(context)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMilli, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMilli, pi)
            }
        }

        if (startIfInsideWindow && isInsideWindow(windows, now)) {
            MonitoringService.startScheduled(context)
        }
    }

    fun canUseExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun requestExactAlarmSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:$APP_PACKAGE"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$APP_PACKAGE"))
        }
    }

    /** Duration in minutes, handling windows that cross midnight (endMin <= startMin). */
    private fun windowDurationMinutes(window: ScheduleWindow): Int {
        return if (window.endMin > window.startMin) {
            window.endMin - window.startMin
        } else {
            1440 - window.startMin + window.endMin
        }
    }

    /** True if `now` falls inside any enabled window, considering starts from today or yesterday. */
    private fun isInsideWindow(windows: List<ScheduleWindow>, now: ZonedDateTime): Boolean {
        for (window in windows) {
            for (dayOffset in -1..0) {
                val date = now.toLocalDate().plusDays(dayOffset.toLong())
                if (date.dayOfWeek.value !in window.days) continue
                val start = date.atStartOfDay(now.zone).plusMinutes(window.startMin.toLong())
                val end = start.plusMinutes(windowDurationMinutes(window).toLong())
                if (!start.isAfter(now) && end.isAfter(now)) {
                    return true
                }
            }
        }
        return false
    }
}
