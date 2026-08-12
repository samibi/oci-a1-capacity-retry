package com.samibi.stayawake.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulated-time tests for SleepOnsetDetector against MEDIUM (default) DetectorConfig:
 * baselineWindowMs=15min, minBaselineSamples=12, hrDropFraction=0.10 (threshold 64.8 for
 * baseline 72), hrLowSustainMs=75s, stillnessMs=120s, motionThreshold=0.08,
 * recentMovementForBaselineMs=120s, postAlarmGraceMs=90s.
 */
class SleepOnsetDetectorTest {

    private val t0 = 1_000_000L
    private val step = 5_000L

    /**
     * Feeds 12 minutes (144 samples, 5s apart) of steady motion (deviation 0.2) and HR=72,
     * which forms a stable baseline of 72 and leaves lastMovementMs at the timestamp of the
     * last sample. Returns that timestamp (T1).
     */
    private fun formBaseline(detector: SleepOnsetDetector, start: Long): Long {
        var last = start
        for (i in 0 until 144) {
            val t = start + i * step
            detector.onMotion(t, 0.2)
            detector.onHeartRate(t, 72.0)
            last = t
        }
        return last
    }

    @Test
    fun warmupNoAlarm() {
        val detector = SleepOnsetDetector(DetectorConfig())
        detector.start(t0)
        // still (no motion calls at all) + low HR immediately, but fewer than minBaselineSamples.
        for (i in 0 until 5) {
            val t = t0 + i * step
            val event = detector.onHeartRate(t, 55.0)
            assertEquals(DetectorEvent.NONE, event)
        }
        val snap = detector.snapshot(t0 + 5 * step)
        assertEquals(null, snap.baselineHr)
    }

    @Test
    fun gradualSleepOnsetTriggersAlarm() {
        val detector = SleepOnsetDetector(DetectorConfig())
        detector.start(t0)
        val t1 = formBaseline(detector, t0)

        var alarmCount = 0
        var alarmJ = -1
        for (j in 1..60) {
            val t = t1 + step * j
            detector.onMotion(t, 0.0) // motion stops
            val bpm = if (j <= 36) 72.0 - 10.0 * j / 36.0 else 62.0 // ramps 72->62 over 3min, then holds
            val event = detector.onHeartRate(t, bpm)
            if (event == DetectorEvent.ALARM) {
                alarmCount++
                if (alarmJ == -1) alarmJ = j
            } else {
                // Must not fire before both stillnessMs (ready at j=24) and hrLowSustainMs
                // (ready at j=41, the later of the two) have elapsed.
                assertTrue("unexpected timing at j=$j", j < 41 || alarmCount > 0)
            }
        }
        assertEquals("expected exactly one alarm", 1, alarmCount)
        assertEquals("alarm should fire once both stillness and hr-low-sustain thresholds are met", 41, alarmJ)
    }

    @Test
    fun briefDipNoAlarm() {
        val detector = SleepOnsetDetector(DetectorConfig())
        detector.start(t0)
        val t1 = formBaseline(detector, t0)

        for (j in 1..120) { // 10 minutes
            val t = t1 + step * j
            detector.onMotion(t, 0.0)
            val bpm = if (j in 1..6) 62.0 else 71.0 // ~30s dip, then back to normal
            val event = detector.onHeartRate(t, bpm)
            assertEquals("no alarm expected for a brief dip (j=$j)", DetectorEvent.NONE, event)
        }
    }

    @Test
    fun stillButNormalHrNoAlarm() {
        val detector = SleepOnsetDetector(DetectorConfig())
        detector.start(t0)
        val t1 = formBaseline(detector, t0)

        for (j in 1..240) { // 20 minutes, no motion, HR stays 70-72
            val t = t1 + step * j
            detector.onMotion(t, 0.0)
            val bpm = if (j % 2 == 0) 70.0 else 72.0
            val event = detector.onHeartRate(t, bpm)
            assertEquals("no alarm expected when HR stays normal (j=$j)", DetectorEvent.NONE, event)
        }
    }

    @Test
    fun movementResetsStillness() {
        val detector = SleepOnsetDetector(DetectorConfig())
        detector.start(t0)
        val t1 = formBaseline(detector, t0)

        for (j in 1..120) { // 10 minutes; HR low throughout, motion every 60s
            val t = t1 + step * j
            val deviation = if (j % 12 == 0) 0.3 else 0.0
            detector.onMotion(t, deviation)
            val event = detector.onHeartRate(t, 62.0)
            assertEquals(
                "stillness should never reach 120s since motion recurs every 60s (j=$j)",
                DetectorEvent.NONE,
                event,
            )
        }
    }

    @Test
    fun graceSuppresssesSecondAlarm() {
        val detector = SleepOnsetDetector(DetectorConfig())
        detector.start(t0)
        val t1 = formBaseline(detector, t0)

        var firstAlarmJ = -1
        var secondAlarmJ = -1
        for (j in 1..50) {
            val t = t1 + step * j
            detector.onMotion(t, 0.0)
            val event = detector.onHeartRate(t, 62.0) // stays low throughout
            if (event == DetectorEvent.ALARM) {
                if (firstAlarmJ == -1) {
                    firstAlarmJ = j
                } else if (secondAlarmJ == -1) {
                    secondAlarmJ = j
                }
            }
        }
        assertEquals("first alarm should fire once still+sustained thresholds met", 24, firstAlarmJ)
        assertTrue("second alarm must not occur within the postAlarmGraceMs window", secondAlarmJ > firstAlarmJ + 18)
        assertEquals(
            "second alarm fires once grace has passed and stillness/hr-low re-accumulate",
            48,
            secondAlarmJ,
        )
    }

    @Test
    fun passiveBackstopFires() {
        val detector = SleepOnsetDetector(DetectorConfig())
        detector.start(t0)

        // Too soon after start: passive backstop must not fire even though grace is clear.
        assertEquals(DetectorEvent.NONE, detector.onUserActivityAsleep(t0 + 60_000L))

        // 5 minutes of baseline formation.
        for (i in 0 until 60) {
            val t = t0 + i * step
            detector.onMotion(t, 0.2)
            detector.onHeartRate(t, 72.0)
        }

        val t5 = t0 + 5 * 60_000L
        assertEquals(DetectorEvent.ALARM, detector.onUserActivityAsleep(t5))
    }

    @Test
    fun implausibleHrIgnored() {
        val detector = SleepOnsetDetector(DetectorConfig())
        detector.start(t0)

        var lastT = t0
        for (i in 0 until 16) {
            val t = t0 + i * step
            lastT = t
            when (i) {
                5 -> assertEquals(DetectorEvent.NONE, detector.onHeartRate(t, 250.0))
                10 -> assertEquals(DetectorEvent.NONE, detector.onHeartRate(t, 10.0))
                else -> {
                    detector.onMotion(t, 0.2)
                    detector.onHeartRate(t, 72.0)
                }
            }
        }

        val snap = detector.snapshot(lastT)
        assertTrue("baseline should stay close to 72 despite implausible readings", snap.baselineHr != null)
        assertTrue(snap.baselineHr!! in 70.0..74.0)
        assertEquals(72.0, snap.currentHr)
    }
}
