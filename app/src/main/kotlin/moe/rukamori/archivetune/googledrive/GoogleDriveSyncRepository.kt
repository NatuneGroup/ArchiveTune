/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.googledrive

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.backup.ScheduledBackupFrequency
import moe.rukamori.archivetune.utils.dataStore
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore-backed repository for [GoogleDriveSyncSettings]. */
@Singleton
class GoogleDriveSyncRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val updateMutex = Mutex()

        fun observeSettings(): Flow<GoogleDriveSyncSettings?> =
            context.dataStore.data.map { preferences ->
                if (!preferences.contains(ENABLED_KEY) && !preferences.contains(REMOTE_FOLDER_URI_KEY)) {
                    return@map null
                }
                preferences.toSettings()
            }

        suspend fun getSettings(): GoogleDriveSyncSettings? =
            context.dataStore.data.first().let { preferences ->
                if (!preferences.contains(ENABLED_KEY) && !preferences.contains(REMOTE_FOLDER_URI_KEY)) {
                    null
                } else {
                    preferences.toSettings()
                }
            }

        suspend fun updateEnabled(enabled: Boolean): GoogleDriveSyncSettings =
            updateMutex.withLock {
                context.dataStore.edit { it[ENABLED_KEY] = enabled }.toSettings()
            }

        suspend fun updateFrequency(frequency: ScheduledBackupFrequency): GoogleDriveSyncSettings =
            updateMutex.withLock {
                context.dataStore
                    .edit { it[FREQUENCY_KEY] = frequency.name }
                    .toSettings()
            }

        suspend fun updateCustomDate(epochDay: Long): GoogleDriveSyncSettings =
            updateMutex.withLock {
                require(epochDay >= LocalDate.now().toEpochDay()) { "Sync date cannot be in the past" }
                context.dataStore
                    .edit {
                        it[FREQUENCY_KEY] = ScheduledBackupFrequency.CUSTOM.name
                        it[CUSTOM_DATE_KEY] = epochDay
                    }.toSettings()
            }

        /**
         * Persists the picked SAF folder tree URI and its display name. Pass nulls to clear the
         * folder (also disables auto-sync, since sync can't run without a target folder).
         */
        suspend fun updateRemoteFolder(uri: String?, name: String?): GoogleDriveSyncSettings =
            updateMutex.withLock {
                context.dataStore
                    .edit {
                        if (uri == null) it.remove(REMOTE_FOLDER_URI_KEY) else it[REMOTE_FOLDER_URI_KEY] = uri
                        if (name == null) it.remove(REMOTE_FOLDER_NAME_KEY) else it[REMOTE_FOLDER_NAME_KEY] = name
                    }.toSettings()
            }

        suspend fun updateOverwrite(overwrite: Boolean): GoogleDriveSyncSettings =
            updateMutex.withLock {
                context.dataStore.edit { it[OVERWRITE_KEY] = overwrite }.toSettings()
            }

        suspend fun recordSyncResult(success: Boolean): GoogleDriveSyncSettings =
            updateMutex.withLock {
                context.dataStore
                    .edit {
                        it[LAST_SYNC_MS_KEY] = System.currentTimeMillis()
                        it[LAST_SYNC_FAILED_KEY] = !success
                    }.toSettings()
            }

        /**
         * Clears the picked folder and disables auto-sync. Called when the user taps "Clear
         * folder" in the UI. The persistable URI permission for the old tree URI is released by
         * the caller (the UI holds the ContentResolver) before invoking this.
         */
        suspend fun clearRemoteFolder(): GoogleDriveSyncSettings =
            updateMutex.withLock {
                context.dataStore
                    .edit {
                        it.remove(REMOTE_FOLDER_URI_KEY)
                        it.remove(REMOTE_FOLDER_NAME_KEY)
                        it[ENABLED_KEY] = false
                    }.toSettings()
            }

        private fun androidx.datastore.preferences.core.Preferences.toSettings(): GoogleDriveSyncSettings =
            GoogleDriveSyncSettings(
                enabled = this[ENABLED_KEY] ?: false,
                frequency =
                    this[FREQUENCY_KEY]
                        ?.let { stored -> ScheduledBackupFrequency.entries.firstOrNull { it.name == stored } }
                        ?: ScheduledBackupFrequency.WEEKLY,
                customDateEpochDay = this[CUSTOM_DATE_KEY],
                remoteFolderUri = this[REMOTE_FOLDER_URI_KEY],
                remoteFolderName = this[REMOTE_FOLDER_NAME_KEY],
                overwriteExisting = this[OVERWRITE_KEY] ?: false,
                lastSyncEpochMs = this[LAST_SYNC_MS_KEY],
                lastSyncFailed = this[LAST_SYNC_FAILED_KEY] ?: false,
            )

        companion object {
            private val ENABLED_KEY = booleanPreferencesKey("googleDriveSyncEnabled")
            private val FREQUENCY_KEY = stringPreferencesKey("googleDriveSyncFrequency")
            private val CUSTOM_DATE_KEY = longPreferencesKey("googleDriveSyncCustomDateEpochDay")
            private val REMOTE_FOLDER_URI_KEY = stringPreferencesKey("googleDriveSyncRemoteFolderUri")
            private val REMOTE_FOLDER_NAME_KEY = stringPreferencesKey("googleDriveSyncRemoteFolderName")
            private val OVERWRITE_KEY = booleanPreferencesKey("googleDriveSyncOverwriteExisting")
            private val LAST_SYNC_MS_KEY = longPreferencesKey("googleDriveSyncLastSyncEpochMs")
            private val LAST_SYNC_FAILED_KEY = booleanPreferencesKey("googleDriveSyncLastSyncFailed")

            /**
             * Keys that should be excluded from portable SETTINGS backups because they reference
             * device-specific state (a SAF tree URI whose persistable permission only exists on
             * the granting device).
             */
            val NON_PORTABLE_PREFERENCE_KEYS: Set<String> =
                setOf(
                    ENABLED_KEY.name,
                    FREQUENCY_KEY.name,
                    CUSTOM_DATE_KEY.name,
                    REMOTE_FOLDER_URI_KEY.name,
                    REMOTE_FOLDER_NAME_KEY.name,
                    OVERWRITE_KEY.name,
                    LAST_SYNC_MS_KEY.name,
                    LAST_SYNC_FAILED_KEY.name,
                )
        }
    }
