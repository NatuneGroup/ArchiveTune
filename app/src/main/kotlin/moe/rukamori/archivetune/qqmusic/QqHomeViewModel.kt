/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The Home tab's QQ Music page, as data: the account's charts, each a section of playable songs.
 * The page belongs to the signed-in account, so its sections are fetched once per account rather
 * than on every visit — leaving the Home tab and coming back does not re-fetch, and using the page
 * while signed in as somebody else does not show the previous account's charts. Only a refresh asks
 * QQ again.
 * Every way this page can fail is a state rather than a throw: a feed that comes back useless is
 * the empty state, a feed that could not be fetched is an error carrying the reason it could not be
 * shown, and neither is something the screen has to guard against.
 */



package moe.rukamori.archivetune.qqmusic

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.reportException

/**
 * One section of the QQ Music page: a chart, and the songs it currently ranks.
 *
 * The songs are the app's own items rather than the catalogue's, because a section is played as a
 * queue and drawn as a list of the same items the rest of the app uses.
 */
@Immutable
data class QqHomeSection(
    val title: String,
    val label: String?,
    val tracks: List<MediaMetadata>,
)

/** What the QQ Music page is showing. */
sealed interface QqHomeScreenState {
    data object Loading : QqHomeScreenState

    data class Success(val sections: List<QqHomeSection>) : QqHomeScreenState

    data object Empty : QqHomeScreenState

    /** [messageResId] is the reason a refresh is being offered for. */
    data class Error(@StringRes val messageResId: Int) : QqHomeScreenState
}

@HiltViewModel
class QqHomeViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _screenState = MutableStateFlow<QqHomeScreenState>(QqHomeScreenState.Loading)
        val screenState: StateFlow<QqHomeScreenState> = _screenState.asStateFlow()

        /** The account the sections on screen were fetched for; a different one invalidates them. */
        private var loadedUin: String? = null
        private var loadJob: Job? = null

        init {
            load(force = false)
        }

        /** Fetches the page again, whatever is already on screen. */
        fun refresh() = load(force = true)

        private fun load(force: Boolean) {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch(Dispatchers.IO) {
                    val session = QqMusicSession.read(context.dataStore)
                    if (session == null) {
                        // Without a session there is no page to be had; the switcher stops offering
                        // it, and this is what the screen shows if it was already open when the
                        // account went away.
                        loadedUin = null
                        _screenState.value = QqHomeScreenState.Error(R.string.home_screens_needs_sign_in)
                        return@launch
                    }
                    if (!force && session.uin == loadedUin && _screenState.value is QqHomeScreenState.Success) {
                        return@launch
                    }

                    _screenState.value = QqHomeScreenState.Loading
                    val charts =
                        try {
                            QqMusicApi.home(session)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // The client is written never to throw, so this only carries a fault it
                            // could not have seen coming. It is caught all the same: the page's
                            // promise is that a failed feed is an error state, not a crash.
                            reportException(error)
                            null
                        }
                    // A refresh replaces this job; a cancelled one must not write its result over
                    // the newer one's.
                    currentCoroutineContext().ensureActive()

                    if (charts == null) {
                        // Nothing was fetched, so nothing is cached for this account either: the
                        // next visit tries again rather than showing a page that never loaded.
                        _screenState.value = QqHomeScreenState.Error(R.string.qq_home_load_failed)
                        return@launch
                    }

                    loadedUin = session.uin
                    val sections = charts.map { it.toSection() }
                    _screenState.value =
                        if (sections.isEmpty()) {
                            QqHomeScreenState.Empty
                        } else {
                            QqHomeScreenState.Success(sections)
                        }
                }
        }
    }

/** The chart as the page draws it, with the app's own item per song. */
private fun QqHomeChart.toSection(): QqHomeSection =
    QqHomeSection(
        title = title,
        label = label,
        tracks = tracks.map { it.toMediaMetadata() },
    )

/**
 * The app's item for a QQ catalogue track: a row on the QQ Music page, and the queue entry the
 * player resolves back to this account's QQ Music.
 *
 * The id is namespaced because it is a QQ media id and not a YouTube one — nothing may later
 * address it as a video — while the metadata travels on the item itself, which is what the audio
 * source chain searches with when the track is played. The artists stay joined into the one string
 * the QQ catalogue returns them as, because that is the string the metadata match compares against.
 * No album is set: a QQ album id addresses nothing on the side of the app the album route opens.
 */
internal fun QqTrack.toMediaMetadata(): MediaMetadata =
    MediaMetadata(
        id = "$QQ_MEDIA_ID_PREFIX$mid",
        title = title.orEmpty(),
        artists = listOf(MediaMetadata.Artist(id = null, name = artist.orEmpty())),
        duration = durationMs?.let { (it / 1000L).toInt() } ?: QQ_UNKNOWN_DURATION,
        thumbnailUrl = coverUrl,
    )

/** Marks an id as QQ's, so it is never mistaken for a YouTube video id. */
private const val QQ_MEDIA_ID_PREFIX = "qq:"

/** The app's "no duration supplied" value, which the player and the rows both read as unknown. */
private const val QQ_UNKNOWN_DURATION = -1
