/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.presence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.SwitchPreference

/**
 * The Natune status (listener presence) opt-in, shared by the onboarding welcome page and
 * Settings → Integration. It is off until the user switches it on, and the disclosure stays one
 * tap away. [showMapLink] and [showReset] are passed where the dashboard link and the identifier
 * reset belong (settings); onboarding shows just the switch and the disclosure.
 */
@Composable
fun ListenerPresenceSection(
    modifier: Modifier = Modifier,
    showMapLink: Boolean = false,
    showReset: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val state by
        remember(context) { ListenerPresence.state(context) }
            .collectAsStateWithLifecycle(initialValue = ListenerPresenceState.Off)
    val scope = rememberCoroutineScope()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Column(modifier = modifier.fillMaxWidth()) {
        SwitchPreference(
            title = { Text(stringResource(R.string.presence_title)) },
            description =
                stringResource(
                    if (ListenerPresence.isConfigured) {
                        R.string.presence_summary
                    } else {
                        R.string.presence_unavailable
                    },
                ),
            icon = { Icon(painterResource(R.drawable.solar_users_group_rounded_linear), null) },
            checked = state.active,
            onCheckedChange = { on ->
                scope.launch {
                    if (on) ListenerPresence.enable(context) else ListenerPresence.disable(context)
                }
            },
            isEnabled = ListenerPresence.isConfigured,
        )
        PreferenceEntry(
            title = {
                Text(
                    stringResource(
                        if (showDetails) R.string.presence_hide_details else R.string.presence_show_details,
                    ),
                )
            },
            icon = { Icon(painterResource(R.drawable.info), null) },
            onClick = { showDetails = !showDetails },
        )
        AnimatedVisibility(visible = showDetails) {
            Text(
                text = stringResource(R.string.presence_details),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (showMapLink) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.presence_view_map)) },
                description = stringResource(R.string.presence_view_map_desc),
                icon = { Icon(painterResource(R.drawable.link), null) },
                onClick = { uriHandler.openUri(ListenerPresencePolicy.MAP_URL) },
            )
        }
        if (showReset && state.active) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.presence_reset_id)) },
                icon = { Icon(painterResource(R.drawable.ic_refresh), null) },
                onClick = { scope.launch { ListenerPresence.resetId(context) } },
            )
        }
    }
}
