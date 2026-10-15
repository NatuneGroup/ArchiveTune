/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune

import moe.rukamori.archivetune.constants.UpdateChannel

internal val isNightlyBuild: Boolean
    get() = BuildConfig.IS_NIGHTLY

internal val defaultUpdateChannel: UpdateChannel
    get() = if (isNightlyBuild) UpdateChannel.NIGHTLY else UpdateChannel.STABLE

/** Build identity supplies only the default; install eligibility is checked separately. */
internal fun effectiveUpdateChannel(
    stored: UpdateChannel?,
    isNightlyBuild: Boolean,
): UpdateChannel = stored ?: if (isNightlyBuild) UpdateChannel.NIGHTLY else UpdateChannel.STABLE

internal fun effectiveUpdateChannel(stored: UpdateChannel?): UpdateChannel = effectiveUpdateChannel(stored, isNightlyBuild)

internal val currentBuildHash: String?
    get() = BuildConfig.NIGHTLY_BUILD_HASH.takeIf { it.isNotBlank() }

internal fun formatVersionName(
    versionName: String = BuildConfig.VERSION_NAME,
    buildHash: String? = currentBuildHash,
): String = listOfNotNull(versionName.takeIf { it.isNotBlank() }, buildHash).joinToString(" ")
