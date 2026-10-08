/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.googledrive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import moe.rukamori.archivetune.backup.ScheduledBackupFrequency
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the [GoogleDriveSyncWorker] using WorkManager, mirroring the pattern of
 * [moe.rukamori.archivetune.backup.ScheduledBackupScheduler].
 */
@Singleton
class GoogleDriveSyncScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun replace(settings: GoogleDriveSyncSettings) {
            val workManager = WorkManager.getInstance(context)
            val runAt =
                nextRunAt(settings) ?: run {
                    workManager.cancelUniqueWork(WORK_NAME)
                    return
                }
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                buildRequest(runAt),
            )
        }

        fun appendNext(settings: GoogleDriveSyncSettings) {
            if (settings.frequency == ScheduledBackupFrequency.CUSTOM) return
            val runAt = nextRunAt(settings) ?: return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(runAt),
            )
        }

        /** Enqueues an immediate one-shot sync (used by the "Sync now" UI action). */
        fun runNow() {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiresBatteryNotLow(true)
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build(),
                    ).build(),
            )
        }

        fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_NOW)
        }

        private fun buildRequest(runAt: ZonedDateTime) =
            OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                ).setInitialDelay(
                    Duration.between(ZonedDateTime.now(), runAt).toMillis().coerceAtLeast(MINIMUM_DELAY_MS),
                    TimeUnit.MILLISECONDS,
                ).build()

        private fun nextRunAt(settings: GoogleDriveSyncSettings): ZonedDateTime? {
            if (!settings.enabled || settings.remoteFolderUri == null) return null
            val now = ZonedDateTime.now()
            val nextDate =
                when (settings.frequency) {
                    ScheduledBackupFrequency.DAILY -> now.toLocalDate().plusDays(1)
                    ScheduledBackupFrequency.WEEKLY -> now.toLocalDate().plusWeeks(1)
                    ScheduledBackupFrequency.MONTHLY -> now.toLocalDate().plusMonths(1)
                    ScheduledBackupFrequency.CUSTOM -> {
                        val epochDay = settings.customDateEpochDay ?: return null
                        LocalDate
                            .ofEpochDay(epochDay)
                            .takeUnless { it.isBefore(now.toLocalDate()) } ?: return null
                    }
                }
            return nextDate.atTime(BACKUP_HOUR, 0).atZone(now.zone)
        }

        companion object {
            private const val WORK_NAME = "google_drive_sync_backup"
            private const val WORK_NAME_NOW = "google_drive_sync_backup_now"
            private const val BACKUP_HOUR = 2
            private const val MINIMUM_DELAY_MS = 1_000L
        }
    }
