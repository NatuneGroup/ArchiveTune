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

/**
 * The single channel rule every update path (launch popup, Updates screen, changelog, background
 * worker, notifications) goes through. A nightly build is always locked to NIGHTLY: its versionCode
 * only ever moves along the nightly line, so honouring a stored STABLE would offer a downgrade the
 * system installer rejects. Every other build follows the stored choice, defaulting to STABLE.
 * Pure so the rule stays unit-testable; the one-argument overload reads the build flag.
 */
internal fun effectiveUpdateChannel(
    stored: UpdateChannel?,
    isNightlyBuild: Boolean,
): UpdateChannel =
    when {
        isNightlyBuild -> UpdateChannel.NIGHTLY
        stored != null -> stored
        else -> UpdateChannel.STABLE
    }

internal fun effectiveUpdateChannel(stored: UpdateChannel?): UpdateChannel = effectiveUpdateChannel(stored, isNightlyBuild)

internal val currentBuildHash: String?
    get() = BuildConfig.NIGHTLY_BUILD_HASH.takeIf { it.isNotBlank() }

internal fun formatVersionName(
    versionName: String = BuildConfig.VERSION_NAME,
    buildHash: String? = currentBuildHash,
): String = listOfNotNull(versionName.takeIf { it.isNotBlank() }, buildHash).joinToString(" ")
