package com.samibi.stayawake.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.samibi.stayawake.monitor.MonitorState
import com.samibi.stayawake.monitor.MonitoringService

private const val BODY_SENSORS_BACKGROUND = "android.permission.BODY_SENSORS_BACKGROUND"
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

@Composable
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val snapshot by MonitorState.flow.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    // Bumped after any permission-result callback so the granted-state checks below re-run.
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val bodySensorsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BODY_SENSORS,
        ) == PackageManager.PERMISSION_GRANTED
        if (bodySensorsGranted) MonitoringService.startManual(context)
        refreshTrigger++
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> refreshTrigger++ }

    fun startFlow() {
        val required = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(POST_NOTIFICATIONS)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            MonitoringService.startManual(context)
        }
    }

    val bodySensorsGranted = remember(refreshTrigger) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val backgroundGranted = remember(refreshTrigger) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, BODY_SENSORS_BACKGROUND) ==
            PackageManager.PERMISSION_GRANTED
    }
    val showBackgroundChip = Build.VERSION.SDK_INT >= 33 &&
        !backgroundGranted &&
        (snapshot.running || bodySensorsGranted)

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
        ) {
            item {
                Text(text = "StayAwake", style = MaterialTheme.typography.title3)
            }
            if (snapshot.running) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "HR: ${snapshot.currentHr?.toInt() ?: "--"} bpm")
                        Text(text = "Baseline: ${snapshot.baselineHr?.toInt() ?: "--"} bpm")
                        Text(text = "Still for: ${snapshot.stillForSec}s")
                        Text(text = "Low HR for: ${snapshot.hrLowForSec}s")
                    }
                }
                if (snapshot.fastBatchingSupported == false) {
                    item {
                        Text(
                            text = "5s HR batching unsupported — updates may be slower",
                            style = MaterialTheme.typography.caption3,
                        )
                    }
                }
                if (snapshot.error != null) {
                    item {
                        Text(
                            text = snapshot.error.orEmpty(),
                            style = MaterialTheme.typography.caption3,
                            color = MaterialTheme.colors.error,
                        )
                    }
                }
            }
            item {
                Chip(
                    label = { Text(if (!snapshot.running) "Start monitoring" else "Stop") },
                    onClick = {
                        if (!snapshot.running) startFlow() else MonitoringService.stop(context)
                    },
                    colors = if (!snapshot.running) {
                        ChipDefaults.primaryChipColors()
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showBackgroundChip) {
                item {
                    CompactChip(
                        label = { Text("Allow background sensing") },
                        onClick = { backgroundLauncher.launch(BODY_SENSORS_BACKGROUND) },
                    )
                }
            }
            item {
                Chip(
                    label = { Text("Settings") },
                    onClick = { navController.navigate("settings") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
