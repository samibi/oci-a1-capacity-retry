package com.samibi.stayawake.monitor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import com.samibi.stayawake.R
import com.samibi.stayawake.StayAwakeApp
import com.samibi.stayawake.alarm.AlarmActivity
import com.samibi.stayawake.data.SettingsStore
import com.samibi.stayawake.ui.MainActivity
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class MonitoringService : LifecycleService() {

    private var detector: SleepOnsetDetector? = null
    private var sensorManager: SensorManager? = null
    private var accelListener: SensorEventListener? = null
    private var vibrator: Vibrator? = null
    private var exerciseCallback: ExerciseUpdateCallback? = null

    private val motionSamples = mutableListOf<Double>()
    private var motionEpochStartElapsedMs: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_MANUAL -> {
                if (!MonitorState.flow.value.running) startSession(StartReason.MANUAL)
            }
            ACTION_START_SCHEDULED -> {
                if (!MonitorState.flow.value.running) startSession(StartReason.SCHEDULED)
            }
            ACTION_STOP -> stopSession()
            ACTION_STOP_SCHEDULED -> {
                if (MonitorState.flow.value.startReason == StartReason.SCHEDULED) stopSession()
            }
            ACTION_DISMISS_ALARM -> dismissAlarm()
        }
        return START_NOT_STICKY
    }

    private fun startSession(reason: StartReason) {
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, StayAwakeApp.CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("StayAwake")
            .setContentText("Watching for sleep onset")
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)

        OngoingActivity.Builder(applicationContext, NOTIF_ID, builder)
            .setStaticIcon(R.drawable.ic_notif)
            .setTouchIntent(contentPendingIntent)
            .build()
            .apply(applicationContext)

        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        ServiceCompat.startForeground(this, NOTIF_ID, builder.build(), type)

        MonitorState.flow.value = MonitorSnapshot(running = true, startReason = reason)

        lifecycleScope.launch {
            val sensitivity = SettingsStore.get(this@MonitoringService).currentSensitivity()
            val newDetector = SleepOnsetDetector(DetectorConfig.forSensitivity(sensitivity))
            newDetector.start(System.currentTimeMillis())
            detector = newDetector

            registerAccelerometer()
            startExercise()
            startPassiveBackstop()
        }
    }

    private fun registerAccelerometer() {
        val manager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager = manager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

        motionSamples.clear()
        motionEpochStartElapsedMs = SystemClock.elapsedRealtime()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                motionSamples.add(sqrt((x * x + y * y + z * z).toDouble()))

                val elapsed = SystemClock.elapsedRealtime() - motionEpochStartElapsedMs
                if (elapsed >= 5000L) {
                    val mad = meanAbsoluteDeviation(motionSamples)
                    val d = detector
                    if (d != null) {
                        d.onMotion(System.currentTimeMillis(), mad)
                        val snap = d.snapshot(System.currentTimeMillis())
                        MonitorState.flow.value = MonitorState.flow.value.copy(
                            stillForSec = snap.stillForMs / 1000L,
                        )
                    }
                    motionSamples.clear()
                    motionEpochStartElapsedMs = SystemClock.elapsedRealtime()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        accelListener = listener
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, 1_000_000)
    }

    private fun meanAbsoluteDeviation(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.sum() / values.size
        return values.sumOf { abs(it - mean) } / values.size
    }

    private suspend fun startExercise() {
        val exerciseClient = HealthServices.getClient(this).exerciseClient
        try {
            val callback = object : ExerciseUpdateCallback {
                override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                    val samples = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
                    for (sample in samples) {
                        handleHeartRate(sample.value)
                    }
                }

                override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}

                override fun onRegistered() {}

                override fun onRegistrationFailed(throwable: Throwable) {
                    MonitorState.flow.value = MonitorState.flow.value.copy(error = throwable.message)
                }

                override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
            }
            exerciseCallback = callback
            exerciseClient.setUpdateCallback(callback)

            fun buildConfig(withFastBatching: Boolean): ExerciseConfig {
                val builder = ExerciseConfig.builder(ExerciseType.WORKOUT)
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                    .setIsAutoPauseAndResumeEnabled(false)
                    .setIsGpsEnabled(false)
                if (withFastBatching) {
                    builder.setBatchingModeOverrides(setOf(BatchingMode.HEART_RATE_5_SECONDS))
                }
                return builder.build()
            }

            // health-services-client 1.0.0 has no capability query for batching-mode
            // overrides, so probe by attempting the fast-batching config first.
            val fastBatching = try {
                exerciseClient.startExerciseAsync(buildConfig(withFastBatching = true)).await()
                true
            } catch (e: Exception) {
                exerciseClient.startExerciseAsync(buildConfig(withFastBatching = false)).await()
                false
            }
            MonitorState.flow.value = MonitorState.flow.value.copy(fastBatchingSupported = fastBatching)
        } catch (e: Exception) {
            MonitorState.flow.value = MonitorState.flow.value.copy(error = e.message)
        }
    }

    private fun startPassiveBackstop() {
        try {
            val passiveClient = HealthServices.getClient(this).passiveMonitoringClient
            val passiveConfig = PassiveListenerConfig.builder()
                .setShouldUserActivityInfoBeRequested(true)
                .build()
            passiveClient.setPassiveListenerCallback(
                passiveConfig,
                object : PassiveListenerCallback {
                    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
                        if (info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP) {
                            val event = detector?.onUserActivityAsleep(System.currentTimeMillis()) ?: DetectorEvent.NONE
                            handleDetectorEvent(event)
                        }
                    }
                },
            )
        } catch (e: Exception) {
            // ACTIVITY_RECOGNITION permission may be missing; non-fatal.
        }
    }

    private fun handleHeartRate(bpm: Double) {
        val d = detector ?: return
        val now = System.currentTimeMillis()
        val event = d.onHeartRate(now, bpm)
        val snap = d.snapshot(now)
        MonitorState.flow.value = MonitorState.flow.value.copy(
            currentHr = snap.currentHr,
            baselineHr = snap.baselineHr,
            hrLowForSec = snap.hrLowForMs / 1000L,
            stillForSec = snap.stillForMs / 1000L,
        )
        handleDetectorEvent(event)
    }

    private fun handleDetectorEvent(event: DetectorEvent) {
        if (event == DetectorEvent.ALARM) {
            triggerAlarm()
        }
    }

    private fun triggerAlarm() {
        MonitorState.flow.value = MonitorState.flow.value.copy(alarmActive = true)

        val v = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 300), intArrayOf(0, 255, 0), 1)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build())
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

        val alarmIntent = Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            1,
            alarmIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, StayAwakeApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Wake up!")
            .setContentText("You were falling asleep")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .build()

        try {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                NotificationManagerCompat.from(this).notify(ALARM_NOTIF_ID, notification)
            }
        } catch (e: SecurityException) {
            // Notifications permission not granted; rely on the direct startActivity below.
        }

        try {
            startActivity(alarmIntent)
        } catch (e: Exception) {
            // best-effort
        }
    }

    private fun dismissAlarm() {
        vibrator?.cancel()
        try {
            NotificationManagerCompat.from(this).cancel(ALARM_NOTIF_ID)
        } catch (e: SecurityException) {
            // ignore
        }
        MonitorState.flow.value = MonitorState.flow.value.copy(alarmActive = false)
    }

    private fun stopSession() {
        lifecycleScope.launch {
            val client = HealthServices.getClient(this@MonitoringService)
            try {
                client.exerciseClient.endExerciseAsync().await()
            } catch (e: Exception) {
                // ignore
            }
            val callback = exerciseCallback
            if (callback != null) {
                try {
                    client.exerciseClient.clearUpdateCallbackAsync(callback).await()
                } catch (e: Exception) {
                    // ignore
                }
            }
            try {
                client.passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            } catch (e: Exception) {
                // ignore
            }

            unregisterAccelerometer()
            vibrator?.cancel()
            try {
                NotificationManagerCompat.from(this@MonitoringService).cancel(ALARM_NOTIF_ID)
            } catch (e: SecurityException) {
                // ignore
            }

            MonitorState.flow.value = MonitorSnapshot()

            ServiceCompat.stopForeground(this@MonitoringService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun unregisterAccelerometer() {
        val manager = sensorManager
        val listener = accelListener
        if (manager != null && listener != null) {
            manager.unregisterListener(listener)
        }
        sensorManager = null
        accelListener = null
    }

    override fun onDestroy() {
        unregisterAccelerometer()
        vibrator?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val ALARM_NOTIF_ID = 1002

        private const val ACTION_START_MANUAL = "com.samibi.stayawake.action.START_MANUAL"
        private const val ACTION_START_SCHEDULED = "com.samibi.stayawake.action.START_SCHEDULED"
        private const val ACTION_STOP = "com.samibi.stayawake.action.STOP"
        private const val ACTION_STOP_SCHEDULED = "com.samibi.stayawake.action.STOP_SCHEDULED"
        private const val ACTION_DISMISS_ALARM = "com.samibi.stayawake.action.DISMISS_ALARM"

        fun startManual(context: Context) {
            context.startForegroundService(Intent(context, MonitoringService::class.java).setAction(ACTION_START_MANUAL))
        }

        fun startScheduled(context: Context) {
            context.startForegroundService(Intent(context, MonitoringService::class.java).setAction(ACTION_START_SCHEDULED))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }

        fun stopScheduled(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP_SCHEDULED))
        }

        fun dismissAlarm(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_DISMISS_ALARM))
        }
    }
}
