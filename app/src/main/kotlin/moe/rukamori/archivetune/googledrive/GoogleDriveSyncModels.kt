/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.googledrive

import moe.rukamori.archivetune.backup.ScheduledBackupFrequency

/** Settings for Google Drive auto-sync of app backups. */
data class GoogleDriveSyncSettings(
    val enabled: Boolean = false,
    val frequency: ScheduledBackupFrequency = ScheduledBackupFrequency.WEEKLY,
    val customDateEpochDay: Long? = null,
    val remoteFolderUri: String? = null,
    val remoteFolderName: String? = null,
    val overwriteExisting: Boolean = false,
    val lastSyncEpochMs: Long? = null,
    val lastSyncFailed: Boolean = false,
)
