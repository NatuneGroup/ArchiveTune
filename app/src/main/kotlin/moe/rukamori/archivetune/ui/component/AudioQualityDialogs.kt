/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * Copyright © Rukamori
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.QobuzAudioQuality

/**
 * Quality tiers offered when starting a download, expressed in terms a listener recognises rather
 * than the provider's internal format ids.
 */
enum class DownloadQualityChoice(
    val qobuzQuality: QobuzAudioQuality,
) {
    /** Whatever the source offers, up to 24-bit/192 kHz. */
    MAX(QobuzAudioQuality.MAX),

    /** 24-bit, typically 96 kHz. */
    HI_RES(QobuzAudioQuality.HI_RES),

    /** CD quality: 16-bit/44.1 kHz, still lossless. */
    LOSSLESS(QobuzAudioQuality.FLAC),
    ;

    companion object {
        /**
         * Falls back to [LOSSLESS] instead of throwing: this only picks the dialog's initial
         * selection, so a new Qobuz tier appearing upstream should not be able to crash the song
         * menu just because it has no radio button yet.
         */
        fun forQobuzQuality(quality: QobuzAudioQuality): DownloadQualityChoice =
            entries.firstOrNull { it.qobuzQuality == quality } ?: LOSSLESS
    }
}

private val DownloadQualityChoice.titleRes: Int
    get() =
        when (this) {
            DownloadQualityChoice.MAX -> R.string.download_quality_max
            DownloadQualityChoice.HI_RES -> R.string.download_quality_hi_res
            DownloadQualityChoice.LOSSLESS -> R.string.download_quality_lossless
        }

/**
 * Must stay in step with [moe.rukamori.archivetune.download.AudioContainer]: any container the
 * sniffer can report needs an entry here, or a track in that container has no offerable target.
 *
 * Note YouTube Opus is cached in a WebM container, so an `OPUS` entry would never match anything.
 */
enum class ExportFormat(
    val extension: String,
    val isLossless: Boolean,
) {
    FLAC("flac", isLossless = true),
    WAV("wav", isLossless = true),
    M4A("m4a", isLossless = false),
    WEBM("webm", isLossless = false),
    OGG("ogg", isLossless = false),
    MP3("mp3", isLossless = false),
    ;

    companion object {
        fun forExtension(extension: String?): ExportFormat? {
            val normalised = extension?.lowercase()?.removePrefix(".") ?: return null
            return entries.firstOrNull { it.extension == normalised }
        }
    }
}

private val ExportFormat.titleRes: Int
    get() =
        when (this) {
            ExportFormat.FLAC -> R.string.export_format_flac
            ExportFormat.WAV -> R.string.export_format_wav
            ExportFormat.M4A -> R.string.export_format_m4a
            ExportFormat.WEBM -> R.string.export_format_webm
            ExportFormat.OGG -> R.string.export_format_ogg
            ExportFormat.MP3 -> R.string.export_format_mp3
        }
