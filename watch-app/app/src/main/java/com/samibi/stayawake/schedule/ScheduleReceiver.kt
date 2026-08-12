package com.samibi.stayawake.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.samibi.stayawake.monitor.MonitoringService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleAlarms.ACTION_FIRE) return

        val appContext = context.applicationContext
        when (intent.getStringExtra(ScheduleAlarms.EXTRA_EVENT)) {
            "start" -> MonitoringService.startScheduled(appContext)
            "stop" -> MonitoringService.stopScheduled(appContext)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ScheduleAlarms.rearm(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
