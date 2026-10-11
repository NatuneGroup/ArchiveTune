/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.compositionLocalOf

val LocalAnimationsDisabled = compositionLocalOf { false }

val LocalBlurDisabled = compositionLocalOf { false }

/**
 * The system's animator duration scale, as set in developer options.
 *
 * 1.0 is the default; 0.5 halves every duration; 0 turns animations off system-wide. Compose feeds
 * this into its own clock, so durations already shorten on their own — what it does not do is tell
 * a spring to settle in fewer oscillations, which is why a springy transition reads as stepping
 * rather than as speed once the scale drops. [LocalAnimationScale] carries the value so a spec can
 * answer for itself.
 */
val LocalAnimationScale = compositionLocalOf { 1f }

/** Reads the scale once. Returns 1 when the setting is missing, which is how a fresh device reads. */
fun Context.systemAnimationScale(): Float =
    runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

/**
 * App-wide scrollbar toggle. When `true`, all LazyColumn / LazyGrid /
 * ScrollState scrollbars in the app are suppressed. Provided by MainActivity
 * from the [HideScrollbarKey] preference; consumed by the scrollbar modifier
 * extension in [moe.rukamori.archivetune.ui.utils.ScrollUtils].
 */
val LocalHideScrollbar = compositionLocalOf { false }
