package com.samibi.stayawake.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.samibi.stayawake.data.SettingsStore
import com.samibi.stayawake.monitor.Sensitivity
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember(context) { SettingsStore.get(context) }
    val current by store.sensitivity.collectAsStateWithLifecycle(initialValue = Sensitivity.MEDIUM)
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
            item {
                Text(text = "Sensitivity", style = MaterialTheme.typography.title3)
            }
            item {
                SensitivityChip(
                    label = "Low",
                    secondaryLabel = "Fewer false alarms",
                    checked = current == Sensitivity.LOW,
                    onClick = { scope.launch { store.setSensitivity(Sensitivity.LOW) } },
                )
            }
            item {
                SensitivityChip(
                    label = "Medium",
                    secondaryLabel = "Balanced",
                    checked = current == Sensitivity.MEDIUM,
                    onClick = { scope.launch { store.setSensitivity(Sensitivity.MEDIUM) } },
                )
            }
            item {
                SensitivityChip(
                    label = "High",
                    secondaryLabel = "Earliest warning",
                    checked = current == Sensitivity.HIGH,
                    onClick = { scope.launch { store.setSensitivity(Sensitivity.HIGH) } },
                )
            }
            item {
                Chip(
                    label = { Text("Schedule") },
                    onClick = { navController.navigate("schedule") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SensitivityChip(
    label: String,
    secondaryLabel: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    ToggleChip(
        checked = checked,
        onCheckedChange = { onClick() },
        label = { Text(label) },
        secondaryLabel = { Text(secondaryLabel) },
        toggleControl = {
            Icon(
                imageVector = ToggleChipDefaults.radioIcon(checked),
                contentDescription = null,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
