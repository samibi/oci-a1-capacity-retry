package com.samibi.stayawake.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.samibi.stayawake.data.SettingsStore
import com.samibi.stayawake.schedule.ScheduleAlarms
import com.samibi.stayawake.schedule.ScheduleWindow
import kotlinx.coroutines.launch

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun ScheduleScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember(context) { SettingsStore.get(context) }
    val windows by store.windows.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ScheduleWindow?>(null) }

    suspend fun save(newList: List<ScheduleWindow>) {
        store.setWindows(newList)
        ScheduleAlarms.rearm(context, startIfInsideWindow = false)
    }

    val current = editing
    if (current != null) {
        EditWindowView(
            window = current,
            onSave = { updated ->
                scope.launch {
                    val exists = windows.any { it.id == updated.id }
                    val newList = if (exists) {
                        windows.map { if (it.id == updated.id) updated else it }
                    } else {
                        windows + updated
                    }
                    save(newList)
                    editing = null
                }
            },
            onCancel = { editing = null },
        )
        return
    }

    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
            item {
                Text(text = "Schedule", style = MaterialTheme.typography.title3)
            }
            if (!ScheduleAlarms.canUseExactAlarms(context)) {
                item {
                    Chip(
                        label = { Text("Allow exact alarms") },
                        onClick = { context.startActivity(ScheduleAlarms.requestExactAlarmSettingsIntent()) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(windows, key = { it.id }) { w ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    ToggleChip(
                        checked = w.enabled,
                        onCheckedChange = { checked ->
                            scope.launch {
                                save(windows.map { if (it.id == w.id) it.copy(enabled = checked) else it })
                            }
                        },
                        label = {
                            Text(
                                "%02d:%02d–%02d:%02d".format(
                                    w.startMin / 60,
                                    w.startMin % 60,
                                    w.endMin / 60,
                                    w.endMin % 60,
                                ),
                            )
                        },
                        secondaryLabel = { Text(w.days.sorted().joinToString(" ") { DAY_NAMES[it - 1] }) },
                        toggleControl = {
                            Icon(imageVector = ToggleChipDefaults.switchIcon(w.enabled), contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        CompactChip(
                            label = { Text("Edit") },
                            onClick = { editing = w },
                            modifier = Modifier.weight(1f),
                        )
                        CompactChip(
                            label = { Text("Delete") },
                            onClick = { scope.launch { save(windows.filter { it.id != w.id }) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                Chip(
                    label = { Text("Add window") },
                    onClick = {
                        val nextId = (windows.maxOfOrNull { it.id } ?: 0) + 1
                        editing = ScheduleWindow(
                            id = nextId,
                            days = setOf(1, 2, 3, 4, 5),
                            startMin = 14 * 60,
                            endMin = 16 * 60,
                            enabled = true,
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EditWindowView(
    window: ScheduleWindow,
    onSave: (ScheduleWindow) -> Unit,
    onCancel: () -> Unit,
) {
    var days by remember(window) { mutableStateOf(window.days) }
    var startMin by remember(window) { mutableStateOf(window.startMin) }
    var endMin by remember(window) { mutableStateOf(window.endMin) }
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
            items(7) { idx ->
                val iso = idx + 1
                val checked = iso in days
                ToggleChip(
                    checked = checked,
                    onCheckedChange = { isChecked ->
                        days = if (isChecked) days + iso else days - iso
                    },
                    label = { Text(DAY_NAMES[idx]) },
                    toggleControl = {
                        Icon(imageVector = ToggleChipDefaults.checkboxIcon(checked), contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { startMin = ((startMin - 15) % 1440 + 1440) % 1440 }) { Text("-15") }
                    Text("Start %02d:%02d".format(startMin / 60, startMin % 60))
                    Button(onClick = { startMin = (startMin + 15) % 1440 }) { Text("+15") }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { endMin = ((endMin - 15) % 1440 + 1440) % 1440 }) { Text("-15") }
                    Text("End %02d:%02d".format(endMin / 60, endMin % 60))
                    Button(onClick = { endMin = (endMin + 15) % 1440 }) { Text("+15") }
                }
            }
            item {
                Chip(
                    label = { Text("Save") },
                    onClick = { onSave(window.copy(days = days, startMin = startMin, endMin = endMin)) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                CompactChip(label = { Text("Cancel") }, onClick = onCancel)
            }
        }
    }
}
