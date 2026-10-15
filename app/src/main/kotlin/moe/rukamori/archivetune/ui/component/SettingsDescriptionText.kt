/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.WeakHashMap

/** Two automatic passes per visible run; the shared renderer measures actual text overflow. */
@Composable
fun SettingsDescriptionText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val entry = remember(lifecycle) { SettingsTextLifecycles.entry(lifecycle) }
    DisposableEffect(lifecycle) {
        SettingsTextLifecycles.acquire(lifecycle, entry)
        onDispose { SettingsTextLifecycles.release(lifecycle, entry) }
    }
    val active by entry.active
    var visible by remember { mutableStateOf(false) }
    MarqueeText(
        text = text,
        style = style,
        color = color,
        fontWeight = fontWeight,
        iterations = 2,
        animationEnabled = active && visible,
        modifier = modifier.onGloballyPositioned { coordinates ->
            visible = coordinates.isAttached &&
                !coordinates.boundsInWindow()
                    .intersect(coordinates.findRootCoordinates().boundsInWindow()).isEmpty
        },
    )
}

/** One observer per navigation entry, shared by all its text; no preference or frame collectors. */
private object SettingsTextLifecycles {
    class Entry(lifecycle: Lifecycle) {
        val state = mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED)
        val active: State<Boolean> get() = state
        var users = 0
        val observer = LifecycleEventObserver { source, _ ->
            state.value = source.lifecycle.currentState == Lifecycle.State.RESUMED
        }
    }

    private val entries = WeakHashMap<Lifecycle, Entry>()

    fun entry(lifecycle: Lifecycle): Entry = entries.getOrPut(lifecycle) { Entry(lifecycle) }

    fun acquire(lifecycle: Lifecycle, entry: Entry) {
        if (entry.users++ == 0) {
            entry.state.value = lifecycle.currentState == Lifecycle.State.RESUMED
            lifecycle.addObserver(entry.observer)
        }
    }

    fun release(lifecycle: Lifecycle, entry: Entry) {
        if (--entry.users == 0) {
            lifecycle.removeObserver(entry.observer)
            // Keep the weak entry: composition can remember a new row before disposing the
            // last old row's effect. Removing it here could create two observers for that page.
        }
    }
}
