/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.db.InternalDatabase
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.extensions.zipOutputStream
import moe.rukamori.archivetune.utils.dataStore
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class BackupArchiveStep {
    EXPORT_SETTINGS,
    CHECKPOINT_DATABASE,
    COPY_DATABASE_FILE,
    COPY_CUSTOM_FONTS,
}

data class BackupArchiveProgress(
    val step: BackupArchiveStep,
    val fileName: String? = null,
    val percent: Int,
    val indeterminate: Boolean,
)

@Singleton
class BackupArchiveRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val operationCoordinator: BackupOperationCoordinator,
    ) {

        suspend fun createBackup(
            uri: Uri,
            categories: Set<BackupArchiveCategory>,
            onProgress: (BackupArchiveProgress) -> Unit = {},
        ) = operationCoordinator.withLock {
            require(categories.isNotEmpty()) { "At least one backup category is required" }

            val includeSettings = BackupArchiveCategory.SETTINGS in categories
            val includeAccount = BackupArchiveCategory.ACCOUNT in categories
            val includeLibrary = BackupArchiveCategory.LIBRARY in categories
            val settingsExcludedKeys = if (includeAccount) emptySet() else ACCOUNT_PREFERENCE_KEYS
            val dbFile = context.getDatabasePath(InternalDatabase.DB_NAME)
            val dbFiles =
                if (includeLibrary) {
                    listOf(
                        dbFile,
                        dbFile.resolveSibling("${InternalDatabase.DB_NAME}-wal"),
                        dbFile.resolveSibling("${InternalDatabase.DB_NAME}-shm"),
                        dbFile.resolveSibling("${InternalDatabase.DB_NAME}-journal"),
                    ).filter { it.exists() }
                } else {
                    emptyList()
                }

            val totalUnits = (if (includeSettings) 1 else 0) + (if (includeLibrary) 1 else 0) + dbFiles.size
            val unitSpan = 100f / totalUnits.coerceAtLeast(1)
            var completedUnits = 0
            var lastProgress: BackupArchiveProgress? = null

            fun emit(
                step: BackupArchiveStep,
                fileName: String? = null,
                unitFraction: Float = 0f,
                indeterminate: Boolean = false,
            ) {
                val progress =
                    BackupArchiveProgress(
                        step = step,
                        fileName = fileName,
                        percent =
                            ((completedUnits + unitFraction.coerceIn(0f, 1f)) * unitSpan)
                                .roundToInt()
                                .coerceIn(0, 100),
                        indeterminate = indeterminate,
                    )
                if (progress != lastProgress) {
                    lastProgress = progress
                    onProgress(progress)
                }
            }

            val output =
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("Failed to open backup destination")
            output.buffered().zipOutputStream().use { zipStream ->
                if (includeSettings) {
                    emit(BackupArchiveStep.EXPORT_SETTINGS, indeterminate = true)
                    zipStream.putNextEntry(ZipEntry(SETTINGS_XML_FILENAME))
                    writeSettingsToXml(zipStream, settingsExcludedKeys)
                    zipStream.closeEntry()
                    completedUnits++

                    // Embed custom font .ttf files so the user's font choice survives a
                    // backup/restore cycle. The font URI/name preferences live in settings.xml,
                    // but the actual .ttf binary lives in filesDir/custom_fonts/ — without
                    // embedding the binary, restoring settings.xml would point at a font file
                    // that doesn't exist on the new install. We walk the directory and write
                    // each .ttf under a `fonts/` prefix in the ZIP.
                    val fontsDir = java.io.File(context.filesDir, CUSTOM_FONTS_DIR_NAME)
                    val fontFiles =
                        if (fontsDir.isDirectory) {
                            fontsDir.listFiles { file -> file.isFile && file.name.endsWith(".ttf", ignoreCase = true) }
                                ?.sortedBy { it.name }
                                ?: emptyList()
                        } else {
                            emptyList()
                        }
                    if (fontFiles.isNotEmpty()) {
                        val buffer = ByteArray(BUFFER_SIZE)
                        fontFiles.forEach { file ->
                            val fileSize = file.length().coerceAtLeast(1L)
                            var bytesCopied = 0L
                            emit(BackupArchiveStep.COPY_CUSTOM_FONTS, file.name)
                            zipStream.putNextEntry(ZipEntry("$FONTS_ZIP_PREFIX/${file.name}"))
                            FileInputStream(file).use { input ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    zipStream.write(buffer, 0, read)
                                    bytesCopied += read
                                    emit(
                                        step = BackupArchiveStep.COPY_CUSTOM_FONTS,
                                        fileName = file.name,
                                        unitFraction = bytesCopied.toFloat() / fileSize.toFloat(),
                                    )
                                }
                            }
                            zipStream.closeEntry()
                        }
                    }
                }

                if (includeLibrary) {
                    emit(BackupArchiveStep.CHECKPOINT_DATABASE, indeterminate = true)
                    database.awaitIdle()
                    database.checkpoint()
                    completedUnits++

                    val buffer = ByteArray(BUFFER_SIZE)
                    dbFiles.forEach { file ->
                        val fileSize = file.length().coerceAtLeast(1L)
                        var bytesCopied = 0L
                        emit(BackupArchiveStep.COPY_DATABASE_FILE, file.name)
                        zipStream.putNextEntry(ZipEntry(file.name))
                        FileInputStream(file).use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                zipStream.write(buffer, 0, read)
                                bytesCopied += read
                                emit(
                                    step = BackupArchiveStep.COPY_DATABASE_FILE,
                                    fileName = file.name,
                                    unitFraction = bytesCopied.toFloat() / fileSize.toFloat(),
                                )
                            }
                        }
                        zipStream.closeEntry()
                        completedUnits++
                    }
                }
            }
        }

        private suspend fun writeSettingsToXml(
            outputStream: OutputStream,
            excludedKeyNames: Set<String>,
        ) {
            val preferences =
                context.dataStore.data
                    .first()
                    .asMap()
            val serializer = android.util.Xml.newSerializer()
            serializer.setOutput(outputStream, Charsets.UTF_8.name())
            serializer.startDocument(Charsets.UTF_8.name(), true)
            serializer.startTag(null, "ArchiveTuneBackup")
            serializer.startTag(null, "Settings")

            preferences.forEach { (key, value) ->
                if (
                    key.name !in excludedKeyNames &&
                    key.name !in ScheduledBackupRepository.NON_PORTABLE_PREFERENCE_KEYS
                ) {
                    serializer.writePreference(key, value)
                }
            }

            serializer.endTag(null, "Settings")
            serializer.endTag(null, "ArchiveTuneBackup")
            serializer.endDocument()
            serializer.flush()
        }

        private fun org.xmlpull.v1.XmlSerializer.writePreference(
            key: Preferences.Key<*>,
            value: Any,
        ) {
            val tagName =
                when (value) {
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is String -> "string"
                    is Set<*> -> "string-set"
                    else -> return
                }
            startTag(null, tagName)
            attribute(null, "name", key.name)
            if (value is Set<*>) {
                value.forEach { item ->
                    startTag(null, "item")
                    text(item.toString())
                    endTag(null, "item")
                }
            } else {
                attribute(null, "value", value.toString())
            }
            endTag(null, tagName)
        }

        companion object {
            const val SETTINGS_XML_FILENAME = "settings.xml"
            const val CUSTOM_FONTS_DIR_NAME = "custom_fonts"
            const val FONTS_ZIP_PREFIX = "fonts"
            private const val BUFFER_SIZE = 64 * 1024

            val ACCOUNT_PREFERENCE_KEYS: Set<String> =
                setOf(
                    "innerTubeCookie",
                    "visitorData",
                    "dataSyncId",
                    "poToken",
                    "poTokenGvs",
                    "poTokenPlayer",
                    "poTokenSourceUrl",
                    "webClientPoTokenEnabled",
                    "accountName",
                    "accountEmail",
                    "accountChannelHandle",
                    "useLoginForBrowse",
                    "lastfmSession",
                    "lastfmUsername",
                    "lastfmProvider",
                    "lastfmCustomEndpoint",
                    "lastfmApiKeyOverride",
                    "lastfmSecretOverride",
                    "librefmApiKeyOverride",
                    "librefmSecretOverride",
                    "customScrobbleApiKeyOverride",
                    "customScrobbleSecretOverride",
                    "lastfmCredentialsMigrated",
                    "listenbrainz_token",
                    "discordToken",
                    "discordRefreshToken",
                    "discordTokenExpiresAt",
                    "discordUsername",
                    "discordName",
                    "discordAvatarUrl",
                    "poolTidalAccounts",
                    "poolQobuzAccounts",
                    "poolDeezerAccounts",
                    "poolAppleMusicAccounts",
                    "poolAmazonAccounts",
                    "poolAccountsLastRefreshAtMillis",
                    "proxyUsername",
                    "proxyPassword",
                    "spotify_sp_dc",
                    "spotify_sp_key",
                    "spotify_access_token",
                    "spotify_access_token_expires_at",
                    "spotify_account_name",
                    "spotify_account_avatar_url",
                    // Tidal login/session (sensitive) — travels with an Account backup, not a
                    // Settings-only one. Instance URL lists (tidalInstances) stay under Settings.
                    "tidalAccessToken",
                    "tidalRefreshToken",
                    "tidalTokenExpiry",
                    "tidalSubscription",
                    "tidalAuthFlow",
                    "tidalCountryCode",
                    "tidalUserId",
                    "tidalNeedsRelogin",
                    "tidal_account_name",
                    "tidalCookie",
                    // Qobuz direct-API tokens (contain user_auth_token + app_secret — sensitive).
                    "qobuzTokens",
                )
        }
    }

enum class BackupArchiveCategory {
    LIBRARY,
    ACCOUNT,
    SETTINGS,
}
