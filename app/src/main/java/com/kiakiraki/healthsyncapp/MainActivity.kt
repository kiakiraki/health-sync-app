package com.kiakiraki.healthsyncapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kiakiraki.healthsyncapp.health.HealthConnectManager
import com.kiakiraki.healthsyncapp.health.HealthConnectState
import com.kiakiraki.healthsyncapp.health.HealthSummary
import com.kiakiraki.healthsyncapp.health.MealSyncState
import com.kiakiraki.healthsyncapp.health.SyncState
import com.kiakiraki.healthsyncapp.ui.theme.HealthSyncAppTheme
import com.kiakiraki.healthsyncapp.work.HealthSyncWorker
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HealthSyncWorker.schedule(this)
        enableEdgeToEdge()
        setContent {
            HealthSyncAppTheme {
                HealthSyncScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSyncScreen(viewModel: HealthSyncViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val mealSyncState by viewModel.mealSyncState.collectAsState()
    val permissionRequest by viewModel.permissionRequest.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = viewModel.healthConnectManager.createPermissionRequestContract()
    ) { _ ->
        viewModel.onPermissionResult()
    }

    // The ViewModel asks for the permission dialog this way when an existing
    // install lacks the optional background-read permission.
    LaunchedEffect(permissionRequest) {
        permissionRequest?.let {
            viewModel.onPermissionRequestLaunched()
            permissionLauncher.launch(it)
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
                            permissionLauncher.launch(HealthConnectManager.PERMISSIONS_WITH_BACKGROUND_READ)
                        }
                    ) {
                        Text("Grant Permissions")
                    }
                }

                is HealthConnectState.Success -> {
                    HealthDataDisplay(
                        summary = currentState.summary,
                        syncState = syncState,
                        mealSyncState = mealSyncState,
                        onRefresh = { viewModel.refresh() },
                        onSync = { viewModel.syncToCloud() },
                        onMealSync = { viewModel.syncMeals() }
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
                        onClick = { viewModel.refresh() }
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
    mealSyncState: MealSyncState,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onMealSync: () -> Unit
) {
    val context = LocalContext.current

    // Show toast on sync state changes
    LaunchedEffect(syncState) {
        when (syncState) {
            is SyncState.Success -> {
                Toast.makeText(context, "Sync completed successfully!", Toast.LENGTH_SHORT).show()
            }
            is SyncState.Error -> {
                Toast.makeText(context, "Sync failed: ${syncState.message}", Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    // Show toast on meal sync state changes
    LaunchedEffect(mealSyncState) {
        when (mealSyncState) {
            is MealSyncState.Success -> {
                Toast.makeText(
                    context,
                    "Meal sync: ${mealSyncState.written} written, ${mealSyncState.skipped} skipped",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is MealSyncState.Error -> {
                Toast.makeText(context, "Meal sync failed: ${mealSyncState.message}", Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Action buttons (top for easy access)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                Text("Refresh Data")
            }
            Button(
                onClick = onSync,
                modifier = Modifier.weight(1f),
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
        }

        // Meal sync button
        Button(
            onClick = onMealSync,
            modifier = Modifier.fillMaxWidth(),
            enabled = mealSyncState !is MealSyncState.Syncing
        ) {
            when (mealSyncState) {
                is MealSyncState.Syncing -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(20.dp)
                            .width(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text("Syncing Meals...")
                }
                else -> Text("Sync Meals to Health Connect")
            }
        }

        // Copy error details button (only shown on error with details)
        if (syncState is SyncState.Error && syncState.details != null) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Sync Error", syncState.details))
                    Toast.makeText(context, "Error details copied", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Copy Error Details")
            }
        }

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
