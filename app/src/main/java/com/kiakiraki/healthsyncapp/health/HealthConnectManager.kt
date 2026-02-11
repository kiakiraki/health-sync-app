package com.kiakiraki.healthsyncapp.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
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
            HealthPermission.getReadPermission(StepsRecord::class)
        )

        fun isHealthConnectAvailable(context: Context): Boolean {
            return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }
    }

    fun createPermissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    fun isAvailable(): Boolean = isHealthConnectAvailable(context)

    suspend fun hasAllPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return PERMISSIONS.all { it in granted }
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            null
        }
    }

    suspend fun readWeightRecords(days: Int = 30): List<com.kiakiraki.healthsyncapp.health.WeightRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return try {
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

            allRecords.map {
                com.kiakiraki.healthsyncapp.health.WeightRecord(
                    weightKg = it.weight.inKilograms,
                    time = it.time
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun readBloodPressureRecords(days: Int = 30): List<com.kiakiraki.healthsyncapp.health.BloodPressureRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return try {
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

            allRecords.map {
                com.kiakiraki.healthsyncapp.health.BloodPressureRecord(
                    systolicMmHg = it.systolic.inMillimetersOfMercury,
                    diastolicMmHg = it.diastolic.inMillimetersOfMercury,
                    time = it.time
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun readSleepRecords(days: Int = 7): List<com.kiakiraki.healthsyncapp.health.SleepRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return try {
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
                    endTime = it.endTime
                )
            }

            mergeOverlappingSleepSessions(sleepRecords)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun mergeOverlappingSleepSessions(
        sessions: List<com.kiakiraki.healthsyncapp.health.SleepRecord>
    ): List<com.kiakiraki.healthsyncapp.health.SleepRecord> {
        if (sessions.isEmpty()) return emptyList()

        val sorted = sessions.sortedBy { it.startTime }
        val merged = mutableListOf<com.kiakiraki.healthsyncapp.health.SleepRecord>()

        var current = sorted.first()

        for (session in sorted.drop(1)) {
            if (session.startTime <= current.endTime) {
                // Overlapping - merge by taking earliest start and latest end
                val newStart = if (current.startTime < session.startTime) current.startTime else session.startTime
                val newEnd = if (current.endTime > session.endTime) current.endTime else session.endTime
                current = com.kiakiraki.healthsyncapp.health.SleepRecord(
                    durationMinutes = java.time.Duration.between(newStart, newEnd).toMinutes(),
                    startTime = newStart,
                    endTime = newEnd
                )
            } else {
                // No overlap - add current and start new
                merged.add(current)
                current = session
            }
        }
        merged.add(current)

        return merged
    }

    suspend fun readStepsRecords(days: Int = 7): List<com.kiakiraki.healthsyncapp.health.StepsRecord> {
        val zoneId = ZoneId.systemDefault()
        val now = LocalDateTime.now(zoneId)
        val startTime = now.minusDays(days.toLong())

        return try {
            val request = AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startTime, now),
                timeRangeSlicer = Period.ofDays(1)
            )
            val response = healthConnectClient.aggregateGroupByPeriod(request)

            response.mapNotNull { result ->
                val count = result.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
                com.kiakiraki.healthsyncapp.health.StepsRecord(
                    count = count,
                    startTime = result.startTime.atZone(zoneId).toInstant(),
                    endTime = result.endTime.atZone(zoneId).toInstant()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun readBodyFatRecords(days: Int = 30): List<com.kiakiraki.healthsyncapp.health.BodyFatRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return try {
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

            allRecords.map {
                com.kiakiraki.healthsyncapp.health.BodyFatRecord(
                    percentage = it.percentage.value,
                    time = it.time
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun readHeartRateRecords(days: Int = 7): List<com.kiakiraki.healthsyncapp.health.HeartRateRecord> {
        val now = Instant.now()
        val startTime = now.minus(days.toLong(), ChronoUnit.DAYS)

        return try {
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

            allRecords.flatMap { record ->
                record.samples.map { sample ->
                    com.kiakiraki.healthsyncapp.health.HeartRateRecord(
                        beatsPerMinute = sample.beatsPerMinute,
                        time = sample.time
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
