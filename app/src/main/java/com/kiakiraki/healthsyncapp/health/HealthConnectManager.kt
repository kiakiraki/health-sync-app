package com.kiakiraki.healthsyncapp.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
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
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
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

        /**
         * The meals API reports salt (NaCl) as on Japanese nutrition labels,
         * while Health Connect stores sodium. Standard label conversion:
         * salt = sodium x 2.54, i.e. sodium = salt / 2.54.
         */
        private const val SODIUM_GRAMS_PER_SALT_GRAM = 1 / 2.54

        fun isHealthConnectAvailable(context: Context): Boolean {
            return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }

        /**
         * Returns true if the stages contain at least one detailed sleep stage
         * (light, deep, or REM). Wearables like Pixel Watch provide these,
         * while Nest Hub typically only records "sleeping" (type 2).
         */
        internal fun hasDetailedStages(stages: List<SleepStageData>): Boolean {
            val detailedTypes = setOf(4, 5, 6) // light, deep, rem
            return stages.any { it.stage in detailedTypes }
        }

        /**
         * Trims a single sleep stage by removing portions that overlap with
         * the given covered intervals. May return 0, 1, or multiple fragments.
         */
        internal fun trimStageByIntervals(
            stage: SleepStageData,
            coveredIntervals: List<SleepStageData>
        ): List<SleepStageData> {
            val result = mutableListOf<SleepStageData>()
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
            preferred: List<SleepStageData>,
            fallback: List<SleepStageData>
        ): List<SleepStageData> {
            val sortedPreferred = preferred.sortedBy { it.startTime }
            val trimmedFallback = fallback.flatMap { stage ->
                trimStageByIntervals(stage, sortedPreferred)
            }
            return (sortedPreferred + trimmedFallback).sortedBy { it.startTime }
        }

        internal fun mergeOverlappingSleepSessions(
            sessions: List<SleepData>
        ): List<SleepData> {
            if (sessions.isEmpty()) return emptyList()

            val sorted = sessions.sortedBy { it.startTime }
            val merged = mutableListOf<SleepData>()

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

                    current = SleepData(
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

        val recentWeights = readRecentWeights(thirtyDaysAgo, now)
        val recentBodyFat = readRecentBodyFat(thirtyDaysAgo, now)
        val latestBloodPressure = readLatestBloodPressure(thirtyDaysAgo, now)
        val latestHeartRate = readLatestHeartRate(sevenDaysAgo, now)
        val totalSteps = aggregateTotalSteps(sevenDaysAgo, now)
        val totalSleep = aggregateTotalSleep(sevenDaysAgo, now)

        return HealthSummary(
            latestWeightKg = recentWeights.getOrNull(0),
            previousWeightKg = recentWeights.getOrNull(1),
            latestBodyFatPercent = recentBodyFat.getOrNull(0),
            previousBodyFatPercent = recentBodyFat.getOrNull(1),
            latestSystolicMmHg = latestBloodPressure?.first,
            latestDiastolicMmHg = latestBloodPressure?.second,
            latestHeartRateBpm = latestHeartRate,
            totalStepsLast7Days = totalSteps,
            totalSleepMinutesLast7Days = totalSleep,
            lastUpdated = now
        )
    }

    /**
     * Returns the most recent weight values, newest first (at most two:
     * latest and previous, for the trend display).
     */
    private suspend fun readRecentWeights(startTime: Instant, endTime: Instant): List<Double> {
        return try {
            readAllRecords(WeightRecord::class, startTime, endTime)
                .sortedByDescending { it.time }
                .take(2)
                .map { it.weight.inKilograms }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HealthSync", "Failed to read recent weights", e)
            emptyList()
        }
    }

    private suspend fun readRecentBodyFat(startTime: Instant, endTime: Instant): List<Double> {
        return try {
            readAllRecords(BodyFatRecord::class, startTime, endTime)
                .sortedByDescending { it.time }
                .take(2)
                .map { it.percentage.value }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HealthSync", "Failed to read recent body fat", e)
            emptyList()
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

    /**
     * Reads all pages of the given record type within the time range.
     */
    private suspend fun <T : Record> readAllRecords(
        recordType: KClass<T>,
        startTime: Instant,
        endTime: Instant,
        dataOriginFilter: Set<DataOrigin> = emptySet()
    ): List<T> {
        val allRecords = mutableListOf<T>()
        var pageToken: String? = null

        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    dataOriginFilter = dataOriginFilter,
                    pageToken = pageToken
                )
            )
            allRecords.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)

        return allRecords
    }

    suspend fun readWeightRecords(days: Int = 30): List<WeightData> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return readAllRecords(WeightRecord::class, startTime, now).map {
            WeightData(
                weightKg = it.weight.inKilograms,
                time = it.time
            )
        }
    }

    suspend fun readBloodPressureRecords(days: Int = 30): List<BloodPressureData> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return readAllRecords(BloodPressureRecord::class, startTime, now).map {
            BloodPressureData(
                systolicMmHg = it.systolic.inMillimetersOfMercury,
                diastolicMmHg = it.diastolic.inMillimetersOfMercury,
                time = it.time
            )
        }
    }

    suspend fun readSleepRecords(days: Int = 7): List<SleepData> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        val sleepRecords = readAllRecords(SleepSessionRecord::class, startTime, now).map {
            SleepData(
                durationMinutes = java.time.Duration.between(it.startTime, it.endTime).toMinutes(),
                startTime = it.startTime,
                endTime = it.endTime,
                stages = it.stages.map { stage ->
                    SleepStageData(
                        stage = stage.stage,
                        startTime = stage.startTime,
                        endTime = stage.endTime
                    )
                }
            )
        }

        return mergeOverlappingSleepSessions(sleepRecords)
    }

    suspend fun readStepsRecords(days: Int = 7): List<StepsData> {
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
            StepsData(
                count = count,
                startTime = result.startTime.atZone(zoneId).toInstant(),
                endTime = result.endTime.atZone(zoneId).toInstant()
            )
        }
    }

    suspend fun readBodyFatRecords(days: Int = 30): List<BodyFatData> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return readAllRecords(BodyFatRecord::class, startTime, now).map {
            BodyFatData(
                percentage = it.percentage.value,
                time = it.time
            )
        }
    }

    suspend fun readHeartRateRecords(days: Int = 7): List<HeartRateData> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return readAllRecords(HeartRateRecord::class, startTime, now).flatMap { record ->
            record.samples.map { sample ->
                HeartRateData(
                    beatsPerMinute = sample.beatsPerMinute,
                    time = sample.time
                )
            }
        }
    }

    suspend fun readRestingHeartRateRecords(days: Int = 7): List<RestingHeartRateData> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return readAllRecords(RestingHeartRateRecord::class, startTime, now).map {
            RestingHeartRateData(
                beatsPerMinute = it.beatsPerMinute,
                time = it.time
            )
        }
    }

    suspend fun readOxygenSaturationRecords(days: Int = 7): List<OxygenSaturationData> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return readAllRecords(OxygenSaturationRecord::class, startTime, now).map {
            OxygenSaturationData(
                percentage = it.percentage.value,
                time = it.time
            )
        }
    }

    /**
     * Aggregates active and total calories per calendar day. The two values
     * come from different record types, so either may be null for a day
     * where only one source has data.
     */
    suspend fun readDailyCaloriesRecords(days: Int = 7): List<DailyCaloriesData> {
        val zoneId = ZoneId.systemDefault()
        val now = LocalDateTime.now(zoneId)
        // Buckets must start at midnight, same as readStepsRecords.
        val startTime = now.toLocalDate().minusDays(days - 1L).atStartOfDay()

        val request = AggregateGroupByPeriodRequest(
            metrics = setOf(
                ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                TotalCaloriesBurnedRecord.ENERGY_TOTAL
            ),
            timeRangeFilter = TimeRangeFilter.between(startTime, now),
            timeRangeSlicer = Period.ofDays(1)
        )
        val response = healthConnectClient.aggregateGroupByPeriod(request)

        return response.mapNotNull { result ->
            val active = result.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            val total = result.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            if (active == null && total == null) return@mapNotNull null
            DailyCaloriesData(
                activeCaloriesKcal = active,
                totalCaloriesKcal = total,
                startTime = result.startTime.atZone(zoneId).toInstant(),
                endTime = result.endTime.atZone(zoneId).toInstant()
            )
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
                sodium = meal.saltG?.let { Mass.grams(it * SODIUM_GRAMS_PER_SALT_GRAM) }
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
        val legacyIds = readAllRecords(
            NutritionRecord::class,
            startTime,
            endTime,
            dataOriginFilter = setOf(DataOrigin(context.packageName))
        )
            .filter { it.metadata.clientRecordId == null }
            .map { it.metadata.id }

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
