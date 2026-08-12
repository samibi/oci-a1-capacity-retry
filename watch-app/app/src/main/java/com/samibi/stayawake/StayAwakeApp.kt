package com.samibi.stayawake

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class StayAwakeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)

        val monitoringChannel = NotificationChannel(
            CHANNEL_MONITORING,
            "Monitoring session",
            NotificationManager.IMPORTANCE_LOW,
        )

        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            "Wake-up alarm",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
        }

        manager.createNotificationChannel(monitoringChannel)
        manager.createNotificationChannel(alarmChannel)
    }

    companion object {
        const val CHANNEL_MONITORING = "monitoring"
        const val CHANNEL_ALARM = "alarm"
    }
}
