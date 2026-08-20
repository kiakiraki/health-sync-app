package com.kiakiraki.healthsyncapp

import com.kiakiraki.healthsyncapp.api.HealthSyncApiClient
import com.kiakiraki.healthsyncapp.health.DailyCaloriesData
import com.kiakiraki.healthsyncapp.health.HealthSyncRequest
import com.kiakiraki.healthsyncapp.health.HeartRateData
import com.kiakiraki.healthsyncapp.health.OxygenSaturationData
import com.kiakiraki.healthsyncapp.health.RestingHeartRateData
import com.kiakiraki.healthsyncapp.health.StepsData
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the RingConn metrics payload: heart rate, resting heart rate,
 * SpO2, daily calories, and the sample-count based request splitting.
 *
 * Instants are chosen between 00:00Z and 14:59Z so the derived local date
 * is the same whether the test runs in UTC (CI) or JST (dev machines).
 */
class MetricsSyncTest {

    private fun buildRequests(
        heartRate: List<HeartRateData> = emptyList(),
        restingHeartRate: List<RestingHeartRateData> = emptyList(),
        oxygenSaturation: List<OxygenSaturationData> = emptyList(),
        dailyCalories: List<DailyCaloriesData> = emptyList(),
        steps: List<StepsData> = emptyList()
    ): List<HealthSyncRequest> = HealthSyncApiClient.buildSyncRequests(
        weightRecords = emptyList(),
        bodyFatRecords = emptyList(),
        bloodPressureRecords = emptyList(),
        heartRateRecords = heartRate,
        sleepRecords = emptyList(),
        stepsRecords = steps,
        restingHeartRateRecords = restingHeartRate,
        oxygenSaturationRecords = oxygenSaturation,
        dailyCaloriesRecords = dailyCalories
    )

    // -- heart_rate --

    @Test
    fun `heart rate samples map to ISO instants sorted by time`() {
        val request = buildRequests(
            heartRate = listOf(
                HeartRateData(80, Instant.parse("2026-08-20T03:05:00Z")),
                HeartRateData(72, Instant.parse("2026-08-20T03:00:00Z"))
            )
        ).single()

        assertEquals(2, request.heartRate.size)
        assertEquals("2026-08-20T03:00:00Z", request.heartRate[0].recordedAt)
        assertEquals(72L, request.heartRate[0].bpm)
        assertEquals("2026-08-20T03:05:00Z", request.heartRate[1].recordedAt)
        assertEquals(80L, request.heartRate[1].bpm)
    }

    // -- resting_heart_rate --

    @Test
    fun `resting heart rate converts time to local date`() {
        val request = buildRequests(
            restingHeartRate = listOf(
                RestingHeartRateData(58, Instant.parse("2026-08-20T03:00:00Z"))
            )
        ).single()

        val entry = request.restingHeartRate.single()
        assertEquals("2026-08-20", entry.date)
        assertEquals(58L, entry.bpm)
    }

    @Test
    fun `resting heart rate keeps only the latest record per day`() {
        val request = buildRequests(
            restingHeartRate = listOf(
                RestingHeartRateData(60, Instant.parse("2026-08-20T02:00:00Z")),
                RestingHeartRateData(56, Instant.parse("2026-08-20T10:00:00Z")),
                RestingHeartRateData(59, Instant.parse("2026-08-19T10:00:00Z"))
            )
        ).single()

        assertEquals(2, request.restingHeartRate.size)
        assertEquals("2026-08-19", request.restingHeartRate[0].date)
        assertEquals(59L, request.restingHeartRate[0].bpm)
        assertEquals("2026-08-20", request.restingHeartRate[1].date)
        assertEquals(56L, request.restingHeartRate[1].bpm)
    }

    // -- spo2 --

    @Test
    fun `spo2 maps percentage in 0-100 form with ISO instant`() {
        val request = buildRequests(
            oxygenSaturation = listOf(
                OxygenSaturationData(97.5, Instant.parse("2026-08-20T03:00:00Z"))
            )
        ).single()

        val entry = request.spo2.single()
        assertEquals("2026-08-20T03:00:00Z", entry.recordedAt)
        assertEquals(97.5, entry.percentage, 0.0001)
    }

    // -- daily_activity --

    @Test
    fun `daily activity maps bucket start date and both calorie values`() {
        val request = buildRequests(
            dailyCalories = listOf(
                DailyCaloriesData(
                    activeCaloriesKcal = 320.5,
                    totalCaloriesKcal = 2100.0,
                    startTime = Instant.parse("2026-08-20T00:00:00Z"),
                    endTime = Instant.parse("2026-08-20T14:00:00Z")
                )
            )
        ).single()

        val entry = request.dailyActivity.single()
        assertEquals("2026-08-20", entry.date)
        assertEquals(320.5, entry.activeCaloriesKcal!!, 0.0001)
        assertEquals(2100.0, entry.totalCaloriesKcal!!, 0.0001)
    }

    @Test
    fun `daily activity keeps a day with only one calorie value and drops empty days`() {
        val request = buildRequests(
            dailyCalories = listOf(
                DailyCaloriesData(
                    activeCaloriesKcal = 250.0,
                    totalCaloriesKcal = null,
                    startTime = Instant.parse("2026-08-19T00:00:00Z"),
                    endTime = Instant.parse("2026-08-19T14:00:00Z")
                ),
                DailyCaloriesData(
                    activeCaloriesKcal = null,
                    totalCaloriesKcal = null,
                    startTime = Instant.parse("2026-08-20T00:00:00Z"),
                    endTime = Instant.parse("2026-08-20T14:00:00Z")
                )
            )
        ).single()

        val entry = request.dailyActivity.single()
        assertEquals("2026-08-19", entry.date)
        assertEquals(250.0, entry.activeCaloriesKcal!!, 0.0001)
        assertNull(entry.totalCaloriesKcal)
    }

    // -- request splitting --

    private fun heartRateSamples(count: Int): List<HeartRateData> =
        (0 until count).map {
            HeartRateData(70, Instant.parse("2026-08-18T00:00:00Z").plusSeconds(it * 60L))
        }

    private fun spo2Samples(count: Int): List<OxygenSaturationData> =
        (0 until count).map {
            OxygenSaturationData(97.0, Instant.parse("2026-08-19T00:00:00Z").plusSeconds(it * 60L))
        }

    @Test
    fun `no samples produce a single request`() {
        assertEquals(1, buildRequests().size)
    }

    @Test
    fun `exactly max samples fit in a single request`() {
        val requests = buildRequests(
            heartRate = heartRateSamples(600),
            oxygenSaturation = spo2Samples(400)
        )

        assertEquals(1, requests.size)
        assertEquals(600, requests.single().heartRate.size)
        assertEquals(400, requests.single().spo2.size)
    }

    @Test
    fun `combined samples above the cap split into multiple requests`() {
        val requests = buildRequests(
            heartRate = heartRateSamples(1500),
            oxygenSaturation = spo2Samples(700),
            steps = listOf(
                StepsData(8000, Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-20T14:00:00Z"))
            )
        )

        assertEquals(3, requests.size)
        requests.forEach { request ->
            assertTrue(request.heartRate.size + request.spo2.size <= HealthSyncApiClient.MAX_SAMPLES_PER_REQUEST)
        }

        // No sample is lost or duplicated by the split
        assertEquals(1500, requests.sumOf { it.heartRate.size })
        assertEquals(700, requests.sumOf { it.spo2.size })

        // Non-sample metrics ride only on the first request
        assertEquals(1, requests[0].steps.size)
        assertTrue(requests.drop(1).all { it.steps.isEmpty() })
        assertTrue(requests.drop(1).all { it.bodyMeasurements.isEmpty() })
        assertTrue(requests.drop(1).all { it.restingHeartRate.isEmpty() })
        assertTrue(requests.drop(1).all { it.dailyActivity.isEmpty() })
    }
}
