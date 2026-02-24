package com.kiakiraki.healthsyncapp

import com.kiakiraki.healthsyncapp.api.HealthSyncApiClient
import com.kiakiraki.healthsyncapp.health.HealthConnectManager
import com.kiakiraki.healthsyncapp.health.SleepRecord
import com.kiakiraki.healthsyncapp.health.SleepStageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SleepStageTest {

    private fun instant(iso: String): Instant = Instant.parse(iso)

    // -- buildSyncRequest: stage type mapping --

    @Test
    fun `buildSyncRequest maps all Health Connect stage types to API strings`() {
        val stageTypes = mapOf(
            0 to "unknown",
            1 to "awake",
            2 to "sleeping",
            3 to "out_of_bed",
            4 to "light",
            5 to "deep",
            6 to "rem",
            7 to "awake_in_bed",
        )

        for ((healthConnectType, expectedApiString) in stageTypes) {
            val sleepRecord = SleepRecord(
                durationMinutes = 60,
                startTime = instant("2026-02-24T23:00:00Z"),
                endTime = instant("2026-02-25T00:00:00Z"),
                stages = listOf(
                    SleepStageRecord(
                        stage = healthConnectType,
                        startTime = instant("2026-02-24T23:00:00Z"),
                        endTime = instant("2026-02-25T00:00:00Z")
                    )
                )
            )

            val request = HealthSyncApiClient.buildSyncRequest(
                weightRecords = emptyList(),
                bodyFatRecords = emptyList(),
                bloodPressureRecords = emptyList(),
                heartRateRecords = emptyList(),
                sleepRecords = listOf(sleepRecord),
                stepsRecords = emptyList()
            )

            assertEquals(
                "stage type $healthConnectType should map to \"$expectedApiString\"",
                expectedApiString,
                request.sleepSessions[0].stages[0].stage
            )
        }
    }

    @Test
    fun `buildSyncRequest maps unknown stage type to unknown`() {
        val sleepRecord = SleepRecord(
            durationMinutes = 60,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T00:00:00Z"),
            stages = listOf(
                SleepStageRecord(
                    stage = 99,
                    startTime = instant("2026-02-24T23:00:00Z"),
                    endTime = instant("2026-02-25T00:00:00Z")
                )
            )
        )

        val request = HealthSyncApiClient.buildSyncRequest(
            weightRecords = emptyList(),
            bodyFatRecords = emptyList(),
            bloodPressureRecords = emptyList(),
            heartRateRecords = emptyList(),
            sleepRecords = listOf(sleepRecord),
            stepsRecords = emptyList()
        )

        assertEquals("unknown", request.sleepSessions[0].stages[0].stage)
    }

    // -- buildSyncRequest: sleep session with stages --

    @Test
    fun `buildSyncRequest includes stages with ISO 8601 timestamps`() {
        val sleepRecord = SleepRecord(
            durationMinutes = 480,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T07:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-24T23:45:00Z")),
                SleepStageRecord(stage = 5, startTime = instant("2026-02-24T23:45:00Z"), endTime = instant("2026-02-25T01:00:00Z")),
                SleepStageRecord(stage = 6, startTime = instant("2026-02-25T01:00:00Z"), endTime = instant("2026-02-25T02:30:00Z")),
            )
        )

        val request = HealthSyncApiClient.buildSyncRequest(
            weightRecords = emptyList(),
            bodyFatRecords = emptyList(),
            bloodPressureRecords = emptyList(),
            heartRateRecords = emptyList(),
            sleepRecords = listOf(sleepRecord),
            stepsRecords = emptyList()
        )

        val session = request.sleepSessions[0]
        assertEquals(8.0, session.durationHours, 0.01)
        assertEquals(3, session.stages.size)

        assertEquals("light", session.stages[0].stage)
        assertEquals("2026-02-24T23:00:00Z", session.stages[0].startTime)
        assertEquals("2026-02-24T23:45:00Z", session.stages[0].endTime)

        assertEquals("deep", session.stages[1].stage)
        assertEquals("rem", session.stages[2].stage)
    }

    @Test
    fun `buildSyncRequest produces empty stages list when sleep has no stages`() {
        val sleepRecord = SleepRecord(
            durationMinutes = 480,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T07:00:00Z")
        )

        val request = HealthSyncApiClient.buildSyncRequest(
            weightRecords = emptyList(),
            bodyFatRecords = emptyList(),
            bloodPressureRecords = emptyList(),
            heartRateRecords = emptyList(),
            sleepRecords = listOf(sleepRecord),
            stepsRecords = emptyList()
        )

        assertTrue(request.sleepSessions[0].stages.isEmpty())
    }

    // -- mergeOverlappingSleepSessions: stage merging --

    @Test
    fun `merge combines stages from overlapping sessions sorted by start time`() {
        val session1 = SleepRecord(
            durationMinutes = 120,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T01:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-24T23:30:00Z")),
                SleepStageRecord(stage = 5, startTime = instant("2026-02-24T23:30:00Z"), endTime = instant("2026-02-25T01:00:00Z")),
            )
        )
        val session2 = SleepRecord(
            durationMinutes = 120,
            startTime = instant("2026-02-25T00:00:00Z"),
            endTime = instant("2026-02-25T02:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 6, startTime = instant("2026-02-25T00:00:00Z"), endTime = instant("2026-02-25T01:30:00Z")),
                SleepStageRecord(stage = 4, startTime = instant("2026-02-25T01:30:00Z"), endTime = instant("2026-02-25T02:00:00Z")),
            )
        )

        val merged = HealthConnectManager.mergeOverlappingSleepSessions(listOf(session1, session2))

        assertEquals(1, merged.size)
        assertEquals(instant("2026-02-24T23:00:00Z"), merged[0].startTime)
        assertEquals(instant("2026-02-25T02:00:00Z"), merged[0].endTime)
        assertEquals(180, merged[0].durationMinutes)

        assertEquals(4, merged[0].stages.size)
        assertEquals(instant("2026-02-24T23:00:00Z"), merged[0].stages[0].startTime)
        assertEquals(instant("2026-02-25T02:00:00Z"), merged[0].stages[3].endTime)
    }

    @Test
    fun `merge keeps stages when one session has stages and the other does not`() {
        val sessionWithStages = SleepRecord(
            durationMinutes = 120,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T01:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-25T01:00:00Z")),
            )
        )
        val sessionWithoutStages = SleepRecord(
            durationMinutes = 60,
            startTime = instant("2026-02-25T00:00:00Z"),
            endTime = instant("2026-02-25T01:00:00Z")
        )

        val merged = HealthConnectManager.mergeOverlappingSleepSessions(listOf(sessionWithStages, sessionWithoutStages))

        assertEquals(1, merged.size)
        assertEquals(1, merged[0].stages.size)
        assertEquals(4, merged[0].stages[0].stage)
    }

    @Test
    fun `merge does not combine non-overlapping sessions`() {
        val session1 = SleepRecord(
            durationMinutes = 120,
            startTime = instant("2026-02-24T22:00:00Z"),
            endTime = instant("2026-02-25T00:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 5, startTime = instant("2026-02-24T22:00:00Z"), endTime = instant("2026-02-25T00:00:00Z")),
            )
        )
        val session2 = SleepRecord(
            durationMinutes = 60,
            startTime = instant("2026-02-25T01:00:00Z"),
            endTime = instant("2026-02-25T02:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 4, startTime = instant("2026-02-25T01:00:00Z"), endTime = instant("2026-02-25T02:00:00Z")),
            )
        )

        val merged = HealthConnectManager.mergeOverlappingSleepSessions(listOf(session1, session2))

        assertEquals(2, merged.size)
        assertEquals(1, merged[0].stages.size)
        assertEquals(1, merged[1].stages.size)
    }

    @Test
    fun `merge returns empty list for empty input`() {
        val merged = HealthConnectManager.mergeOverlappingSleepSessions(emptyList())
        assertTrue(merged.isEmpty())
    }

    // -- Priority merging: detailed stages (Pixel Watch) preferred over non-detailed (Nest Hub) --

    @Test
    fun `merge prefers detailed stages and trims non-detailed for overlapping interval`() {
        // Nest Hub: single "sleeping" stage for the whole session
        val nestHub = SleepRecord(
            durationMinutes = 480,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T07:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 2, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-25T07:00:00Z")),
            )
        )
        // Pixel Watch: detailed stages for a sub-interval
        val pixelWatch = SleepRecord(
            durationMinutes = 420,
            startTime = instant("2026-02-24T23:30:00Z"),
            endTime = instant("2026-02-25T06:30:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:30:00Z"), endTime = instant("2026-02-25T01:00:00Z")),
                SleepStageRecord(stage = 5, startTime = instant("2026-02-25T01:00:00Z"), endTime = instant("2026-02-25T03:00:00Z")),
                SleepStageRecord(stage = 6, startTime = instant("2026-02-25T03:00:00Z"), endTime = instant("2026-02-25T05:00:00Z")),
                SleepStageRecord(stage = 4, startTime = instant("2026-02-25T05:00:00Z"), endTime = instant("2026-02-25T06:30:00Z")),
            )
        )

        val merged = HealthConnectManager.mergeOverlappingSleepSessions(listOf(nestHub, pixelWatch))

        assertEquals(1, merged.size)
        assertEquals(instant("2026-02-24T23:00:00Z"), merged[0].startTime)
        assertEquals(instant("2026-02-25T07:00:00Z"), merged[0].endTime)

        val stages = merged[0].stages
        assertEquals(6, stages.size)

        // Nest Hub fills the gap before Pixel Watch
        assertEquals(2, stages[0].stage)
        assertEquals(instant("2026-02-24T23:00:00Z"), stages[0].startTime)
        assertEquals(instant("2026-02-24T23:30:00Z"), stages[0].endTime)

        // Pixel Watch detailed stages
        assertEquals(4, stages[1].stage)
        assertEquals(instant("2026-02-24T23:30:00Z"), stages[1].startTime)
        assertEquals(5, stages[2].stage)
        assertEquals(6, stages[3].stage)
        assertEquals(4, stages[4].stage)
        assertEquals(instant("2026-02-25T06:30:00Z"), stages[4].endTime)

        // Nest Hub fills the gap after Pixel Watch
        assertEquals(2, stages[5].stage)
        assertEquals(instant("2026-02-25T06:30:00Z"), stages[5].startTime)
        assertEquals(instant("2026-02-25T07:00:00Z"), stages[5].endTime)
    }

    @Test
    fun `merge fully replaces non-detailed stages when detailed covers entire interval`() {
        val nestHub = SleepRecord(
            durationMinutes = 420,
            startTime = instant("2026-02-24T23:30:00Z"),
            endTime = instant("2026-02-25T06:30:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 2, startTime = instant("2026-02-24T23:30:00Z"), endTime = instant("2026-02-25T06:30:00Z")),
            )
        )
        val pixelWatch = SleepRecord(
            durationMinutes = 480,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T07:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-25T02:00:00Z")),
                SleepStageRecord(stage = 5, startTime = instant("2026-02-25T02:00:00Z"), endTime = instant("2026-02-25T05:00:00Z")),
                SleepStageRecord(stage = 6, startTime = instant("2026-02-25T05:00:00Z"), endTime = instant("2026-02-25T07:00:00Z")),
            )
        )

        val merged = HealthConnectManager.mergeOverlappingSleepSessions(listOf(nestHub, pixelWatch))

        assertEquals(1, merged.size)
        // Nest Hub completely covered by Pixel Watch → only Pixel Watch stages
        assertEquals(3, merged[0].stages.size)
        assertEquals(4, merged[0].stages[0].stage)
        assertEquals(5, merged[0].stages[1].stage)
        assertEquals(6, merged[0].stages[2].stage)
    }

    @Test
    fun `merge combines all stages when both sessions have detailed stages`() {
        val watch1 = SleepRecord(
            durationMinutes = 120,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T01:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-25T01:00:00Z")),
            )
        )
        val watch2 = SleepRecord(
            durationMinutes = 120,
            startTime = instant("2026-02-25T00:00:00Z"),
            endTime = instant("2026-02-25T02:00:00Z"),
            stages = listOf(
                SleepStageRecord(stage = 5, startTime = instant("2026-02-25T00:00:00Z"), endTime = instant("2026-02-25T02:00:00Z")),
            )
        )

        val merged = HealthConnectManager.mergeOverlappingSleepSessions(listOf(watch1, watch2))

        assertEquals(1, merged.size)
        // Both have detailed stages → all combined (no priority)
        assertEquals(2, merged[0].stages.size)
    }

    // -- hasDetailedStages --

    @Test
    fun `hasDetailedStages returns true for light deep or rem stages`() {
        assertTrue(HealthConnectManager.hasDetailedStages(listOf(
            SleepStageRecord(stage = 4, startTime = Instant.EPOCH, endTime = Instant.EPOCH.plusSeconds(3600))
        )))
        assertTrue(HealthConnectManager.hasDetailedStages(listOf(
            SleepStageRecord(stage = 5, startTime = Instant.EPOCH, endTime = Instant.EPOCH.plusSeconds(3600))
        )))
        assertTrue(HealthConnectManager.hasDetailedStages(listOf(
            SleepStageRecord(stage = 6, startTime = Instant.EPOCH, endTime = Instant.EPOCH.plusSeconds(3600))
        )))
    }

    @Test
    fun `hasDetailedStages returns false for non-detailed stages`() {
        assertFalse(HealthConnectManager.hasDetailedStages(listOf(
            SleepStageRecord(stage = 2, startTime = Instant.EPOCH, endTime = Instant.EPOCH.plusSeconds(3600))
        )))
        assertFalse(HealthConnectManager.hasDetailedStages(emptyList()))
    }

    // -- trimStageByIntervals --

    @Test
    fun `trimStageByIntervals splits stage around covered intervals`() {
        val stage = SleepStageRecord(
            stage = 2,
            startTime = instant("2026-02-24T23:00:00Z"),
            endTime = instant("2026-02-25T07:00:00Z")
        )
        val covered = listOf(
            SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:30:00Z"), endTime = instant("2026-02-25T06:30:00Z"))
        )

        val trimmed = HealthConnectManager.trimStageByIntervals(stage, covered)

        assertEquals(2, trimmed.size)
        assertEquals(instant("2026-02-24T23:00:00Z"), trimmed[0].startTime)
        assertEquals(instant("2026-02-24T23:30:00Z"), trimmed[0].endTime)
        assertEquals(instant("2026-02-25T06:30:00Z"), trimmed[1].startTime)
        assertEquals(instant("2026-02-25T07:00:00Z"), trimmed[1].endTime)
        assertEquals(2, trimmed[0].stage)
        assertEquals(2, trimmed[1].stage)
    }

    @Test
    fun `trimStageByIntervals removes stage completely covered`() {
        val stage = SleepStageRecord(
            stage = 2,
            startTime = instant("2026-02-25T00:00:00Z"),
            endTime = instant("2026-02-25T06:00:00Z")
        )
        val covered = listOf(
            SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-25T07:00:00Z"))
        )

        val trimmed = HealthConnectManager.trimStageByIntervals(stage, covered)

        assertTrue(trimmed.isEmpty())
    }

    @Test
    fun `trimStageByIntervals returns stage unchanged when no overlap`() {
        val stage = SleepStageRecord(
            stage = 2,
            startTime = instant("2026-02-24T20:00:00Z"),
            endTime = instant("2026-02-24T22:00:00Z")
        )
        val covered = listOf(
            SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-25T07:00:00Z"))
        )

        val trimmed = HealthConnectManager.trimStageByIntervals(stage, covered)

        assertEquals(1, trimmed.size)
        assertEquals(instant("2026-02-24T20:00:00Z"), trimmed[0].startTime)
        assertEquals(instant("2026-02-24T22:00:00Z"), trimmed[0].endTime)
    }

    @Test
    fun `trimStageByIntervals splits stage into three fragments with two covered intervals`() {
        val stage = SleepStageRecord(
            stage = 2,
            startTime = instant("2026-02-24T22:00:00Z"),
            endTime = instant("2026-02-25T08:00:00Z")
        )
        val covered = listOf(
            SleepStageRecord(stage = 4, startTime = instant("2026-02-24T23:00:00Z"), endTime = instant("2026-02-25T01:00:00Z")),
            SleepStageRecord(stage = 5, startTime = instant("2026-02-25T03:00:00Z"), endTime = instant("2026-02-25T06:00:00Z")),
        )

        val trimmed = HealthConnectManager.trimStageByIntervals(stage, covered)

        assertEquals(3, trimmed.size)
        // Before first covered interval
        assertEquals(instant("2026-02-24T22:00:00Z"), trimmed[0].startTime)
        assertEquals(instant("2026-02-24T23:00:00Z"), trimmed[0].endTime)
        // Gap between covered intervals
        assertEquals(instant("2026-02-25T01:00:00Z"), trimmed[1].startTime)
        assertEquals(instant("2026-02-25T03:00:00Z"), trimmed[1].endTime)
        // After last covered interval
        assertEquals(instant("2026-02-25T06:00:00Z"), trimmed[2].startTime)
        assertEquals(instant("2026-02-25T08:00:00Z"), trimmed[2].endTime)
    }
}
