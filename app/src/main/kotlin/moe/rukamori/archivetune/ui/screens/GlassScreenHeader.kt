/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.ui.component.IconButton as AppIconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.utils.rememberPreference

/**
 * Shared glass header state for the detail screens (album / artist / playlist).
 *
 * Centralizes the three gates every hand-rolled header repeated: the
 * [LiquidGlassEnabledKey] master toggle, the Android 12+ RuntimeShader
 * requirement, and the full-screen-lyrics suspension (the lyrics overlay is
 * opaque, so recording the list and sampling it every frame would just starve
 * the karaoke sweep of GPU). [backdrop] is null unless all three pass, so
 * call sites gate on [liquidGlassActive]/[backdrop] alone.
 */
@Stable
class GlassScreenHeader(
    val liquidGlassActive: Boolean,
    val backdrop: PlatformBackdrop?,
    val haze: HazeState,
)

@Composable
fun rememberGlassScreenHeader(): GlassScreenHeader {
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    val surfaceColor = MaterialTheme.colorScheme.surface

    val backdrop = rememberBackdrop(surfaceColor)
    val haze = rememberScreenHeaderHaze()
    val active =
        liquidGlassEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !lyricsFullScreen
    return GlassScreenHeader(
        liquidGlassActive = active,
        backdrop = if (active) backdrop else null,
        haze = haze,
    )
}

/**
 * Tags the scrolling content as the header's backdrop + haze source. The
 * layerBackdrop half is a no-op unless the kit created a live backdrop, so
 * this is safe to apply unconditionally — rendering with liquid glass OFF is
 * unchanged.
 */
fun Modifier.glassHeaderSource(header: GlassScreenHeader): Modifier =
    this
        .then(if (header.backdrop != null) Modifier.layerBackdrop(header.backdrop) else Modifier)
        .hazeSource(header.haze)

@Composable
fun BoxScope.GlassScreenHeaderOverlay(
    header: GlassScreenHeader,
    title: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    ScreenHeaderHaze(
        hazeState = header.haze,
        systemBarsTopPadding = systemBarsTopPadding,
    )

    val backdrop = header.backdrop
    if (!header.liquidGlassActive || backdrop == null) {
        return
    }

    LiquidGlassActionPill(
        backdrop = backdrop,
        interactive = true,
        modifier =
            modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp),
    ) {
        AppIconButton(
            onClick = onBack,
            onLongClick = onBackLongClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = title,
                tint = Color.White,
            )
        }

        // No second title style: this is the frosted-pill title look from
        // LargeFrostedTopAppBar (bold, single-line), tinted for glass.
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
    }

    if (onSearch != null || trailing != null) {
        LiquidGlassActionPill(
            backdrop = backdrop,
            modifier =
                modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
        ) {
            if (onSearch != null) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIconButton(
                        onClick = onSearch,
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = stringResource(R.string.search),
                            tint = Color.White,
                        )
                    }
                }
            } else {
                trailing?.invoke(this)
            }
        }
    }
}
