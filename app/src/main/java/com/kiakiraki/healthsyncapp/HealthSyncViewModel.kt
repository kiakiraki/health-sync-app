package com.kiakiraki.healthsyncapp

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiakiraki.healthsyncapp.api.ApiException
import com.kiakiraki.healthsyncapp.api.HealthSyncApiClient
import com.kiakiraki.healthsyncapp.health.HealthConnectManager
import com.kiakiraki.healthsyncapp.health.HealthConnectState
import com.kiakiraki.healthsyncapp.health.MealSyncState
import com.kiakiraki.healthsyncapp.health.SyncState
import com.kiakiraki.healthsyncapp.health.SyncStatusStore
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds screen state and sync operations so they survive configuration
 * changes; previously a rotation dropped all state and cancelled any
 * running sync.
 */
class HealthSyncViewModel(application: Application) : AndroidViewModel(application) {

    val healthConnectManager = HealthConnectManager(application)
    private val apiClient = HealthSyncApiClient()
    private val syncStatusStore = SyncStatusStore(application)

    private val _state = MutableStateFlow<HealthConnectState>(HealthConnectState.Loading)
    val state: StateFlow<HealthConnectState> = _state.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _mealSyncState = MutableStateFlow<MealSyncState>(MealSyncState.Idle)
    val mealSyncState: StateFlow<MealSyncState> = _mealSyncState.asStateFlow()

    /**
     * Set when the UI should show the Health Connect permission dialog
     * (existing installs that lack the optional background-read
     * permission). The UI launches the request and calls
     * [onPermissionRequestLaunched] to clear it.
     */
    private val _permissionRequest = MutableStateFlow<Set<String>?>(null)
    val permissionRequest: StateFlow<Set<String>?> = _permissionRequest.asStateFlow()

    private val _lastCloudSyncAt = MutableStateFlow(syncStatusStore.lastCloudSyncAt)
    val lastCloudSyncAt: StateFlow<Instant?> = _lastCloudSyncAt.asStateFlow()

    private val _lastMealSyncAt = MutableStateFlow(syncStatusStore.lastMealSyncAt)
    val lastMealSyncAt: StateFlow<Instant?> = _lastMealSyncAt.asStateFlow()

    // Picks up timestamps written by HealthSyncWorker while the screen is
    // open. Must stay a field: SharedPreferences holds listeners weakly.
    private val syncStatusListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            _lastCloudSyncAt.value = syncStatusStore.lastCloudSyncAt
            _lastMealSyncAt.value = syncStatusStore.lastMealSyncAt
        }

    init {
        syncStatusStore.registerListener(syncStatusListener)
        initialize()
    }

    private fun initialize() {
        viewModelScope.launch {
            if (!healthConnectManager.isAvailable()) {
                _state.value = HealthConnectState.NotSupported
                return@launch
            }

            if (healthConnectManager.hasAllPermissions()) {
                if (!healthConnectManager.hasBackgroundReadPermission()) {
                    _permissionRequest.value = HealthConnectManager.PERMISSIONS_WITH_BACKGROUND_READ
                } else {
                    loadHealthData()
                }
            } else {
                _state.value = HealthConnectState.PermissionsRequired
            }
        }
    }

    fun onPermissionRequestLaunched() {
        _permissionRequest.value = null
    }

    fun onPermissionResult() {
        viewModelScope.launch {
            // Re-check granted permissions instead of inspecting the launcher
            // result: the request may have included only the optional
            // background-read permission, whose denial must not block the UI.
            if (healthConnectManager.hasAllPermissions()) {
                loadHealthData()
            } else {
                _state.value = HealthConnectState.PermissionsRequired
            }
        }
    }

    fun refresh() {
        _state.value = HealthConnectState.Loading
        viewModelScope.launch { loadHealthData() }
    }

    private suspend fun loadHealthData() {
        try {
            val summary = healthConnectManager.readHealthSummary()
            _state.value = HealthConnectState.Success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = HealthConnectState.Error(e.message ?: "Unknown error occurred")
        }
    }

    fun syncToCloud() {
        if (_syncState.value is SyncState.Syncing) return
        _syncState.value = SyncState.Syncing
        viewModelScope.launch {
            try {
                val request = HealthSyncApiClient.buildSyncRequest(
                    weightRecords = healthConnectManager.readWeightRecords(30),
                    bodyFatRecords = healthConnectManager.readBodyFatRecords(30),
                    bloodPressureRecords = healthConnectManager.readBloodPressureRecords(30),
                    heartRateRecords = healthConnectManager.readHeartRateRecords(7),
                    sleepRecords = healthConnectManager.readSleepRecords(7),
                    stepsRecords = healthConnectManager.readStepsRecords(7)
                )

                apiClient.syncHealthData(request).fold(
                    onSuccess = {
                        syncStatusStore.lastCloudSyncAt = Instant.now()
                        _lastCloudSyncAt.value = syncStatusStore.lastCloudSyncAt
                        _syncState.value = SyncState.Success
                    },
                    onFailure = { e ->
                        Log.e("HealthSync", "Sync failed", e)
                        val details = (e as? ApiException)?.responseBody
                        if (details != null) {
                            Log.e("HealthSync", "Response body: $details")
                        }
                        _syncState.value = SyncState.Error(e.message ?: "Unknown error occurred", details)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthSync", "Sync failed", e)
                _syncState.value = SyncState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    fun syncMeals() {
        if (_mealSyncState.value is MealSyncState.Syncing) return
        _mealSyncState.value = MealSyncState.Syncing
        viewModelScope.launch {
            try {
                apiClient.fetchMeals(days = 7).fold(
                    onSuccess = { meals ->
                        Log.d("HealthSync", "Fetched ${meals.size} meals from API")
                        val (written, skipped) = healthConnectManager.writeNutritionRecords(meals)
                        Log.d("HealthSync", "Meal sync complete: $written written, $skipped skipped")
                        syncStatusStore.lastMealSyncAt = Instant.now()
                        _lastMealSyncAt.value = syncStatusStore.lastMealSyncAt
                        _mealSyncState.value = MealSyncState.Success(written, skipped)
                    },
                    onFailure = { e ->
                        Log.e("HealthSync", "Meal sync failed", e)
                        _mealSyncState.value = MealSyncState.Error(e.message ?: "Unknown error occurred")
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HealthSync", "Meal sync failed", e)
                _mealSyncState.value = MealSyncState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    override fun onCleared() {
        syncStatusStore.unregisterListener(syncStatusListener)
        apiClient.close()
    }
}
