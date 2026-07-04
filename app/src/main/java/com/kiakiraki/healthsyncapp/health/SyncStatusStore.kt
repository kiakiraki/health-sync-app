package com.kiakiraki.healthsyncapp.health

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

/**
 * Persists when each sync direction last succeeded, so the UI can show it
 * across app restarts. Written by both the ViewModel (manual sync) and
 * HealthSyncWorker (periodic background sync).
 */
class SyncStatusStore(context: Context) {

    private val prefs = context.getSharedPreferences("sync_status", Context.MODE_PRIVATE)

    var lastCloudSyncAt: Instant?
        get() = readInstant(KEY_LAST_CLOUD_SYNC_AT)
        set(value) = writeInstant(KEY_LAST_CLOUD_SYNC_AT, value)

    var lastMealSyncAt: Instant?
        get() = readInstant(KEY_LAST_MEAL_SYNC_AT)
        set(value) = writeInstant(KEY_LAST_MEAL_SYNC_AT, value)

    /**
     * The listener fires when the background worker updates a timestamp
     * while the screen is open. SharedPreferences holds listeners weakly;
     * the caller must keep a strong reference.
     */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun readInstant(key: String): Instant? =
        prefs.getLong(key, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    private fun writeInstant(key: String, value: Instant?) {
        prefs.edit().putLong(key, value?.toEpochMilli() ?: 0L).apply()
    }

    companion object {
        private const val KEY_LAST_CLOUD_SYNC_AT = "last_cloud_sync_at"
        private const val KEY_LAST_MEAL_SYNC_AT = "last_meal_sync_at"
    }
}
