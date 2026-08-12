package com.samibi.stayawake.monitor

enum class DetectorEvent { NONE, ALARM }

data class DetectorSnapshot(
    val currentHr: Double? = null,
    val baselineHr: Double? = null,
    val hrLowForMs: Long = 0L,
    val stillForMs: Long = 0L,
)

class SleepOnsetDetector(val config: DetectorConfig) {

    private var startedAtMs: Long = 0L
    private var lastMovementMs: Long = 0L
    private var hrLowSinceMs: Long? = null
    private var lastAlarmAtMs: Long? = null
    private var lastHr: Double? = null
    private val baseline = ArrayDeque<Pair<Long, Double>>()

    fun start(nowMs: Long) {
        startedAtMs = nowMs
        lastMovementMs = nowMs
        hrLowSinceMs = null
        lastAlarmAtMs = null
        lastHr = null
        baseline.clear()
    }

    fun onMotion(nowMs: Long, deviation: Double) {
        if (deviation >= config.motionThreshold) {
            lastMovementMs = nowMs
        }
    }

    fun onHeartRate(nowMs: Long, bpm: Double): DetectorEvent {
        if (bpm < config.minPlausibleHr || bpm > config.maxPlausibleHr) {
            return DetectorEvent.NONE
        }
        lastHr = bpm

        val eligibleForBaseline =
            (nowMs - lastMovementMs) <= config.recentMovementForBaselineMs ||
                baseline.size < config.minBaselineSamples
        if (eligibleForBaseline) {
            baseline.addLast(nowMs to bpm)
        }
        val cutoff = nowMs - config.baselineWindowMs
        while (baseline.size > config.minBaselineSamples && baseline.first().first < cutoff) {
            baseline.removeFirst()
        }

        val currentBaseline = median(baseline.map { it.second })
        if (baseline.size < config.minBaselineSamples || currentBaseline == null) {
            return DetectorEvent.NONE
        }

        if (bpm <= currentBaseline * (1 - config.hrDropFraction)) {
            if (hrLowSinceMs == null) hrLowSinceMs = nowMs
        } else {
            hrLowSinceMs = null
        }

        val still = (nowMs - lastMovementMs) >= config.stillnessMs
        val hrLowSustained = hrLowSinceMs != null &&
            (nowMs - hrLowSinceMs!!) >= config.hrLowSustainMs
        val grace = lastAlarmAtMs != null &&
            (nowMs - lastAlarmAtMs!!) < config.postAlarmGraceMs

        return if (still && hrLowSustained && !grace) {
            fireAlarm(nowMs)
            DetectorEvent.ALARM
        } else {
            DetectorEvent.NONE
        }
    }

    fun onUserActivityAsleep(nowMs: Long): DetectorEvent {
        val grace = lastAlarmAtMs != null &&
            (nowMs - lastAlarmAtMs!!) < config.postAlarmGraceMs
        val warmedUp = (nowMs - startedAtMs) >= 3 * 60_000L
        return if (warmedUp && !grace) {
            fireAlarm(nowMs)
            DetectorEvent.ALARM
        } else {
            DetectorEvent.NONE
        }
    }

    fun snapshot(nowMs: Long): DetectorSnapshot {
        val currentBaseline =
            if (baseline.size < config.minBaselineSamples) null else median(baseline.map { it.second })
        val hrLowForMs = hrLowSinceMs?.let { nowMs - it } ?: 0L
        val stillForMs = (nowMs - lastMovementMs).coerceAtLeast(0L)
        return DetectorSnapshot(
            currentHr = lastHr,
            baselineHr = currentBaseline,
            hrLowForMs = hrLowForMs,
            stillForMs = stillForMs,
        )
    }

    private fun fireAlarm(nowMs: Long) {
        lastAlarmAtMs = nowMs
        hrLowSinceMs = null
        lastMovementMs = nowMs
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }
}
