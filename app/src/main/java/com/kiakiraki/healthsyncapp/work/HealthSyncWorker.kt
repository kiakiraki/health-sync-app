package com.kiakiraki.healthsyncapp.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kiakiraki.healthsyncapp.api.HealthSyncApiClient
import com.kiakiraki.healthsyncapp.health.HealthConnectManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Periodically uploads health data to the cloud and writes meals from the
 * API into Health Connect, mirroring what the manual sync buttons do.
 *
 * Reading Health Connect data without a foreground activity requires the
 * runtime permission READ_HEALTH_DATA_IN_BACKGROUND; when it (or any core
 * permission) is missing the worker skips silently rather than failing, so
 * WorkManager does not retry a run that can never succeed.
 */
class HealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val healthConnectManager = HealthConnectManager(applicationContext)

        if (!healthConnectManager.isAvailable()) {
            Log.d(TAG, "Background sync skipped: Health Connect is not available")
            return Result.success()
        }
        if (!healthConnectManager.hasAllPermissions() ||
            !healthConnectManager.hasBackgroundReadPermission()
        ) {
            Log.d(TAG, "Background sync skipped: permissions not granted")
            return Result.success()
        }

        val apiClient = HealthSyncApiClient()
        return try {
            val request = HealthSyncApiClient.buildSyncRequest(
                weightRecords = healthConnectManager.readWeightRecords(30),
                bodyFatRecords = healthConnectManager.readBodyFatRecords(30),
                bloodPressureRecords = healthConnectManager.readBloodPressureRecords(30),
                heartRateRecords = healthConnectManager.readHeartRateRecords(7),
                sleepRecords = healthConnectManager.readSleepRecords(7),
                stepsRecords = healthConnectManager.readStepsRecords(7)
            )
            apiClient.syncHealthData(request).getOrThrow()

            val meals = apiClient.fetchMeals(days = 7).getOrThrow()
            val (written, skipped) = healthConnectManager.writeNutritionRecords(meals)

            Log.d(TAG, "Background sync complete: health data uploaded, meals $written written / $skipped skipped")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } finally {
            apiClient.close()
        }
    }

    companion object {
        private const val TAG = "HealthSync"
        private const val WORK_NAME = "health-sync-periodic"
        private const val MAX_RETRIES = 3

        /**
         * Enqueues the periodic sync, replacing the schedule if parameters
         * change. Safe to call on every app start.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
