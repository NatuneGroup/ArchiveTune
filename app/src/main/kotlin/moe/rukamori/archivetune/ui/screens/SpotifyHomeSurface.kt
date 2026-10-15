/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import com.kyant.backdrop.backdrops.LayerBackdrop
import moe.rukamori.archivetune.ui.component.LiquidGlassRole
import moe.rukamori.archivetune.ui.component.liquidGlassMaterial
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled

private val LocalSpotifyHomeBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
internal fun SpotifyHomeLayout(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val disableBlur = !moe.rukamori.archivetune.LocalBlurPolicy.current.any
    val glassEnabled = rememberLiquidGlassEnabled() && !disableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val backdrop = if (glassEnabled) rememberBackdrop(MaterialTheme.colorScheme.surface) else null
    Box(
        modifier = modifier,
    ) {
        if (disableBlur) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        } else {
            HomeAtmosphereBackground(
                Modifier.then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            )
        }
        CompositionLocalProvider(LocalSpotifyHomeBackdrop provides backdrop) {
            content()
        }
    }
}

@Composable
internal fun Modifier.spotifyHomeSurface(shape: Shape): Modifier {
    val backdrop = LocalSpotifyHomeBackdrop.current
    val clipped = clip(shape)
    if (backdrop == null) return clipped.background(MaterialTheme.colorScheme.surfaceContainerHigh)
    return clipped.liquidGlassMaterial(
        backdrop = backdrop,
        shape = shape,
        role = LiquidGlassRole.Card,
        interactive = true,
    )
}
