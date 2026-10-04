/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.SeekExtraSeconds
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.resolveSeekByTargetMs
import moe.rukamori.archivetune.utils.rememberPreference

internal const val SeekSkipStepMs = 5_000L

private const val SeekSkipRepeatWindowMs = 1_000L

@Stable
internal class SeekSkip(
    private val playerConnection: PlayerConnection,
    private val progressive: () -> Boolean,
) {
    private var lastSkipAt = 0L
    private var multiplier = 1

    fun skip(forward: Boolean): Long {
        val now = SystemClock.uptimeMillis()
        multiplier = if (progressive() && now - lastSkipAt < SeekSkipRepeatWindowMs) multiplier + 1 else 1
        lastSkipAt = now

        val player = playerConnection.player
        val step = SeekSkipStepMs * multiplier
        val delta = if (forward) step else -step
        player.seekTo(resolveSeekByTargetMs(player.currentPosition, delta, player.duration))
        playerConnection.service.forceDiscordSync("artwork_double_tap_seek")
        return step
    }
}

@Composable
internal fun rememberSeekSkip(playerConnection: PlayerConnection): SeekSkip {
    val (progressive) = rememberPreference(SeekExtraSeconds, defaultValue = false)
    val progressiveState = rememberUpdatedState(progressive)
    return remember(playerConnection) {
        SeekSkip(
            playerConnection = playerConnection,
            progressive = { progressiveState.value },
        )
    }
}

@Composable
internal fun Modifier.playerSeekDoubleTap(
    enabled: Boolean = true,
    onTap: (() -> Unit)? = null,
): Modifier {
    val playerConnection = LocalPlayerConnection.current ?: return this
    val seekSkip = rememberSeekSkip(playerConnection)
    val haptics = LocalHapticFeedback.current
    val (hapticsEnabled) = rememberPreference(EnableHapticFeedbackKey, true)
    val layoutDirection = LocalLayoutDirection.current
    val currentOnTap = rememberUpdatedState(onTap)
    return pointerInput(seekSkip, enabled, hapticsEnabled, layoutDirection) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onTap = { currentOnTap.value?.invoke() },
            onDoubleTap = { offset ->
                if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val forward =
                    if (layoutDirection == LayoutDirection.Ltr) {
                        offset.x >= size.width / 2f
                    } else {
                        offset.x < size.width / 2f
                    }
                seekSkip.skip(forward)
            },
        )
    }
}
