package com.samibi.stayawake.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.samibi.stayawake.monitor.Sensitivity
import com.samibi.stayawake.schedule.ScheduleWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store = appContext.dataStore

    val sensitivity: Flow<Sensitivity> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[KEY_SENSITIVITY]
            if (raw == null) {
                Sensitivity.MEDIUM
            } else {
                try {
                    Sensitivity.valueOf(raw)
                } catch (e: IllegalArgumentException) {
                    Sensitivity.MEDIUM
                }
            }
        }

    val windows: Flow<List<ScheduleWindow>> = store.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> deserializeWindows(prefs[KEY_WINDOWS].orEmpty()) }

    suspend fun setSensitivity(s: Sensitivity) {
        store.edit { prefs -> prefs[KEY_SENSITIVITY] = s.name }
    }

    suspend fun setWindows(windows: List<ScheduleWindow>) {
        store.edit { prefs -> prefs[KEY_WINDOWS] = serializeWindows(windows) }
    }

    suspend fun currentSensitivity(): Sensitivity = sensitivity.first()

    suspend fun currentWindows(): List<ScheduleWindow> = windows.first()

    companion object {
        private val KEY_SENSITIVITY = stringPreferencesKey("sensitivity")
        private val KEY_WINDOWS = stringPreferencesKey("windows")

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
        }

        internal fun serializeWindows(windows: List<ScheduleWindow>): String =
            windows.joinToString(";") { w ->
                val daysStr = w.days.sorted().joinToString("-")
                "${w.id},$daysStr,${w.startMin},${w.endMin},${if (w.enabled) 1 else 0}"
            }

        internal fun deserializeWindows(raw: String): List<ScheduleWindow> {
            if (raw.isEmpty()) return emptyList()
            val result = mutableListOf<ScheduleWindow>()
            for (entry in raw.split(";")) {
                if (entry.isEmpty()) continue
                try {
                    val parts = entry.split(",")
                    if (parts.size != 5) continue
                    val id = parts[0].toInt()
                    val days = if (parts[1].isEmpty()) {
                        emptySet()
                    } else {
                        parts[1].split("-").map { it.toInt() }.toSet()
                    }
                    val startMin = parts[2].toInt()
                    val endMin = parts[3].toInt()
                    val enabled = parts[4] == "1"
                    result.add(ScheduleWindow(id, days, startMin, endMin, enabled))
                } catch (e: Exception) {
                    // skip malformed entry
                }
            }
            return result
        }
    }
}
