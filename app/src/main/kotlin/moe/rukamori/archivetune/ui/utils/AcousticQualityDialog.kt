/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.acoustics.AcousticAuditTarget
import moe.rukamori.archivetune.acoustics.rememberAcousticAuditController
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.LocalMenuState

@Composable
fun AcousticQualityDialog(target: AcousticAuditTarget, onDismiss: () -> Unit) {
    val controller = rememberAcousticAuditController(target)
    val dismiss = { controller.close(); onDismiss() }
    DefaultDialog(
        onDismiss = dismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        title = { Text(stringResource(R.string.acoustic_quality_title)) },
        horizontalAlignment = Alignment.Start,
        contentScrollable = true,
        buttons = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.acoustic_quality_close)) }
        },
    ) {
        AcousticQualityContent(target, controller)
    }
}

@Composable
internal fun AcousticQualityMenuAction(target: AcousticAuditTarget, onDismiss: () -> Unit) {
    val menuState = LocalMenuState.current
    ListItem(
        headlineContent = { Text(stringResource(R.string.acoustic_quality_title)) },
        leadingContent = { Icon(painterResource(R.drawable.graphic_eq), contentDescription = null) },
        modifier = Modifier.clickable {
            onDismiss()
            menuState.showDialog {
                AcousticQualityDialog(target = target, onDismiss = menuState::dismissDialog)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
