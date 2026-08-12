package com.samibi.stayawake.monitor

data class DetectorConfig(
    val baselineWindowMs: Long = 15 * 60_000L,
    val minBaselineSamples: Int = 12,
    val hrDropFraction: Double = 0.10,
    val hrLowSustainMs: Long = 75_000L,
    val stillnessMs: Long = 120_000L,
    val motionThreshold: Double = 0.08,
    val recentMovementForBaselineMs: Long = 120_000L,
    val postAlarmGraceMs: Long = 90_000L,
    val minPlausibleHr: Double = 30.0,
    val maxPlausibleHr: Double = 220.0,
) {
    companion object {
        fun forSensitivity(s: Sensitivity): DetectorConfig = when (s) {
            Sensitivity.LOW -> DetectorConfig(
                hrDropFraction = 0.13,
                hrLowSustainMs = 120_000L,
                stillnessMs = 180_000L,
            )
            Sensitivity.MEDIUM -> DetectorConfig()
            Sensitivity.HIGH -> DetectorConfig(
                hrDropFraction = 0.08,
                hrLowSustainMs = 60_000L,
                stillnessMs = 90_000L,
            )
        }
    }
}
