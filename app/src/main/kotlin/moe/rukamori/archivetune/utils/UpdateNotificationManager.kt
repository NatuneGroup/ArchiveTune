/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.MainActivity
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EnableUpdateNotificationKey
import moe.rukamori.archivetune.constants.LastNotifiedVersionKey
import moe.rukamori.archivetune.constants.LastUpdateCheckChannelKey
import moe.rukamori.archivetune.constants.LastUpdateCheckKey
import moe.rukamori.archivetune.constants.UpdateChannel
import java.util.concurrent.TimeUnit

object UpdateNotificationManager {
    private const val CHANNEL_ID = "update_notification_channel"
    private const val NOTIFICATION_ID = 9999
    private const val WORK_NAME = "update_check_work"
    private const val STABLE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val NIGHTLY_CHECK_INTERVAL_MS = 15 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun createNotificationChannel(context: Context) {
        if (!BuildConfig.UPDATER_AVAILABLE) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.update_notification_channel_name)
            val descriptionText = context.getString(R.string.update_notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel =
                NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun schedulePeriodicUpdateCheck(context: Context) {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            cancelPeriodicUpdateCheck(context)
            return
        }

        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val updateCheckRequest =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                30,
                TimeUnit.MINUTES,
                10,
                TimeUnit.MINUTES,
            ).setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            // UPDATE (not KEEP): an app update must replace a schedule enqueued by an earlier
            // version, which may still carry a different interval.
            ExistingPeriodicWorkPolicy.UPDATE,
            updateCheckRequest,
        )
    }

    fun cancelPeriodicUpdateCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun checkForUpdates(context: Context) {
        if (!BuildConfig.UPDATER_AVAILABLE) {
            cancelPeriodicUpdateCheck(context)
            cancelUpdateNotification(context)
            return
        }

        scope.launch {
            try {
                val dataStore = context.dataStore

                val isEnabled = dataStore.data.map { it[EnableUpdateNotificationKey] ?: false }.first()
                if (!isEnabled) {
                    cancelPeriodicUpdateCheck(context)
                    return@launch
                }

                schedulePeriodicUpdateCheck(context)

                val revision = Updater.selectionRevision
                val preferences = dataStore.data.first()
                val updateChannel = Updater.selectedChannel(preferences)
                val lastCheck = if (preferences[LastUpdateCheckChannelKey] == updateChannel.name) {
                    preferences[LastUpdateCheckKey] ?: 0L
                } else 0L
                val now = System.currentTimeMillis()
                val checkInterval = when (updateChannel) {
                    UpdateChannel.NIGHTLY -> NIGHTLY_CHECK_INTERVAL_MS
                    UpdateChannel.STABLE -> STABLE_CHECK_INTERVAL_MS
                }
                if (now - lastCheck < checkInterval) return@launch
                if (!Updater.isCurrentSelection(updateChannel, revision)) return@launch
                dataStore.edit {
                    it[LastUpdateCheckKey] = now
                    it[LastUpdateCheckChannelKey] = updateChannel.name
                }
                Updater.getLatestRelease(updateChannel).onSuccess { release ->
                    Updater.eligibleOffer(release, updateChannel)?.let { offer ->
                        notifyIfNewVersion(context, offer, revision)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An unavailable feed must not disturb playback.
            }
        }
    }

    suspend fun notifyIfNewVersion(context: Context, offer: UpdateOffer, revision: Long) {
        if (!BuildConfig.UPDATER_AVAILABLE ||
            updateEligibility(offer.versionCode, BuildConfig.VERSION_CODE) != UpdateEligibility.NEWER
        ) return
        try {
            val preferences = context.dataStore.data.first()
            if (preferences[EnableUpdateNotificationKey] != true ||
                preferences[LastNotifiedVersionKey] == offer.identity ||
                !Updater.isCurrentSelection(offer.channel, revision)
            ) return
            // Selection invalidation and notification publication share the same short lock.
            Updater.publishIfCurrent(revision) { showUpdateNotification(context, offer) }
            context.dataStore.edit {
                if (Updater.selectionRevision == revision && Updater.selectedChannel(it) == offer.channel) {
                    it[LastNotifiedVersionKey] = offer.identity
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Silently fail, retaining the next check's opportunity to notify.
        }
    }

    private fun showUpdateNotification(
        context: Context,
        offer: UpdateOffer,
    ) {
        createNotificationChannel(context)

        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("navigate_to", "settings/update")
            }
        val openAppPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val downloadIntent = Intent(Intent.ACTION_VIEW, Uri.parse(offer.downloadUrl))
        val downloadPendingIntent =
            PendingIntent.getActivity(
                context,
                1,
                downloadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_notification_text, offer.versionName))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openAppPendingIntent)
                .setAutoCancel(true)
                .addAction(
                    R.drawable.download,
                    context.getString(R.string.download),
                    downloadPendingIntent,
                ).build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Missing POST_NOTIFICATIONS permission
        }
    }

    fun cancelUpdateNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
