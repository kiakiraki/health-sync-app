package com.kiakiraki.healthsyncapp.api

import com.kiakiraki.healthsyncapp.BuildConfig
import com.kiakiraki.healthsyncapp.health.BloodPressureApi
import com.kiakiraki.healthsyncapp.health.BodyFatRecord
import com.kiakiraki.healthsyncapp.health.BodyMeasurementApi
import com.kiakiraki.healthsyncapp.health.HealthSyncRequest
import com.kiakiraki.healthsyncapp.health.HeartRateRecord
import com.kiakiraki.healthsyncapp.health.SleepRecord
import com.kiakiraki.healthsyncapp.health.SleepSessionApi
import com.kiakiraki.healthsyncapp.health.StepsApi
import com.kiakiraki.healthsyncapp.health.StepsRecord
import com.kiakiraki.healthsyncapp.health.WeightRecord
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HealthSyncApiClient {

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    suspend fun syncHealthData(request: HealthSyncRequest): Result<Unit> {
        return try {
            val response = client.post(API_URL) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${BuildConfig.HEALTH_SYNC_API_KEY}")
                setBody(request)
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(ApiException(response.status.value, errorBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        client.close()
    }

    companion object {
        private val API_URL = BuildConfig.HEALTH_SYNC_API_URL

        private val isoFormatter = DateTimeFormatter.ISO_INSTANT

        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun buildSyncRequest(
            weightRecords: List<WeightRecord>,
            bodyFatRecords: List<BodyFatRecord>,
            bloodPressureRecords: List<com.kiakiraki.healthsyncapp.health.BloodPressureRecord>,
            heartRateRecords: List<HeartRateRecord>,
            sleepRecords: List<SleepRecord>,
            stepsRecords: List<StepsRecord>
        ): HealthSyncRequest {
            val bodyMeasurements = buildBodyMeasurements(weightRecords, bodyFatRecords)
            val bloodPressure = buildBloodPressure(bloodPressureRecords, heartRateRecords)
            val sleepSessions = buildSleepSessions(sleepRecords)
            val steps = buildSteps(stepsRecords)

            return HealthSyncRequest(
                bodyMeasurements = bodyMeasurements,
                bloodPressure = bloodPressure,
                sleepSessions = sleepSessions,
                steps = steps
            )
        }

        private fun buildBodyMeasurements(
            weightRecords: List<WeightRecord>,
            bodyFatRecords: List<BodyFatRecord>
        ): List<BodyMeasurementApi> {
            val weightByTime = weightRecords.associateBy { it.time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) }
            val bodyFatByTime = bodyFatRecords.associateBy { it.time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) }

            val allTimes = (weightByTime.keys + bodyFatByTime.keys).distinct().sorted()

            return allTimes.map { time ->
                BodyMeasurementApi(
                    recordedAt = formatInstantToIso(time),
                    weightKg = weightByTime[time]?.weightKg,
                    bodyFatPercent = bodyFatByTime[time]?.percentage
                )
            }
        }

        private fun buildBloodPressure(
            bloodPressureRecords: List<com.kiakiraki.healthsyncapp.health.BloodPressureRecord>,
            heartRateRecords: List<HeartRateRecord>
        ): List<BloodPressureApi> {
            val heartRateByTime = heartRateRecords.associateBy {
                it.time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
            }

            return bloodPressureRecords.map { bp ->
                val bpTimeMinute = bp.time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                val pulse = heartRateByTime[bpTimeMinute]?.beatsPerMinute?.toInt()

                BloodPressureApi(
                    recordedAt = formatInstantToIso(bp.time),
                    systolic = bp.systolicMmHg.toInt(),
                    diastolic = bp.diastolicMmHg.toInt(),
                    pulse = pulse
                )
            }
        }

        private fun buildSleepSessions(sleepRecords: List<SleepRecord>): List<SleepSessionApi> {
            return sleepRecords.map { sleep ->
                SleepSessionApi(
                    startTime = formatInstantToIso(sleep.startTime),
                    endTime = formatInstantToIso(sleep.endTime),
                    durationHours = sleep.durationMinutes / 60.0
                )
            }
        }

        private fun buildSteps(stepsRecords: List<StepsRecord>): List<StepsApi> {
            return stepsRecords.map { record ->
                val date = LocalDate.ofInstant(record.startTime, ZoneId.systemDefault())
                StepsApi(
                    date = dateFormatter.format(date),
                    count = record.count
                )
            }.sortedBy { it.date }
        }

        private fun formatInstantToIso(instant: Instant): String {
            return isoFormatter.format(instant)
        }
    }
}

class ApiException(val statusCode: Int, message: String) : Exception("API Error ($statusCode): $message")
