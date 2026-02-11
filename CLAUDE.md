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

This is an Android health tracking app that reads data from Google Health Connect and syncs to a cloud backend.

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
- `HealthConnectManager`: Handles permissions, data queries, and aggregations
- `HealthData.kt`: Data models using sealed classes for state (`HealthConnectState`, `SyncState`)
- Reads: Weight, Body Fat, Blood Pressure, Heart Rate, Sleep, Steps

**API Layer** (`api/HealthSyncApiClient.kt`):
- Ktor client with Bearer token auth
- Endpoint URL is injected via `BuildConfig.HEALTH_SYNC_API_URL`
- Custom `InstantSerializer` for epoch millisecond serialization

### Configuration

- API key and endpoint URL are stored in `local.properties` (gitignored) and injected into `BuildConfig`
- See `local.properties.example` for the required properties
- Target SDK: 36 (Android 15+), no backward compatibility
- Version catalog in `gradle/libs.versions.toml`

### Dependencies

- Kotlin 2.0.21 with kotlinx.serialization
- Jetpack Compose (2024.09.00 BOM) + Material 3
- Health Connect Client 1.1.0-alpha10
- Ktor Client 2.3.7
