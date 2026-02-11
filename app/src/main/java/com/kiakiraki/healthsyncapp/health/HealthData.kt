package com.kiakiraki.healthsyncapp.health

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class HealthSummary(
    val latestWeightKg: Double? = null,
    val latestBodyFatPercent: Double? = null,
    val latestSystolicMmHg: Double? = null,
    val latestDiastolicMmHg: Double? = null,
    val latestHeartRateBpm: Long? = null,
    val totalStepsLast7Days: Long? = null,
    val totalSleepMinutesLast7Days: Long? = null,
    @Serializable(with = InstantSerializer::class)
    val lastUpdated: Instant? = null
)

sealed class HealthConnectState {
    data object Loading : HealthConnectState()
    data object NotSupported : HealthConnectState()
    data object PermissionsRequired : HealthConnectState()
    data class Success(val summary: HealthSummary) : HealthConnectState()
    data class Error(val message: String) : HealthConnectState()
}

sealed class SyncState {
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data object Success : SyncState()
    data class Error(val message: String) : SyncState()
}

@Serializable
data class WeightRecord(
    val weightKg: Double,
    @Serializable(with = InstantSerializer::class)
    val time: Instant
)

@Serializable
data class BloodPressureRecord(
    val systolicMmHg: Double,
    val diastolicMmHg: Double,
    @Serializable(with = InstantSerializer::class)
    val time: Instant
)

@Serializable
data class SleepRecord(
    val durationMinutes: Long,
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    @Serializable(with = InstantSerializer::class)
    val endTime: Instant
)

@Serializable
data class StepsRecord(
    val count: Long,
    @Serializable(with = InstantSerializer::class)
    val startTime: Instant,
    @Serializable(with = InstantSerializer::class)
    val endTime: Instant
)

@Serializable
data class BodyFatRecord(
    val percentage: Double,
    @Serializable(with = InstantSerializer::class)
    val time: Instant
)

@Serializable
data class HeartRateRecord(
    val beatsPerMinute: Long,
    @Serializable(with = InstantSerializer::class)
    val time: Instant
)

// API Request/Response data classes
@Serializable
data class HealthSyncRequest(
    @SerialName("body_measurements") val bodyMeasurements: List<BodyMeasurementApi>,
    @SerialName("blood_pressure") val bloodPressure: List<BloodPressureApi>,
    @SerialName("sleep_sessions") val sleepSessions: List<SleepSessionApi>,
    val steps: List<StepsApi>
)

@Serializable
data class BodyMeasurementApi(
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("weight_kg") val weightKg: Double?,
    @SerialName("body_fat_percent") val bodyFatPercent: Double?
)

@Serializable
data class BloodPressureApi(
    @SerialName("recorded_at") val recordedAt: String,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?
)

@Serializable
data class SleepSessionApi(
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("duration_hours") val durationHours: Double
)

@Serializable
data class StepsApi(
    val date: String,
    val count: Long
)

object InstantSerializer : kotlinx.serialization.KSerializer<Instant> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "Instant",
        kotlinx.serialization.descriptors.PrimitiveKind.LONG
    )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilli())
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Instant {
        return Instant.ofEpochMilli(decoder.decodeLong())
    }
}
