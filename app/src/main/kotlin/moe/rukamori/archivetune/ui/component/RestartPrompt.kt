/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.utils.PreferenceSnapshot
import moe.rukamori.archivetune.utils.RestartReason
import moe.rukamori.archivetune.utils.SnapshotPreferenceState
import moe.rukamori.archivetune.utils.relaunchApp
import moe.rukamori.archivetune.viewmodels.RestartPromptViewModel

val LocalRestartPrompt = staticCompositionLocalOf<RestartPromptViewModel> {
    error("RestartPromptViewModel must be provided by MainActivity")
}

/** Existing single-page snapshot reads; only these opt-in setters request a post-commit prompt. */
fun <T> PreferenceSnapshot.restartState(
    controller: RestartPromptViewModel,
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> = SnapshotPreferenceState(
    read = { this[key, defaultValue] },
    write = { value -> controller.edit(this) { this[key] = value } },
)

inline fun <reified E : Enum<E>> PreferenceSnapshot.restartEnumState(
    controller: RestartPromptViewModel,
    key: Preferences.Key<String>,
    defaultValue: E,
): MutableState<E> = SnapshotPreferenceState(
    read = { current[key].toEnum(defaultValue) },
    write = { value -> controller.edit(this) { this[key] = value.name } },
)

/** The only restart dialog, above navigation. Back/outside dismissal is exactly Later. */
@Composable
fun RestartPromptHost(controller: RestartPromptViewModel) {
    val state by controller.state.collectAsStateWithLifecycle()
    if (!state.visible) return
    val context = LocalContext.current
    val labels = state.reasons.map {
        stringResource(
            when (it) {
                RestartReason.UI_SCALE -> R.string.ui_scale
                RestartReason.FONT -> R.string.font_preference
                RestartReason.TABLET_MODE -> R.string.tablet_mode
                RestartReason.GLASS -> R.string.liquid_glass
                RestartReason.NAVIGATION_GLASS -> R.string.navigation_bar_settings_title
                RestartReason.USB_OUTPUT -> R.string.restart_reason_usb
                RestartReason.STORAGE -> R.string.storage
                RestartReason.REGION -> R.string.youtube_music_region
            },
        )
    }
    AlertDialog(
        onDismissRequest = controller::later,
        title = { Text(stringResource(R.string.restart_to_apply)) },
        text = {
            Text(
                stringResource(R.string.restart_prompt_description) +
                    "\n\n" + labels.joinToString("\n"),
            )
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy,
                onClick = { controller.restartIfReady { context.relaunchApp() } },
            ) {
                Text(stringResource(R.string.restart_now))
            }
        },
        dismissButton = {
            TextButton(onClick = controller::later) {
                Text(stringResource(R.string.later))
            }
        },
    )
}
