/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import moe.rukamori.archivetune.LocalAnimationScale
import moe.rukamori.archivetune.constants.BackAnimationStyle
import moe.rukamori.archivetune.scaleAwareSpring

/**
 * The app-wide back animation style, read from [BackAnimationStyleKey] in MainActivity.
 *
 * PREDICTIVE surfaces scale away under the back gesture and spring back when it is cancelled;
 * ORIGINAL keeps the classic instant dispatch, where back simply runs the close action.
 */
val LocalBackAnimationStyle = staticCompositionLocalOf { BackAnimationStyle.PREDICTIVE }

/**
 * Which surface owns the next back press while the player is involved. Published by
 * BottomSheetPlayer into a [PlayerBackController] and consumed by the two player back handlers
 * composed after the NavHost in MainActivity, so there is exactly one dispatcher for the
 * expanded sheet and every page or overlay stacked on it.
 */
enum class PlayerBackTarget {
    NONE,
    SHEET,
    SUB_PAGE,
}

/**
 * The single owner of the player's back target. BottomSheetPlayer publishes the target and the
 * action for whatever is on top (queue, lyrics, a player style's page, the sheet itself);
 * MainActivity's handlers route the gesture to it.
 */
@Stable
class PlayerBackController {
    var backTarget by mutableStateOf(PlayerBackTarget.NONE)

    var routeBack: () -> Unit = {}
}

/**
 * Gesture progress for one predictive back interaction with a sheet: 0 at rest, rising with the
 * finger while the gesture is active, sprung back to 0 when it ends without committing.
 */
@Stable
class PredictiveSheetBackState {
    val progress: Animatable<Float, AnimationVector1D> = Animatable(0f)

    var gestureActive by mutableStateOf(false)
}

@Composable
fun rememberPredictiveSheetBackState(): PredictiveSheetBackState {
    val state = remember { PredictiveSheetBackState() }
    val animationScale = LocalAnimationScale.current
    LaunchedEffect(state.gestureActive, animationScale) {
        if (!state.gestureActive && state.progress.value > 0f) {
            state.progress.animateTo(
                targetValue = 0f,
                animationSpec =
                    scaleAwareSpring(
                        animationScale,
                        dampingRatio = 0.85f,
                        stiffness = 380f,
                    ),
            )
        }
    }
    return state
}

/**
 * Back handling that shows the gesture's effect while it is still running.
 *
 * PREDICTIVE collects the system's back events into [backState]'s progress, so the surface bound
 * to it (its graphicsLayer) scales and fades under the finger; when the gesture commits, the
 * flow completes and the normal close action runs. A cancelled gesture never reaches the close
 * action, and the progress springs back to 0. ORIGINAL is the plain back handler this replaces.
 */
@Composable
fun PredictiveStyleBackHandler(
    backState: PredictiveSheetBackState,
    enabled: Boolean,
    onBack: () -> Unit,
) {
    if (LocalBackAnimationStyle.current == BackAnimationStyle.PREDICTIVE) {
        val onBackUpdated = rememberUpdatedState(onBack)
        PredictiveBackHandler(enabled = enabled) { events ->
            backState.gestureActive = true
            try {
                events.collect { event -> backState.progress.snapTo(event.progress) }
                onBackUpdated.value()
            } finally {
                backState.gestureActive = false
            }
        }
    } else {
        BackHandler(enabled = enabled, onBack = onBack)
    }
}
