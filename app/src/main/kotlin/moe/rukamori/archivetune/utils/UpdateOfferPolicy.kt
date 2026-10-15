/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.constants.UpdateChannel

/** A resolved release is immutable from its eligibility check through the user's action. */
data class UpdateOffer(
    val channel: UpdateChannel,
    val tag: String,
    val versionCode: Int,
    val versionName: String,
    val notes: String?,
    val downloadUrl: String,
) {
    val identity: String get() = "${channel.name}|$tag|$versionCode"
}

internal enum class UpdateEligibility { NEWER, NOT_NEWER, UNKNOWN_BUILD_CODE }

internal fun updateEligibility(candidateCode: Int?, installedCode: Int): UpdateEligibility =
    when {
        candidateCode == null || candidateCode <= 0 -> UpdateEligibility.UNKNOWN_BUILD_CODE
        candidateCode > installedCode -> UpdateEligibility.NEWER
        else -> UpdateEligibility.NOT_NEWER
    }

internal fun updateRequestIsCurrent(
    requestChannel: UpdateChannel,
    requestRevision: Long,
    selectedChannel: UpdateChannel,
    selectionRevision: Long,
): Boolean = requestChannel == selectedChannel && requestRevision == selectionRevision

internal fun archiveMatchesOffer(
    candidateCode: Int,
    archiveCode: Long?,
    installedCode: Int,
    sourceMatches: Boolean,
): Boolean = sourceMatches && updateEligibility(candidateCode, installedCode) == UpdateEligibility.NEWER &&
    archiveCode == candidateCode.toLong()
