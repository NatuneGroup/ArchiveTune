/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.utils

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache

/**
 * The non-blur answer to "what is behind the artwork": an artwork-colour gradient. Every
 * blurred-artwork background (player backgrounds, lyrics backdrops, ambient modes) falls back to
 * this while "Disable blur effects" is on, so the plain look still carries the song's colours.
 *
 * The palette is extracted once per track with the same [PlayerColorExtractor] rules the gradient
 * player background uses and shared through [PlayerPaletteCache]; a track whose palette cannot be
 * extracted yields an empty list and the caller draws its own static fallback.
 */
@Composable
fun rememberArtworkGradientColors(
    mediaId: String?,
    artworkUrl: String?,
    fallbackColor: Int,
    darkTheme: Boolean,
    backgroundMode: String,
): List<Color> {
    val context = LocalContext.current
    var colors by remember(mediaId, artworkUrl) { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(mediaId, artworkUrl, fallbackColor, darkTheme, backgroundMode) {
        if (mediaId.isNullOrBlank() || artworkUrl.isNullOrBlank()) {
            colors = emptyList()
            return@LaunchedEffect
        }
        val cacheKey =
            PlayerPaletteCacheKey(
                mediaId = mediaId,
                provider = guessArtworkProvider(artworkUrl),
                artworkIdentity = artworkUrl,
                backgroundMode = backgroundMode,
                darkTheme = darkTheme,
            )
        PlayerPaletteCache.get(cacheKey)?.let { cached ->
            colors = cached
            return@LaunchedEffect
        }
        val request =
            ImageRequest
                .Builder(context)
                .data(artworkUrl)
                .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                .allowHardware(false)
                .build()
        val extracted =
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        context.imageLoader.execute(request)
                    }
                if (result !is SuccessResult) {
                    null
                } else {
                    val bitmap = result.image?.toBitmap() ?: return@LaunchedEffect
                    val palette =
                        withContext(Dispatchers.Default) {
                            Palette
                                .from(bitmap)
                                .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                                .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                                .generate()
                        }
                    PlayerColorExtractor.extractGradientColors(palette, fallbackColor)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        if (!extracted.isNullOrEmpty()) {
            PlayerPaletteCache.put(cacheKey, extracted)
            colors = extracted
        }
    }
    return colors
}

/**
 * The gradient itself, drawn the way the player background's GRADIENT branch draws it: three
 * vertical stops over a gentle dark overlay. Also the single fallback surface for every
 * blurred-artwork background when the blur policy is off.
 */
@Composable
fun ArtworkGradientBackdrop(
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = gradientColors,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(1000)) togetherWith fadeOut(tween(1000))
        },
        label = "artwork-gradient-backdrop",
    ) { colors ->
        if (colors.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                val gradientColorStops =
                    if (colors.size >= 3) {
                        arrayOf(
                            0.0f to colors[0].copy(alpha = 0.92f),
                            0.5f to colors[1].copy(alpha = 0.75f),
                            1.0f to colors[2].copy(alpha = 0.65f),
                        )
                    } else {
                        arrayOf(
                            0.0f to colors[0].copy(alpha = 0.9f),
                            0.6f to colors[0].copy(alpha = 0.55f),
                            1.0f to Color.Black.copy(alpha = 0.7f),
                        )
                    }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(colorStops = gradientColorStops)),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.18f)),
                )
            }
        }
    }
}

/**
 * [rememberArtworkGradientColors] + [ArtworkGradientBackdrop] in one call, for backdrops that have
 * no palette of their own at hand. Shares the GRADIENT player background's palette cache entry.
 */
@Composable
fun BlurFreeArtworkBackdrop(
    mediaId: String?,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    val colors =
        rememberArtworkGradientColors(
            mediaId = mediaId,
            artworkUrl = artworkUrl,
            fallbackColor = MaterialTheme.colorScheme.surface.toArgb(),
            darkTheme = isSystemInDarkTheme(),
            backgroundMode = "GRADIENT",
        )
    ArtworkGradientBackdrop(gradientColors = colors, modifier = modifier)
}
