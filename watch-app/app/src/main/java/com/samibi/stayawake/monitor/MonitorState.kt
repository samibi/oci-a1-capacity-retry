package com.samibi.stayawake.monitor

import kotlinx.coroutines.flow.MutableStateFlow

enum class StartReason { MANUAL, SCHEDULED }

data class MonitorSnapshot(
    val running: Boolean = false,
    val startReason: StartReason = StartReason.MANUAL,
    val alarmActive: Boolean = false,
    val currentHr: Double? = null,
    val baselineHr: Double? = null,
    val hrLowForSec: Long = 0L,
    val stillForSec: Long = 0L,
    val fastBatchingSupported: Boolean? = null,
    val error: String? = null,
)

object MonitorState {
    val flow = MutableStateFlow(MonitorSnapshot())
}
