/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The block at the top of a bottom-sheet menu that says what the menu is about — the track, the
 * playlist, the album.
 */
@Composable
fun MenuHeaderCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Apple Music's action sheets do not put the track on a raised card. The header sits directly
    // on the sheet with a hairline under it, and the actions read as a plain list below — so under
    // the Apple Music Experience the card is simply not drawn.
    if (rememberAppleMusicExperience()) {
        Column(modifier = modifier.fillMaxWidth()) {
            content()
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
        content = { content() },
    )
}
