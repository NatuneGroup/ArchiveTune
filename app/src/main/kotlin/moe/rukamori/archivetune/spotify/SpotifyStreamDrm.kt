/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The Widevine session the web-player ("Stopify") method needs: Spotify's CDN serves CENC
 * protected AAC, and the key comes from Spotify's web-player license endpoint, requested as the
 * streaming account. The catalog account's token never reaches this endpoint.
 */

package moe.rukamori.archivetune.spotify

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.UUID

object SpotifyStreamDrm {
    private const val LICENSE_URL = "https://gae2-spclient.spotify.com/widevine-license/v1/audio/license"

    /**
     * A fresh manager per call: a DefaultDrmSessionManager binds to the first player looper that
     * prepares it, and the crossfade player builds its own media source factory.
     */
    fun buildSessionManager(
        context: Context,
        httpClient: OkHttpClient,
    ): DrmSessionManager? =
        runCatching {
            DefaultDrmSessionManager
                .Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LicenseCallback(context.applicationContext, httpClient))
        }.getOrNull()

    private class LicenseCallback(
        private val context: Context,
        private val httpClient: OkHttpClient,
    ) : MediaDrmCallback {
        private val dataSourceFactory = OkHttpDataSource.Factory(httpClient)

        // Provisioning is a device matter between the CDM and Google's server; nothing Spotify-
        // or account-specific goes into it.
        private val provisioning = HttpMediaDrmCallback(null, dataSourceFactory)

        override fun executeProvisionRequest(
            uuid: UUID,
            request: ExoMediaDrm.ProvisionRequest,
        ): MediaDrmCallback.Response = provisioning.executeProvisionRequest(uuid, request)

        override fun executeKeyRequest(
            uuid: UUID,
            request: ExoMediaDrm.KeyRequest,
        ): MediaDrmCallback.Response {
            val token = runBlocking { SpotifyStreamingAccount.accessToken(context) } ?: fail("No streaming account token")
            val clientToken = runBlocking { SpotifyWebStreamer.clientToken() }
            val callback =
                HttpMediaDrmCallback(LICENSE_URL, true, dataSourceFactory).apply {
                    setKeyRequestProperty("authorization", "Bearer $token")
                    setKeyRequestProperty("client-token", clientToken)
                    setKeyRequestProperty("app-platform", "WebPlayer")
                    setKeyRequestProperty("spotify-app-version", SpotifyWebStreamer.SPOTIFY_APP_VERSION)
                    setKeyRequestProperty("origin", "https://open.spotify.com")
                    setKeyRequestProperty("referer", "https://open.spotify.com/")
                    setKeyRequestProperty("User-Agent", SpotifyWebStreamer.USER_AGENT)
                }
            return callback.executeKeyRequest(uuid, request)
        }

        private fun fail(message: String): Nothing {
            val uri = Uri.parse(LICENSE_URL)
            throw MediaDrmCallbackException(DataSpec(uri), uri, emptyMap(), 0L, IOException(message))
        }
    }
}
