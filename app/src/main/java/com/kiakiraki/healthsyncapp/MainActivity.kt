package com.kiakiraki.healthsyncapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kiakiraki.healthsyncapp.api.HealthSyncApiClient
import com.kiakiraki.healthsyncapp.health.HealthConnectManager
import com.kiakiraki.healthsyncapp.health.HealthConnectState
import com.kiakiraki.healthsyncapp.health.HealthSummary
import com.kiakiraki.healthsyncapp.health.SyncState
import com.kiakiraki.healthsyncapp.ui.theme.HealthSyncAppTheme
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var apiClient: HealthSyncApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthConnectManager = HealthConnectManager(this)
        apiClient = HealthSyncApiClient()
        enableEdgeToEdge()
        setContent {
            HealthSyncAppTheme {
                HealthSyncScreen(healthConnectManager, apiClient)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::apiClient.isInitialized) {
            apiClient.close()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSyncScreen(healthConnectManager: HealthConnectManager, apiClient: HealthSyncApiClient) {
    var state by remember { mutableStateOf<HealthConnectState>(HealthConnectState.Loading) }
    var syncState by remember { mutableStateOf<SyncState>(SyncState.Idle) }
    val coroutineScope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = healthConnectManager.createPermissionRequestContract()
    ) { grantedPermissions ->
        coroutineScope.launch {
            if (grantedPermissions.containsAll(HealthConnectManager.PERMISSIONS)) {
                loadHealthData(healthConnectManager) { state = it }
            } else {
                state = HealthConnectState.PermissionsRequired
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!healthConnectManager.isAvailable()) {
            state = HealthConnectState.NotSupported
            return@LaunchedEffect
        }

        if (healthConnectManager.hasAllPermissions()) {
            loadHealthData(healthConnectManager) { state = it }
        } else {
            state = HealthConnectState.PermissionsRequired
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Health Sync") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val currentState = state) {
                is HealthConnectState.Loading -> {
                    Spacer(modifier = Modifier.height(32.dp))
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading health data...")
                }

                is HealthConnectState.NotSupported -> {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = "Health Connect is not available on this device.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Please install Health Connect from Google Play Store.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is HealthConnectState.PermissionsRequired -> {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = "Health Connect permissions are required to display your health data.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
                        }
                    ) {
                        Text("Grant Permissions")
                    }
                }

                is HealthConnectState.Success -> {
                    HealthDataDisplay(
                        summary = currentState.summary,
                        syncState = syncState,
                        onRefresh = {
                            coroutineScope.launch {
                                state = HealthConnectState.Loading
                                loadHealthData(healthConnectManager) { state = it }
                            }
                        },
                        onSync = {
                            coroutineScope.launch {
                                syncState = SyncState.Syncing
                                syncHealthData(healthConnectManager, apiClient) { syncState = it }
                            }
                        }
                    )
                }

                is HealthConnectState.Error -> {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = "Error: ${currentState.message}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                state = HealthConnectState.Loading
                                loadHealthData(healthConnectManager) { state = it }
                            }
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
fun HealthDataDisplay(
    summary: HealthSummary,
    syncState: SyncState,
    onRefresh: () -> Unit,
    onSync: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Last Updated
        summary.lastUpdated?.let { lastUpdated ->
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
                .withZone(ZoneId.systemDefault())
            Text(
                text = "Last updated: ${formatter.format(lastUpdated)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Weight Card
        HealthCard(title = "Weight") {
            if (summary.latestWeightKg != null) {
                HealthDataRow(label = "Latest", value = String.format("%.1f kg", summary.latestWeightKg))
            } else {
                Text("No data available", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Body Fat Card
        HealthCard(title = "Body Fat") {
            if (summary.latestBodyFatPercent != null) {
                HealthDataRow(label = "Latest", value = String.format("%.1f %%", summary.latestBodyFatPercent))
            } else {
                Text("No data available", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Blood Pressure Card
        HealthCard(title = "Blood Pressure") {
            if (summary.latestSystolicMmHg != null && summary.latestDiastolicMmHg != null) {
                HealthDataRow(
                    label = "Latest",
                    value = "${summary.latestSystolicMmHg.toInt()}/${summary.latestDiastolicMmHg.toInt()} mmHg"
                )
            } else {
                Text("No data available", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Heart Rate Card
        HealthCard(title = "Heart Rate") {
            if (summary.latestHeartRateBpm != null) {
                HealthDataRow(label = "Latest", value = "${summary.latestHeartRateBpm} bpm")
            } else {
                Text("No data available", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Steps Card
        HealthCard(title = "Steps (Last 7 days)") {
            if (summary.totalStepsLast7Days != null) {
                HealthDataRow(label = "Total", value = "${summary.totalStepsLast7Days} steps")
                val avgPerDay = summary.totalStepsLast7Days / 7
                HealthDataRow(label = "Daily avg", value = "$avgPerDay steps")
            } else {
                Text("No data available", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Sleep Card
        HealthCard(title = "Sleep (Last 7 days)") {
            if (summary.totalSleepMinutesLast7Days != null) {
                val totalHours = summary.totalSleepMinutesLast7Days / 60
                val totalMinutes = summary.totalSleepMinutesLast7Days % 60
                HealthDataRow(label = "Total", value = "${totalHours}h ${totalMinutes}m")
                val avgMinutesPerDay = summary.totalSleepMinutesLast7Days / 7
                val avgHours = avgMinutesPerDay / 60
                val avgMinutes = avgMinutesPerDay % 60
                HealthDataRow(label = "Daily avg", value = "${avgHours}h ${avgMinutes}m")
            } else {
                Text("No data available", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Refresh Button
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh Data")
        }

        // Sync to Cloud Button
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onSync,
            modifier = Modifier.fillMaxWidth(),
            enabled = syncState !is SyncState.Syncing
        ) {
            when (syncState) {
                is SyncState.Syncing -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(20.dp)
                            .width(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text("Syncing...")
                }
                else -> Text("Sync to Cloud")
            }
        }

        // Sync status feedback
        when (syncState) {
            is SyncState.Success -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sync completed successfully!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is SyncState.Error -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sync failed: ${syncState.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {}
        }
    }
}

@Composable
fun HealthCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun HealthDataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private suspend fun loadHealthData(
    healthConnectManager: HealthConnectManager,
    onStateChange: (HealthConnectState) -> Unit
) {
    try {
        val summary = healthConnectManager.readHealthSummary()
        onStateChange(HealthConnectState.Success(summary))
    } catch (e: Exception) {
        onStateChange(HealthConnectState.Error(e.message ?: "Unknown error occurred"))
    }
}

private suspend fun syncHealthData(
    healthConnectManager: HealthConnectManager,
    apiClient: HealthSyncApiClient,
    onStateChange: (SyncState) -> Unit
) {
    try {
        val weightRecords = healthConnectManager.readWeightRecords(30)
        val bodyFatRecords = healthConnectManager.readBodyFatRecords(30)
        val bloodPressureRecords = healthConnectManager.readBloodPressureRecords(30)
        val heartRateRecords = healthConnectManager.readHeartRateRecords(7)
        val sleepRecords = healthConnectManager.readSleepRecords(7)
        val stepsRecords = healthConnectManager.readStepsRecords(7)

        val request = HealthSyncApiClient.buildSyncRequest(
            weightRecords = weightRecords,
            bodyFatRecords = bodyFatRecords,
            bloodPressureRecords = bloodPressureRecords,
            heartRateRecords = heartRateRecords,
            sleepRecords = sleepRecords,
            stepsRecords = stepsRecords
        )

        val result = apiClient.syncHealthData(request)
        result.fold(
            onSuccess = { onStateChange(SyncState.Success) },
            onFailure = { e -> onStateChange(SyncState.Error(e.message ?: "Unknown error occurred")) }
        )
    } catch (e: Exception) {
        onStateChange(SyncState.Error(e.message ?: "Unknown error occurred"))
    }
}
