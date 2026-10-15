/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.Queue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.spotify.SPOTIFY_CATALOG_RESOLVE_TIMEOUT_MS
import moe.rukamori.archivetune.spotify.SpotifyReleaseTarget
import moe.rukamori.archivetune.spotify.SpotifyTracksQueue
import moe.rukamori.archivetune.spotify.resolveSpotifyAlbumTarget
import moe.rukamori.archivetune.spotify.resolveSpotifyArtistId
import moe.rukamori.archivetune.utils.reportException

/** Opens a tapped Spotify album or artist in the app; [resolvingKey] is the tap in flight. */
@Stable
class SpotifyCatalogOpener internal constructor(
    private val context: Context,
    private val navController: NavController,
    private val playerConnection: PlayerConnection?,
    private val scope: CoroutineScope,
) {
    var resolvingKey: String? by mutableStateOf(null)
        private set

    fun openAlbum(
        key: String,
        albumId: String,
        albumName: String,
        artistName: String?,
    ) = open(key) {
        when (val target = resolveSpotifyAlbumTarget(albumId, albumName, artistName)) {
            is SpotifyReleaseTarget.AlbumPage -> navigateTo("album/${target.browseId}")
            is SpotifyReleaseTarget.Song -> play(YouTubeQueue.radio(target.song.toMediaMetadata()))
            is SpotifyReleaseTarget.SpotifyTracks ->
                play(SpotifyTracksQueue(title = albumName, initialTracks = target.tracks))
            null -> null
        }
    }

    fun openArtist(
        key: String,
        artistName: String,
    ) = open(key) {
        resolveSpotifyArtistId(artistName)?.let { artistId -> navigateTo("artist/$artistId") }
    }

    private fun navigateTo(route: String): () -> Unit = { navController.navigate(route) }

    private fun play(queue: Queue): () -> Unit = { playerConnection?.playQueue(queue) }

    private fun open(
        key: String,
        resolve: suspend () -> (() -> Unit)?,
    ) {
        if (resolvingKey != null) return
        resolvingKey = key
        scope.launch {
            try {
                val land = withTimeoutOrNull(SPOTIFY_CATALOG_RESOLVE_TIMEOUT_MS) { withContext(Dispatchers.IO) { resolve() } }
                if (land != null) land() else showNothingFound()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportException(error)
                showNothingFound()
            } finally {
                resolvingKey = null
            }
        }
    }

    private fun showNothingFound() {
        Toast.makeText(context, context.getString(R.string.no_results_found), Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun rememberSpotifyCatalogOpener(navController: NavController): SpotifyCatalogOpener {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    return remember(context, navController, playerConnection, scope) {
        SpotifyCatalogOpener(context, navController, playerConnection, scope)
    }
}
