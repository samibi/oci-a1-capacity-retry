package com.samibi.stayawake.schedule

data class ScheduleWindow(
    val id: Int,
    val days: Set<Int>,      // ISO: 1=Monday .. 7=Sunday
    val startMin: Int,       // minutes since midnight 0..1439
    val endMin: Int,         // minutes since midnight; if endMin <= startMin the window crosses midnight
    val enabled: Boolean,
)
