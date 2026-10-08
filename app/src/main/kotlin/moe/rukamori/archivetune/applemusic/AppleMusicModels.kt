/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.applemusic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesResult> = emptyList(),
)

@Serializable
data class ITunesResult(
    val wrapperType: String? = null,
    val kind: String? = null,
    val artistId: Long? = null,
    val collectionId: Long? = null,
    val trackId: Long? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val trackName: String? = null,
    val artistViewUrl: String? = null,
    val collectionViewUrl: String? = null,
    val trackViewUrl: String? = null,
    val previewUrl: String? = null,
    val artworkUrl100: String? = null,
    val releaseDate: String? = null,
    val trackCount: Int? = null,
    val trackTimeMillis: Long? = null,
    val trackNumber: Int? = null,
    val discCount: Int? = null,
    val discNumber: Int? = null,
    val primaryGenreName: String? = null,
    val trackExplicitness: String? = null,
    val collectionExplicitness: String? = null,
    val isStreamable: Boolean? = null,
    @SerialName("collectionArtistName")
    val collectionArtistName: String? = null,
)
