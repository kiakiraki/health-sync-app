# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build
./gradlew build              # Full build
./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK

# Test
./gradlew test               # Unit tests
./gradlew connectedAndroidTest  # Instrumented tests (requires device/emulator)
./gradlew testDebugUnitTest --tests "ClassName.testName"  # Single test

# Lint
./gradlew lint               # Run Android lint checks
```

## Architecture

This is an Android health tracking app that reads data from Google Health Connect and syncs to a cloud backend. It also fetches meal data from the backend and writes it into Health Connect as NutritionRecords.

### Layer Structure

```
MainActivity.kt (UI - Jetpack Compose)
    ↓
HealthConnectManager.kt (Domain - Health Connect API wrapper)
    ↓
HealthSyncApiClient.kt (API - Ktor HTTP client for cloud sync)
```

### Key Components

**UI Layer** (`MainActivity.kt`):
- Single-activity Compose app with Material 3
- State managed via `mutableStateOf` and `rememberCoroutineScope`
- Main composables: `HealthSyncScreen`, `HealthDataDisplay`, `HealthCard`

**Health Layer** (`health/`):
- `HealthConnectManager`: Handles permissions, data queries, aggregations, and nutrition writes
- `HealthData.kt`: Data models using sealed classes for state (`HealthConnectState`, `SyncState`, `MealSyncState`)
- Reads: Weight, Body Fat, Blood Pressure, Heart Rate, Resting Heart Rate, SpO2, Active/Total Calories (daily aggregates), Sleep, Steps
- Writes: Nutrition (meals fetched from the backend; upserted via `clientRecordId = "meal-<id>"` — the app has `WRITE_NUTRITION` only, so reading others' records is not possible and own records must be read with `dataOriginFilter`)

**API Layer** (`api/HealthSyncApiClient.kt`):
- Ktor client with Bearer token auth
- Endpoint URL is injected via `BuildConfig.HEALTH_SYNC_API_URL`
- Custom `InstantSerializer` for epoch millisecond serialization
- `fetchMeals()`: Retrieves meal records (last 7 days) for writing to Health Connect
- `buildSyncRequests()`: Builds the `/sync` payloads. The server handles one `/sync` call in a single D1 transaction, so high-frequency samples are split: combined `heart_rate` + `spo2` samples are capped at `MAX_SAMPLES_PER_REQUEST` (1000) per POST; overflow goes into follow-up requests carrying only those samples. All other metrics ride on the first request. Server upserts are idempotent (`recorded_at`/`date` unique keys), so splitting and retrying are safe — callers must POST every returned request

### Configuration

- API key and endpoint URL are stored in `local.properties` (gitignored) and injected into `BuildConfig`
- See `local.properties.example` for the required properties
- compileSdk 37 / minSdk & targetSdk 36, no backward compatibility
- Version catalog in `gradle/libs.versions.toml` (single source of truth for the versions below)

### Dependencies

- Kotlin 2.4.0 with kotlinx.serialization
- Jetpack Compose (2026.04.01 BOM) + Material 3
- Health Connect Client 1.2.0-alpha05
- Ktor Client 3.5.1
- WorkManager 2.11.2 (declared but no Worker implemented yet)
