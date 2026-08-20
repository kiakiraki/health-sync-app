package com.kiakiraki.healthsyncapp

import com.kiakiraki.healthsyncapp.api.HealthSyncApiClient
import com.kiakiraki.healthsyncapp.health.BloodPressureData
import com.kiakiraki.healthsyncapp.health.BodyFatData
import com.kiakiraki.healthsyncapp.health.HeartRateData
import com.kiakiraki.healthsyncapp.health.SleepData
import com.kiakiraki.healthsyncapp.health.SleepStageData
import com.kiakiraki.healthsyncapp.health.StepsData
import com.kiakiraki.healthsyncapp.health.WeightData
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthSyncApiClientTest {

    private fun buildRequest(
        weight: List<WeightData> = emptyList(),
        bodyFat: List<BodyFatData> = emptyList(),
        bloodPressure: List<BloodPressureData> = emptyList(),
        heartRate: List<HeartRateData> = emptyList(),
        sleep: List<SleepData> = emptyList(),
        steps: List<StepsData> = emptyList()
    ) = HealthSyncApiClient.buildSyncRequests(
        weightRecords = weight,
        bodyFatRecords = bodyFat,
        bloodPressureRecords = bloodPressure,
        heartRateRecords = heartRate,
        sleepRecords = sleep,
        stepsRecords = steps
    ).single()

    @Test
    fun `weight and body fat in the same minute merge into one measurement`() {
        val request = buildRequest(
            weight = listOf(WeightData(65.2, Instant.parse("2026-07-01T07:30:10Z"))),
            bodyFat = listOf(BodyFatData(21.5, Instant.parse("2026-07-01T07:30:55Z")))
        )

        assertEquals(1, request.bodyMeasurements.size)
        val measurement = request.bodyMeasurements.single()
        assertEquals(65.2, measurement.weightKg!!, 0.0001)
        assertEquals(21.5, measurement.bodyFatPercent!!, 0.0001)
    }

    @Test
    fun `weight and body fat in different minutes stay separate`() {
        val request = buildRequest(
            weight = listOf(WeightData(65.2, Instant.parse("2026-07-01T07:30:00Z"))),
            bodyFat = listOf(BodyFatData(21.5, Instant.parse("2026-07-01T07:31:00Z")))
        )

        assertEquals(2, request.bodyMeasurements.size)
        assertNull(request.bodyMeasurements.first { it.weightKg != null }.bodyFatPercent)
        assertNull(request.bodyMeasurements.first { it.bodyFatPercent != null }.weightKg)
    }

    @Test
    fun `blood pressure picks up pulse measured in the same minute`() {
        val request = buildRequest(
            bloodPressure = listOf(
                BloodPressureData(118.0, 76.0, Instant.parse("2026-07-01T22:00:30Z"))
            ),
            heartRate = listOf(
                HeartRateData(64, Instant.parse("2026-07-01T22:00:05Z")),
                HeartRateData(80, Instant.parse("2026-07-01T22:05:00Z"))
            )
        )

        val bp = request.bloodPressure.single()
        assertEquals(118, bp.systolic)
        assertEquals(76, bp.diastolic)
        assertEquals(64, bp.pulse)
    }

    @Test
    fun `blood pressure without matching heart rate has null pulse`() {
        val request = buildRequest(
            bloodPressure = listOf(
                BloodPressureData(118.0, 76.0, Instant.parse("2026-07-01T22:00:00Z"))
            ),
            heartRate = listOf(HeartRateData(64, Instant.parse("2026-07-01T22:01:00Z")))
        )

        assertNull(request.bloodPressure.single().pulse)
    }

    @Test
    fun `sleep session maps duration and stage names`() {
        val start = Instant.parse("2026-07-01T15:00:00Z")
        val end = Instant.parse("2026-07-01T22:30:00Z")
        val request = buildRequest(
            sleep = listOf(
                SleepData(
                    durationMinutes = 450,
                    startTime = start,
                    endTime = end,
                    stages = listOf(
                        SleepStageData(4, start, Instant.parse("2026-07-01T16:00:00Z")),
                        SleepStageData(5, Instant.parse("2026-07-01T16:00:00Z"), Instant.parse("2026-07-01T17:00:00Z")),
                        SleepStageData(6, Instant.parse("2026-07-01T17:00:00Z"), end)
                    )
                )
            )
        )

        val session = request.sleepSessions.single()
        assertEquals(7.5, session.durationHours, 0.0001)
        assertEquals(listOf("light", "deep", "rem"), session.stages.map { it.stage })
    }

    @Test
    fun `steps map to bucket start date and sort by date`() {
        val request = buildRequest(
            steps = listOf(
                StepsData(8000, Instant.parse("2026-07-02T15:00:00Z"), Instant.parse("2026-07-03T15:00:00Z")),
                StepsData(6000, Instant.parse("2026-07-01T15:00:00Z"), Instant.parse("2026-07-02T15:00:00Z"))
            )
        )

        assertEquals(listOf(6000L, 8000L), request.steps.map { it.count })
        assertEquals(request.steps, request.steps.sortedBy { it.date })
    }
}
