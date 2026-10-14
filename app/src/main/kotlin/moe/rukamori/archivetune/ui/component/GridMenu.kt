/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

val GridMenuItemHeight = 108.dp

fun LazyGridScope.GridMenuItem(
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int,
    tint: @Composable () -> Color = { LocalContentColor.current },
    @StringRes title: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = GridMenuItem(
    modifier = modifier,
    icon = {
        Icon(
            painter = painterResource(icon),
            tint = tint(),
            contentDescription = null,
        )
    },
    title = title,
    enabled = enabled,
    onClick = onClick,
)

fun LazyGridScope.GridMenuItem(
    modifier: Modifier = Modifier,
    icon: @Composable BoxScope.() -> Unit,
    @StringRes title: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    item {
        Column(
            modifier =
                modifier
                    .clip(ShapeDefaults.Large)
                    .height(GridMenuItemHeight)
                    .clickable(
                        enabled = enabled,
                        onClick = onClick,
                    ).alpha(if (enabled) 1f else 0.5f)
                    .padding(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
                content = icon,
            )
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            with(LocalDensity.current) {
                                MaterialTheme.typography.labelLarge.lineHeight
                                    .toDp() * 2
                            },
                        ),
            )
        }
    }
}
