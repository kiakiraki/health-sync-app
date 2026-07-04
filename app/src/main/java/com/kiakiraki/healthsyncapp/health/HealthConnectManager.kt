package com.kiakiraki.healthsyncapp.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getWritePermission(NutritionRecord::class)
        )

        /**
         * PERMISSIONS plus background read, which HealthSyncWorker needs to
         * read data without a foreground activity. Requested together, but
         * only PERMISSIONS are mandatory for the app to function.
         */
        val PERMISSIONS_WITH_BACKGROUND_READ =
            PERMISSIONS + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        fun isHealthConnectAvailable(context: Context): Boolean {
            return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }

        /**
         * Returns true if the stages contain at least one detailed sleep stage
         * (light, deep, or REM). Wearables like Pixel Watch provide these,
         * while Nest Hub typically only records "sleeping" (type 2).
         */
        internal fun hasDetailedStages(stages: List<SleepStageRecord>): Boolean {
            val detailedTypes = setOf(4, 5, 6) // light, deep, rem
            return stages.any { it.stage in detailedTypes }
        }

        /**
         * Trims a single sleep stage by removing portions that overlap with
         * the given covered intervals. May return 0, 1, or multiple fragments.
         */
        internal fun trimStageByIntervals(
            stage: SleepStageRecord,
            coveredIntervals: List<SleepStageRecord>
        ): List<SleepStageRecord> {
            val result = mutableListOf<SleepStageRecord>()
            var currentStart = stage.startTime

            for (covered in coveredIntervals) {
                if (covered.endTime <= currentStart) continue
                if (covered.startTime >= stage.endTime) break

                if (covered.startTime > currentStart) {
                    result.add(stage.copy(
                        startTime = currentStart,
                        endTime = minOf(covered.startTime, stage.endTime)
                    ))
                }
                currentStart = maxOf(currentStart, covered.endTime)
            }

            if (currentStart < stage.endTime) {
                result.add(stage.copy(
                    startTime = currentStart,
                    endTime = stage.endTime
                ))
            }

            return result
        }

        /**
         * Merges stages from two sources, prioritizing preferred stages.
         * Fallback stages are trimmed to only cover intervals not covered
         * by any preferred stage.
         */
        internal fun mergeStagesWithPriority(
            preferred: List<SleepStageRecord>,
            fallback: List<SleepStageRecord>
        ): List<SleepStageRecord> {
            val sortedPreferred = preferred.sortedBy { it.startTime }
            val trimmedFallback = fallback.flatMap { stage ->
                trimStageByIntervals(stage, sortedPreferred)
            }
            return (sortedPreferred + trimmedFallback).sortedBy { it.startTime }
        }

        internal fun mergeOverlappingSleepSessions(
            sessions: List<SleepRecord>
        ): List<SleepRecord> {
            if (sessions.isEmpty()) return emptyList()

            val sorted = sessions.sortedBy { it.startTime }
            val merged = mutableListOf<SleepRecord>()

            var current = sorted.first()

            for (session in sorted.drop(1)) {
                if (session.startTime <= current.endTime) {
                    val newStart = if (current.startTime < session.startTime) current.startTime else session.startTime
                    val newEnd = if (current.endTime > session.endTime) current.endTime else session.endTime

                    val currentDetailed = hasDetailedStages(current.stages)
                    val sessionDetailed = hasDetailedStages(session.stages)
                    val mergedStages = when {
                        currentDetailed && !sessionDetailed ->
                            mergeStagesWithPriority(preferred = current.stages, fallback = session.stages)
                        sessionDetailed && !currentDetailed ->
                            mergeStagesWithPriority(preferred = session.stages, fallback = current.stages)
                        else ->
                            (current.stages + session.stages).sortedBy { it.startTime }
                    }

                    current = SleepRecord(
                        durationMinutes = java.time.Duration.between(newStart, newEnd).toMinutes(),
                        startTime = newStart,
                        endTime = newEnd,
                        stages = mergedStages
                    )
                } else {
                    merged.add(current)
                    current = session
                }
            }
            merged.add(current)

            return merged
        }
    }

    fun createPermissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    fun isAvailable(): Boolean = isHealthConnectAvailable(context)

    suspend fun hasAllPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return PERMISSIONS.all { it in granted }
    }

    suspend fun hasBackgroundReadPermission(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
    }

    suspend fun readHealthSummary(): HealthSummary {
        val now = Instant.now()
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        val sevenDaysAgo = now.minus(7, ChronoUnit.DAYS)

        val latestWeight = readLatestWeight(thirtyDaysAgo, now)
        val latestBodyFat = readLatestBodyFat(thirtyDaysAgo, now)
        val latestBloodPressure = readLatestBloodPressure(thirtyDaysAgo, now)
        val latestHeartRate = readLatestHeartRate(sevenDaysAgo, now)
        val totalSteps = aggregateTotalSteps(sevenDaysAgo, now)
        val totalSleep = aggregateTotalSleep(sevenDaysAgo, now)

        return HealthSummary(
            latestWeightKg = latestWeight,
            latestBodyFatPercent = latestBodyFat,
            latestSystolicMmHg = latestBloodPressure?.first,
            latestDiastolicMmHg = latestBloodPressure?.second,
            latestHeartRateBpm = latestHeartRate,
            totalStepsLast7Days = totalSteps,
            totalSleepMinutesLast7Days = totalSleep,
            lastUpdated = now
        )
    }

    private suspend fun readLatestWeight(startTime: Instant, endTime: Instant): Double? {
        return try {
            val request = ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.readRecords(request)
            response.records.maxByOrNull { it.time }?.weight?.inKilograms
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HealthSync", "Failed to read latest weight", e)
            null
        }
    }

    private suspend fun readLatestBodyFat(startTime: Instant, endTime: Instant): Double? {
        return try {
            val request = ReadRecordsRequest(
                recordType = BodyFatRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.readRecords(request)
            response.records.maxByOrNull { it.time }?.percentage?.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HealthSync", "Failed to read latest body fat", e)
            null
        }
    }

    private suspend fun readLatestBloodPressure(startTime: Instant, endTime: Instant): Pair<Double, Double>? {
        return try {
            val request = ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.readRecords(request)
            response.records.maxByOrNull { it.time }?.let {
                Pair(it.systolic.inMillimetersOfMercury, it.diastolic.inMillimetersOfMercury)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HealthSync", "Failed to read latest blood pressure", e)
            null
        }
    }

    private suspend fun readLatestHeartRate(startTime: Instant, endTime: Instant): Long? {
        return try {
            val request = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.readRecords(request)
            response.records.maxByOrNull { it.endTime }?.samples?.lastOrNull()?.beatsPerMinute
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HealthSync", "Failed to read latest heart rate", e)
            null
        }
    }

    private suspend fun aggregateTotalSteps(startTime: Instant, endTime: Instant): Long? {
        return try {
            val request = AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.aggregate(request)
            response[StepsRecord.COUNT_TOTAL]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HealthSync", "Failed to aggregate total steps", e)
            null
        }
    }

    private suspend fun aggregateTotalSleep(startTime: Instant, endTime: Instant): Long? {
        return try {
            val request = AggregateRequest(
                metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            val response = healthConnectClient.aggregate(request)
            response[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HealthSync", "Failed to aggregate total sleep", e)
            null
        }
    }

    suspend fun readWeightRecords(days: Int = 30): List<com.kiakiraki.healthsyncapp.health.WeightRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        val allRecords = mutableListOf<WeightRecord>()
        var pageToken: String? = null

        do {
            val request = ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, now),
                pageToken = pageToken
            )
            val response = healthConnectClient.readRecords(request)
            allRecords.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)

        return allRecords.map {
            com.kiakiraki.healthsyncapp.health.WeightRecord(
                weightKg = it.weight.inKilograms,
                time = it.time
            )
        }
    }

    suspend fun readBloodPressureRecords(days: Int = 30): List<com.kiakiraki.healthsyncapp.health.BloodPressureRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        val allRecords = mutableListOf<BloodPressureRecord>()
        var pageToken: String? = null

        do {
            val request = ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, now),
                pageToken = pageToken
            )
            val response = healthConnectClient.readRecords(request)
            allRecords.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)

        return allRecords.map {
            com.kiakiraki.healthsyncapp.health.BloodPressureRecord(
                systolicMmHg = it.systolic.inMillimetersOfMercury,
                diastolicMmHg = it.diastolic.inMillimetersOfMercury,
                time = it.time
            )
        }
    }

    suspend fun readSleepRecords(days: Int = 7): List<com.kiakiraki.healthsyncapp.health.SleepRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        val allRecords = mutableListOf<SleepSessionRecord>()
        var pageToken: String? = null

        do {
            val request = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, now),
                pageToken = pageToken
            )
            val response = healthConnectClient.readRecords(request)
            allRecords.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)

        val sleepRecords = allRecords.map {
            com.kiakiraki.healthsyncapp.health.SleepRecord(
                durationMinutes = java.time.Duration.between(it.startTime, it.endTime).toMinutes(),
                startTime = it.startTime,
                endTime = it.endTime,
                stages = it.stages.map { stage ->
                    SleepStageRecord(
                        stage = stage.stage,
                        startTime = stage.startTime,
                        endTime = stage.endTime
                    )
                }
            )
        }

        return mergeOverlappingSleepSessions(sleepRecords)
    }

    suspend fun readStepsRecords(days: Int = 7): List<com.kiakiraki.healthsyncapp.health.StepsRecord> {
        val zoneId = ZoneId.systemDefault()
        val now = LocalDateTime.now(zoneId)
        // Buckets must start at midnight: slicing by Period.ofDays(1) from
        // the current time would produce "24h from now" windows that split
        // each calendar day's steps across two adjacent buckets.
        val startTime = now.toLocalDate().minusDays(days - 1L).atStartOfDay()

        val request = AggregateGroupByPeriodRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(startTime, now),
            timeRangeSlicer = Period.ofDays(1)
        )
        val response = healthConnectClient.aggregateGroupByPeriod(request)

        return response.mapNotNull { result ->
            val count = result.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            com.kiakiraki.healthsyncapp.health.StepsRecord(
                count = count,
                startTime = result.startTime.atZone(zoneId).toInstant(),
                endTime = result.endTime.atZone(zoneId).toInstant()
            )
        }
    }

    suspend fun readBodyFatRecords(days: Int = 30): List<com.kiakiraki.healthsyncapp.health.BodyFatRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        val allRecords = mutableListOf<BodyFatRecord>()
        var pageToken: String? = null

        do {
            val request = ReadRecordsRequest(
                recordType = BodyFatRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, now),
                pageToken = pageToken
            )
            val response = healthConnectClient.readRecords(request)
            allRecords.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)

        return allRecords.map {
            com.kiakiraki.healthsyncapp.health.BodyFatRecord(
                percentage = it.percentage.value,
                time = it.time
            )
        }
    }

    suspend fun readHeartRateRecords(days: Int = 7): List<com.kiakiraki.healthsyncapp.health.HeartRateRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        val allRecords = mutableListOf<HeartRateRecord>()
        var pageToken: String? = null

        do {
            val request = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, now),
                pageToken = pageToken
            )
            val response = healthConnectClient.readRecords(request)
            allRecords.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)

        return allRecords.flatMap { record ->
            record.samples.map { sample ->
                com.kiakiraki.healthsyncapp.health.HeartRateRecord(
                    beatsPerMinute = sample.beatsPerMinute,
                    time = sample.time
                )
            }
        }
    }

    /**
     * Writes meal data from the API as NutritionRecords to Health Connect.
     * Uses clientRecordId ("meal-<id>") so repeated syncs upsert instead of
     * creating duplicates.
     * Returns a pair of (written count, skipped count).
     */
    suspend fun writeNutritionRecords(meals: List<MealData>): Pair<Int, Int> {
        var skipped = 0
        val now = Instant.now()
        val recordsToInsert = mutableListOf<NutritionRecord>()

        for (meal in meals) {
            val (startTime, endTime) = mealTimeRange(
                LocalDate.parse(meal.date),
                meal.mealType
            )

            if (startTime.isAfter(now)) {
                Log.d("HealthSync", "Skipping meal id=${meal.id} (${meal.date} ${meal.mealType}): start time is in the future")
                skipped++
                continue
            }

            val jstOffset = ZoneOffset.ofHours(9)
            val record = NutritionRecord(
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = jstOffset,
                endZoneOffset = jstOffset,
                metadata = Metadata.manualEntry(clientRecordId = "meal-${meal.id}"),
                name = meal.description,
                mealType = meal.mealType.toHealthConnectMealType(),
                energy = meal.caloriesKcal?.let { Energy.kilocalories(it) },
                protein = meal.proteinG?.let { Mass.grams(it) },
                totalFat = meal.fatG?.let { Mass.grams(it) },
                totalCarbohydrate = meal.carbsG?.let { Mass.grams(it) },
                dietaryFiber = meal.fiberG?.let { Mass.grams(it) },
                sodium = meal.saltG?.let { Mass.grams(it * 0.3937) }
            )
            recordsToInsert.add(record)
        }

        if (recordsToInsert.isNotEmpty()) {
            deleteLegacyNutritionRecords(
                startTime = recordsToInsert.minOf { it.startTime },
                endTime = recordsToInsert.maxOf { it.endTime }
            )
            healthConnectClient.insertRecords(recordsToInsert)
            Log.d("HealthSync", "Upserted ${recordsToInsert.size} nutrition records to Health Connect")
        }

        return recordsToInsert.size to skipped
    }

    /**
     * Deletes records this app wrote before clientRecordId-based deduplication
     * was introduced. Those records have no clientRecordId, so insertRecords
     * cannot upsert over them and they would remain as duplicates forever.
     * The dataOriginFilter is required to read own records with write-only
     * permission, and also guarantees other apps' meals are never touched.
     */
    private suspend fun deleteLegacyNutritionRecords(startTime: Instant, endTime: Instant) {
        val legacyIds = mutableListOf<String>()
        var pageToken: String? = null

        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = NutritionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                    pageToken = pageToken
                )
            )
            legacyIds.addAll(
                response.records
                    .filter { it.metadata.clientRecordId == null }
                    .map { it.metadata.id }
            )
            pageToken = response.pageToken
        } while (pageToken != null)

        if (legacyIds.isNotEmpty()) {
            healthConnectClient.deleteRecords(
                recordType = NutritionRecord::class,
                recordIdsList = legacyIds,
                clientRecordIdsList = emptyList()
            )
            Log.d("HealthSync", "Deleted ${legacyIds.size} legacy nutrition records without clientRecordId")
        }
    }

    private fun mealTimeRange(date: LocalDate, mealType: String): Pair<Instant, Instant> {
        val (startHour, endHour) = when (mealType) {
            "breakfast" -> 7 to 8
            "lunch" -> 12 to 13
            "dinner" -> 19 to 20
            "snack" -> 15 to 16
            else -> 12 to 13
        }
        val jstZone = ZoneId.of("Asia/Tokyo")
        val start = date.atTime(startHour, 0).atZone(jstZone).toInstant()
        val end = date.atTime(endHour, 0).atZone(jstZone).toInstant()
        return start to end
    }

    private fun String.toHealthConnectMealType(): Int = when (this) {
        "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
        "lunch" -> MealType.MEAL_TYPE_LUNCH
        "dinner" -> MealType.MEAL_TYPE_DINNER
        "snack" -> MealType.MEAL_TYPE_SNACK
        else -> MealType.MEAL_TYPE_UNKNOWN
    }
}
