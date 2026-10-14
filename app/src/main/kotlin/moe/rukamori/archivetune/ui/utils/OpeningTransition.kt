/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first

/**
 * True once the destination's opening transition has finished. NavHost holds an entering entry at
 * STARTED while its transition runs and moves it to RESUMED when it settles, so heavy previews and
 * images gated on this stay out of the frames the transition is drawing.
 */
@Composable
fun rememberOpeningTransitionFinished(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var finished by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    if (!finished) {
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            finished = true
        }
    }
    return finished
}
