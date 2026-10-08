/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.googledrive

import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.backup.ScheduledBackupFrequency
import javax.inject.Inject

/**
 * Use cases for Google Drive sync — mirrors the structure of
 * [moe.rukamori.archivetune.backup.ObserveScheduledBackupSettingsUseCase] /
 * [moe.rukamori.archivetune.backup.UpdateScheduledBackupUseCase].
 */
class ObserveGoogleDriveSyncSettingsUseCase
    @Inject
    constructor(
        private val repository: GoogleDriveSyncRepository,
    ) {
        operator fun invoke(): Flow<GoogleDriveSyncSettings?> = repository.observeSettings()
    }

class UpdateGoogleDriveSyncUseCase
    @Inject
    constructor(
        private val repository: GoogleDriveSyncRepository,
        private val scheduler: GoogleDriveSyncScheduler,
    ) {
        suspend fun setEnabled(enabled: Boolean): GoogleDriveSyncSettings =
            repository.updateEnabled(enabled).also(scheduler::replace)

        suspend fun setFrequency(frequency: ScheduledBackupFrequency): GoogleDriveSyncSettings =
            repository.updateFrequency(frequency).also(scheduler::replace)

        suspend fun setCustomDate(epochDay: Long): GoogleDriveSyncSettings =
            repository.updateCustomDate(epochDay).also(scheduler::replace)

        suspend fun setRemoteFolder(uri: String?, name: String?): GoogleDriveSyncSettings =
            repository.updateRemoteFolder(uri, name)

        suspend fun setOverwrite(overwrite: Boolean): GoogleDriveSyncSettings =
            repository.updateOverwrite(overwrite)

        /**
         * Clears the picked folder and disables auto-sync. Cancels the scheduled WorkManager job
         * since sync can't run without a target folder.
         */
        suspend fun clearRemoteFolder(): GoogleDriveSyncSettings =
            repository.clearRemoteFolder().also { scheduler.cancel() }

        fun runNow() = scheduler.runNow()
    }
