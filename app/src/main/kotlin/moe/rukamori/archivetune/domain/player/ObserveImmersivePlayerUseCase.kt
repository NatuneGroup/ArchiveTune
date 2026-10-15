/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.domain.player

import androidx.media3.common.Player
import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.canvas.CanvasVideo
import moe.rukamori.archivetune.db.entities.containerLabel
import moe.rukamori.archivetune.db.entities.formattedBitrate
import moe.rukamori.archivetune.db.entities.formattedSampleRate
import moe.rukamori.archivetune.playback.ImmersivePlayerData
import moe.rukamori.archivetune.playback.ImmersivePlayerRepository
import moe.rukamori.archivetune.ui.player.immersive.ImmersivePlayerArtist
import moe.rukamori.archivetune.ui.player.immersive.ImmersivePlayerUiModel
import moe.rukamori.archivetune.ui.player.streamQuality
import javax.inject.Inject

class ObserveImmersivePlayerUseCase @Inject constructor(
    private val repository: ImmersivePlayerRepository,
) {
    operator fun invoke(): Flow<ImmersivePlayerData?> = repository.observePlayer()

    fun map(data: ImmersivePlayerData, canvas: CanvasVideo? = null): ImmersivePlayerUiModel {
        val metadata = data.metadata
        val formatDetails =
            data.format?.let { format ->
                listOfNotNull(
                    format.containerLabel().takeIf(String::isNotBlank),
                    format.formattedSampleRate(),
                    format.formattedBitrate(),
                ).joinToString(separator = " · ")
            }.orEmpty()
        return ImmersivePlayerUiModel(
            mediaId = metadata.id,
            title = metadata.title,
            artists = metadata.artists.map { ImmersivePlayerArtist(it.id, it.name) },
            albumId = metadata.album?.id,
            sourceTitle = metadata.album?.title?.takeIf(String::isNotBlank) ?: data.queueTitle.orEmpty(),
            artworkUrl = metadata.thumbnailUrl?.takeIf(String::isNotBlank)
                ?: data.song?.takeIf { it.id == metadata.id }?.thumbnailUrl?.takeIf(String::isNotBlank),
            isPlaying = data.isPlaying,
            isBuffering = data.playbackState == Player.STATE_BUFFERING,
            canSkipPrevious = data.canSkipPrevious,
            canSkipNext = data.canSkipNext,
            isLiked = data.song?.song?.liked == true,
            formatDetails = formatDetails,
            streamQuality = data.format?.streamQuality(),
            outputDevice = data.outputDevice,
            canvas = canvas,
            durationMs = metadata.duration.coerceAtLeast(0) * 1_000L,
        )
    }
}
