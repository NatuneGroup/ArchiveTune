/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import moe.rukamori.archivetune.constants.QobuzEnabledKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.tidal.TidalInstanceHealthManager
import timber.log.Timber

/**
 * Keeps the source instance list and the pooled accounts fresh in the background.
 *
 * Both refreshes existed already and neither ever ran on its own: `TidalInstanceHealthManager
 * .refresh` was reachable only from the two settings screens, so an instance list went stale the
 * moment the reader stopped opening settings and tapping refresh, and a dead instance stayed in the
 * rotation until they did. This is the schedule that was missing, not a second implementation —
 * both calls below are the same ones the settings buttons make.
 *
 * Each refresh already throttles itself: the health scan drops a call while another scan is in
 * flight, and the pool returns early inside its own interval unless forced. So this worker firing
 * on a device that is already up to date costs one preference read.
 */
class SourceRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val instancesWanted =
            context.dataStore.get(TidalEnabledKey, true) || context.dataStore.get(QobuzEnabledKey, false)

        return try {
            if (instancesWanted) {
                // staggered: this runs unattended, so there is no reason to hit every instance at
                // once the way the foreground "check now" button does.
                val records = TidalInstanceHealthManager.refresh(context, includeDiscovery = false, staggered = true)
                Timber.tag(TAG).d("Instance refresh done: %d healthy of %d", records.count { it.isHealthy }, records.size)
            }
            PoolAccountManager.refresh(context)
            Result.success()
        } catch (error: Throwable) {
            // Retry rather than fail: the usual cause is the network dropping mid-scan, and
            // WorkManager's backoff is exactly the right response to that.
            Timber.tag(TAG).w(error, "Source refresh failed; will retry")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SourceRefresh"
        private const val WORK_NAME = "source_instance_refresh"

        /**
         * Registers the recurring refresh. Safe to call on every launch — KEEP means an existing
         * schedule is left alone rather than restarted, so a reader who opens the app ten times in
         * an hour does not push the next run ten times further out.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SourceRefreshWorker>(REFRESH_INTERVAL.inWholeHours, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Six hours. Instances are volunteer-run and go down on their own schedule, so a day is too
         * slow to notice; anything much shorter spends a reader's battery re-confirming a list that
         * rarely changes within one listening session.
         */
        private val REFRESH_INTERVAL = 6.hours
    }
}
