package com.kiakiraki.healthsyncapp.api

import com.kiakiraki.healthsyncapp.BuildConfig
import com.kiakiraki.healthsyncapp.health.BloodPressureApi
import com.kiakiraki.healthsyncapp.health.BloodPressureData
import com.kiakiraki.healthsyncapp.health.BodyFatData
import com.kiakiraki.healthsyncapp.health.BodyMeasurementApi
import com.kiakiraki.healthsyncapp.health.HealthSyncRequest
import com.kiakiraki.healthsyncapp.health.HeartRateData
import com.kiakiraki.healthsyncapp.health.MealData
import com.kiakiraki.healthsyncapp.health.MealsResponse
import com.kiakiraki.healthsyncapp.health.SleepData
import com.kiakiraki.healthsyncapp.health.SleepSessionApi
import com.kiakiraki.healthsyncapp.health.SleepStageApi
import com.kiakiraki.healthsyncapp.health.StepsApi
import com.kiakiraki.healthsyncapp.health.StepsData
import com.kiakiraki.healthsyncapp.health.WeightData
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
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
        // Without timeouts an unresponsive server leaves the sync stuck in
        // "Syncing..." forever.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    suspend fun syncHealthData(request: HealthSyncRequest): Result<Unit> {
        return try {
            val response = client.post(SYNC_URL) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchMeals(days: Int = 7): Result<List<MealData>> {
        return try {
            val response = client.get(MEALS_URL) {
                header(HttpHeaders.Authorization, "Bearer ${BuildConfig.HEALTH_SYNC_API_KEY}")
                parameter("days", days)
            }

            if (response.status.isSuccess()) {
                val mealsResponse: MealsResponse = response.body()
                Result.success(mealsResponse.meals)
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(ApiException(response.status.value, errorBody))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        client.close()
    }

    companion object {
        /**
         * HEALTH_SYNC_API_URL in local.properties points at the sync
         * endpoint (".../sync"); sibling endpoints are derived from its
         * parent path.
         */
        private val SYNC_URL = BuildConfig.HEALTH_SYNC_API_URL.trimEnd('/')
        private val MEALS_URL = SYNC_URL.substringBeforeLast('/') + "/meals"

        private val isoFormatter = DateTimeFormatter.ISO_INSTANT

        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun buildSyncRequest(
            weightRecords: List<WeightData>,
            bodyFatRecords: List<BodyFatData>,
            bloodPressureRecords: List<BloodPressureData>,
            heartRateRecords: List<HeartRateData>,
            sleepRecords: List<SleepData>,
            stepsRecords: List<StepsData>
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
            weightRecords: List<WeightData>,
            bodyFatRecords: List<BodyFatData>
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
            bloodPressureRecords: List<BloodPressureData>,
            heartRateRecords: List<HeartRateData>
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

        private fun buildSleepSessions(sleepRecords: List<SleepData>): List<SleepSessionApi> {
            return sleepRecords.map { sleep ->
                SleepSessionApi(
                    startTime = formatInstantToIso(sleep.startTime),
                    endTime = formatInstantToIso(sleep.endTime),
                    durationHours = sleep.durationMinutes / 60.0,
                    stages = sleep.stages.map { stage ->
                        SleepStageApi(
                            stage = mapStageType(stage.stage),
                            startTime = formatInstantToIso(stage.startTime),
                            endTime = formatInstantToIso(stage.endTime)
                        )
                    }
                )
            }
        }

        private fun mapStageType(stageType: Int): String = when (stageType) {
            1 -> "awake"
            2 -> "sleeping"
            3 -> "out_of_bed"
            4 -> "light"
            5 -> "deep"
            6 -> "rem"
            7 -> "awake_in_bed"
            else -> "unknown"
        }

        private fun buildSteps(stepsRecords: List<StepsData>): List<StepsApi> {
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

class ApiException(
    val statusCode: Int,
    val responseBody: String
) : Exception("API Error ($statusCode): ${extractErrorMessage(responseBody)}")

private fun extractErrorMessage(body: String): String {
    if (body.trimStart().startsWith("<")) {
        return "Server returned HTML error response"
    }
    return body.take(200)
}
