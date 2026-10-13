/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.player

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.CompactControlsStockColorKey
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyle
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.CompactMiniPlayerKey
import moe.rukamori.archivetune.constants.NavigationBarStyle
import moe.rukamori.archivetune.constants.SwipeSensitivityKey
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.ui.component.LocalNavigationBarBackdrop
import moe.rukamori.archivetune.ui.component.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.ui.component.PillBackdrop
import moe.rukamori.archivetune.ui.component.PillFrostBlurRadiusPx
import moe.rukamori.archivetune.ui.component.PillFrostOverlayAlpha
import moe.rukamori.archivetune.ui.component.PillRole
import moe.rukamori.archivetune.ui.component.liquidGlass
import moe.rukamori.archivetune.ui.component.rememberAppleMusicExperience
import moe.rukamori.archivetune.ui.component.rememberPillStyle
import moe.rukamori.archivetune.ui.component.rememberPreSFrostedBitmap
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.isLowEndDevice
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
    navigationProximityProvider: () -> Float = { 0f },
    compactFractionState: State<Float>? = null,
    compactHorizontalPadding: Dp = 0.dp,
    compactReserveEndControl: Boolean = false,
) {
    NewMiniPlayer(
        position = position,
        duration = duration,
        modifier = modifier,
        pureBlack = pureBlack,
        navigationProximityProvider = navigationProximityProvider,
        compactFractionState = compactFractionState,
        compactHorizontalPadding = compactHorizontalPadding,
        compactReserveEndControl = compactReserveEndControl,
    )
}

@Composable
private fun NewMiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
    navigationProximityProvider: () -> Float,
    compactFractionState: State<Float>?,
    compactHorizontalPadding: Dp,
    compactReserveEndControl: Boolean,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnail by rememberPreference(moe.rukamori.archivetune.constants.SwipeThumbnailKey, true)
    val miniPlayerBackgroundStyle by rememberEnumPreference(
        key = MiniPlayerBackgroundStyleKey,
        defaultValue = MiniPlayerBackgroundStyle.THEME,
    )
    val compactMiniPlayerPreference by rememberPreference(CompactMiniPlayerKey, defaultValue = false)
    val appleMusicExperience = rememberAppleMusicExperience()
    val compactMiniPlayer = compactMiniPlayerPreference && !appleMusicExperience
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    // Keep the previous valid palette while the next artwork loads; replace only on success.
    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }
    var hasValidPalette by remember { mutableStateOf(false) }
    val fallbackColor = MaterialTheme.colorScheme.surface.toArgb()
    // Only the artwork-derived styles need palette extraction; THEME, FROSTED and
    // LIQUID_GLASS don't.
    val shouldUseArtworkBackground =
        miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.GRADIENT ||
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.GLOW
    val darkTheme = isSystemInDarkTheme()

    LaunchedEffect(
        mediaMetadata?.id,
        mediaMetadata?.thumbnailUrl,
        shouldUseArtworkBackground,
        fallbackColor,
        darkTheme,
    ) {
        if (!shouldUseArtworkBackground) {
            gradientColors = emptyList()
            hasValidPalette = false
            return@LaunchedEffect
        }

        val currentMetadata = mediaMetadata
        val thumbnailUrl = currentMetadata?.thumbnailUrl
        if (currentMetadata == null || thumbnailUrl.isNullOrBlank()) {
            if (!hasValidPalette) gradientColors = emptyList()
            return@LaunchedEffect
        }

        val cacheKey =
            PlayerPaletteCacheKey(
                mediaId = currentMetadata.id,
                provider = guessArtworkProvider(thumbnailUrl),
                artworkIdentity = thumbnailUrl,
                backgroundMode = miniPlayerBackgroundStyle.name,
                darkTheme = darkTheme,
            )
        PlayerPaletteCache.get(cacheKey)?.let { cachedColors ->
            gradientColors = cachedColors
            hasValidPalette = true
            return@LaunchedEffect
        }

        val request =
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                .allowHardware(false)
                .build()

        val extractedColors =
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        context.imageLoader.execute(request)
                    }
                if (result !is SuccessResult) {
                    null
                } else {
                    val bitmap = result.image?.toBitmap()
                    if (bitmap == null) {
                        null
                    } else {
                        val palette =
                            withContext(Dispatchers.Default) {
                                Palette
                                    .from(bitmap)
                                    .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                                    .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                                    .generate()
                            }
                        PlayerColorExtractor.extractGradientColors(
                            palette = palette,
                            fallbackColor = fallbackColor,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }

        // On failure/cancellation keep the previous valid palette; never force a grey fallback.
        if (extractedColors != null) {
            val stillCurrent =
                mediaMetadata?.id == currentMetadata.id &&
                    mediaMetadata?.thumbnailUrl == thumbnailUrl
            if (stillCurrent) {
                PlayerPaletteCache.put(cacheKey, extractedColors)
                gradientColors = extractedColors
                hasValidPalette = true
            }
        } else if (!hasValidPalette) {
            gradientColors = emptyList()
        }
    }

    val pill = rememberPillStyle()
    // Next to an Apple Music tab bar the mini player drops its accent and artwork-derived colours so
    // it does not compete with the bar's accent. The artwork itself stays in colour.
    val grayscale = appleMusicExperience || pill.navigationStyle == NavigationBarStyle.APPLE_MUSIC
    val backgroundPalette =
        remember(gradientColors, grayscale) {
            MiniPlayerBackgroundPalette.from(
                if (grayscale) gradientColors.map { it.toGrayscale() } else gradientColors,
            )
        }
    val liquidGlassMaster = rememberLiquidGlassEnabled()
    val stockColours by rememberPreference(CompactControlsStockColorKey, defaultValue = false)
    val effectiveBackgroundStyle =
        when {
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS && (!liquidGlassMaster || stockColours) ->
                MiniPlayerBackgroundStyle.THEME
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> MiniPlayerBackgroundStyle.THEME
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS ->
                MiniPlayerBackgroundStyle.LIQUID_GLASS
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.FROSTED -> MiniPlayerBackgroundStyle.FROSTED
            shouldUseArtworkBackground && backgroundPalette != null -> miniPlayerBackgroundStyle
            else -> MiniPlayerBackgroundStyle.THEME
        }

    val contentColors =
        rememberMiniPlayerContentColors(
            useArtworkBackground =
                effectiveBackgroundStyle == MiniPlayerBackgroundStyle.GRADIENT ||
                    effectiveBackgroundStyle == MiniPlayerBackgroundStyle.GLOW ||
                    effectiveBackgroundStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS,
            grayscale = grayscale,
        )
    // The provider fires every sheet-drag frame; route it through derivedStateOf so the
    // shape below only recomputes when the snapped proximity value changes, and remember
    // the shape itself so each recomposition reuses one object instead of allocating.
    val navigationProximity by remember(navigationProximityProvider) {
        derivedStateOf { navigationProximityProvider().coerceIn(0f, 1f) }
    }
    val unconstrainedShape = remember(pill, navigationProximity) { pill.shape(PillRole.MINI_PLAYER, navigationProximity) }
    val restingShape = remember(pill) { pill.shape(PillRole.MINI_PLAYER) }
    // Width snaps instead of morphing: a docked mini player is full-width until contact, then
    // clamps to the nav bar width; floating and Apple Music pills never dock, so they always sit at
    // the bar's width. Kept outside the shape's derivedStateOf — a boolean threshold flips at most
    // twice per gesture, not every frame.
    val constrainToNavigationWidth by remember(navigationProximityProvider) {
        derivedStateOf { navigationProximityProvider() > 0f }
    }

    // The compact row spans the whole bottom edge between the round buttons, so the bar-width cap
    // and the style's corner radii give way to a full capsule as soon as the layout starts to compact.
    val compactFraction: (() -> Float)? =
        remember(compactFractionState) { compactFractionState?.let { state -> { state.value } } }
    val compactActive by remember(compactFractionState) {
        derivedStateOf { (compactFractionState?.value ?: 0f) > 0.01f }
    }
    val compactCapsule by remember(compactFractionState) {
        derivedStateOf { (compactFractionState?.value ?: 0f) > 0.5f }
    }
    val capsuleShape = remember { RoundedCornerShape(percent = 50) }
    val miniPlayerShape = if (compactCapsule) capsuleShape else unconstrainedShape
    val backgroundShape = if (compactCapsule) capsuleShape else restingShape
    // In the one-row bar without a glass tab bar the capsule matches the solid Material 3 circles
    // beside it: opaque and without the Apple hairline.
    val solidCompactCapsule = compactCapsule && pill.backdrop != PillBackdrop.LIQUID_GLASS
    val themeColor =
        remember(pill, grayscale, solidCompactCapsule) {
            val raised =
                if (solidCompactCapsule) pill.raisedContainerColor.copy(alpha = 1f) else pill.raisedContainerColor
            if (grayscale) raised.toGrayscale() else raised
        }
    val capsuleBorder = pill.border.takeUnless { solidCompactCapsule }

    SwipeableMiniPlayerBox(
        modifier = modifier,
        contentMaxWidth =
            if (!compactActive && (constrainToNavigationWidth || !pill.isDocked || pill.compact)) pill.maxWidth else null,
        horizontalInset = pill.horizontalInset,
        swipeSensitivity = swipeSensitivity,
        swipeThumbnail = swipeThumbnail,
        playerConnection = playerConnection,
        layoutDirection = layoutDirection,
        coroutineScope = coroutineScope,
        pureBlack = pureBlack,
        useLegacyBackground = false,
        compactFraction = compactFraction,
        compactHorizontalPadding = compactHorizontalPadding,
        compactReserveEndControl = compactReserveEndControl,
    ) { offsetX ->
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .clip(miniPlayerShape)
                    .then(capsuleBorder?.let { Modifier.border(it, miniPlayerShape) } ?: Modifier),
        ) {
            MiniPlayerBackground(
                style = effectiveBackgroundStyle,
                palette = backgroundPalette,
                themeColor = themeColor,
                shape = backgroundShape,
                modifier = Modifier.fillMaxSize(),
            )
            NewMiniPlayerContent(
                position = position,
                duration = duration,
                playerConnection = playerConnection,
                colors = contentColors,
                compact = compactMiniPlayer,
                compactFraction = compactFraction,
                compactShowTransportControls = !compactReserveEndControl,
            )
        }
    }
}

/** Collapses hue and saturation, keeping the colour's perceived brightness and alpha. */
private fun Color.toGrayscale(): Color {
    val luma = 0.299f * red + 0.587f * green + 0.114f * blue
    return Color(luma, luma, luma, alpha)
}

@Composable
private fun rememberMiniPlayerContentColors(
    useArtworkBackground: Boolean,
    grayscale: Boolean,
): MiniPlayerContentColors {
    val colorScheme = MaterialTheme.colorScheme
    return remember(
        useArtworkBackground,
        grayscale,
        colorScheme.primary,
        colorScheme.onPrimary,
        colorScheme.outline,
        colorScheme.onSurface,
        colorScheme.onSurfaceVariant,
        colorScheme.surface,
        colorScheme.surfaceContainerHighest,
        colorScheme.surfaceVariant,
        colorScheme.primaryContainer,
        colorScheme.onPrimaryContainer,
    ) {
        if (useArtworkBackground) {
            MiniPlayerContentColors(
                title = Color.White,
                secondary = Color.White.copy(alpha = 0.72f),
                progress = Color.White,
                progressTrack = Color.White.copy(alpha = 0.24f),
                artworkContainer = Color.White.copy(alpha = 0.14f),
                artworkBorder = Color.White.copy(alpha = 0.22f),
                primaryButtonContainer = Color.White.copy(alpha = 0.92f),
                primaryButtonIcon = Color.Black,
                secondaryButtonContainer = Color.Black.copy(alpha = 0.22f),
                buttonIcon = Color.White,
                disabledButtonIcon = Color.White.copy(alpha = 0.38f),
            )
        } else if (grayscale) {
            val strong = colorScheme.onSurface.toGrayscale()
            MiniPlayerContentColors(
                title = strong,
                secondary = colorScheme.onSurfaceVariant.toGrayscale(),
                progress = strong,
                progressTrack = colorScheme.outline.toGrayscale().copy(alpha = 0.18f),
                artworkContainer = colorScheme.surfaceVariant.toGrayscale(),
                artworkBorder = colorScheme.outline.toGrayscale().copy(alpha = 0.2f),
                primaryButtonContainer = strong,
                primaryButtonIcon = colorScheme.surface.toGrayscale(),
                secondaryButtonContainer = colorScheme.surfaceContainerHighest.toGrayscale(),
                buttonIcon = strong,
                disabledButtonIcon = strong.copy(alpha = 0.38f),
            )
        } else {
            MiniPlayerContentColors(
                title = colorScheme.onSurface,
                secondary = colorScheme.onSurfaceVariant,
                progress = colorScheme.primary,
                progressTrack = colorScheme.outline.copy(alpha = 0.18f),
                artworkContainer = colorScheme.surfaceVariant,
                artworkBorder = colorScheme.outline.copy(alpha = 0.2f),
                primaryButtonContainer = colorScheme.primary,
                primaryButtonIcon = colorScheme.onPrimary,
                secondaryButtonContainer = colorScheme.surfaceContainerHighest,
                buttonIcon = colorScheme.onSurface,
                disabledButtonIcon = colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

@Composable
private fun MiniPlayerBackground(
    style: MiniPlayerBackgroundStyle,
    palette: MiniPlayerBackgroundPalette?,
    themeColor: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    // Frosted blur on the mini player relies on RenderEffect (API 31+). On pre-S the CPU-blurred
    // bitmap fallback produced visible glitches on older devices, so FROSTED is forcibly
    // downgraded to THEME. The Settings screen surfaces a "not supported on Android versions
    // below 12" warning under the mini player background selector when running on pre-S.
    val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val effectiveStyle = if (isPreS && style == MiniPlayerBackgroundStyle.FROSTED) {
        MiniPlayerBackgroundStyle.THEME
    } else if (isPreS && style == MiniPlayerBackgroundStyle.LIQUID_GLASS) {
        MiniPlayerBackgroundStyle.THEME
    } else {
        style
    }
    when (effectiveStyle) {
        MiniPlayerBackgroundStyle.THEME -> {
            Box(
                modifier = modifier.background(themeColor),
            )
        }

        MiniPlayerBackgroundStyle.LIQUID_GLASS -> {
            val liquidGlassBackdrop = LocalLiquidGlassBackdrop.current
            val baseColor = themeColor
            if (liquidGlassBackdrop != null) {
                Box(
                    modifier =
                        modifier.liquidGlass(
                            backdrop = liquidGlassBackdrop,
                            shape = shape,
                            interactive = false,
                            baseColor = baseColor,
                        ),
                )
            } else {
                Box(
                    modifier = modifier.background(baseColor),
                )
            }
        }

        MiniPlayerBackgroundStyle.FROSTED -> {
            val backdrop = LocalNavigationBarBackdrop.current
            val baseColor = themeColor
            if (backdrop == null) {
                Box(modifier = modifier.background(baseColor))
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // Pre-S: CPU-blurred bitmap fallback. The bitmap is the small slice under the
                // mini player (not the full screen), captured and blurred every ~80 ms — fast
                // enough for smooth frosted tracking without tanking pre-S hardware. The blurred
                // slice is already aligned to the mini player's top-left, so we draw at (0, 0).
                var positionInRoot by remember { mutableStateOf(Offset.Zero) }
                var miniPlayerSize by remember { mutableStateOf(IntSize.Zero) }
                val blurredBitmap = rememberPreSFrostedBitmap(
                    backdrop = backdrop,
                    barPositionInRoot = positionInRoot,
                    barSize = miniPlayerSize,
                    blurRadiusPx = PillFrostBlurRadiusPx,
                    updateIntervalMs = if (LocalContext.current.isLowEndDevice()) 160L else 80L,
                )
                Box(
                    modifier =
                        modifier
                            .onGloballyPositioned {
                                positionInRoot = it.positionInRoot()
                                miniPlayerSize = it.size
                            }
                            .background(baseColor),
                ) {
                    if (blurredBitmap != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = PillFrostOverlayAlpha
                                        clip = true
                                    }.drawBehind {
                                        drawImage(blurredBitmap)
                                    },
                        )
                    }
                }
            } else {
                var positionInRoot by remember { mutableStateOf(Offset.Zero) }
                Box(
                    modifier =
                        modifier
                            .onGloballyPositioned { positionInRoot = it.positionInRoot() }
                            .background(baseColor),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    renderEffect =
                                        BlurEffect(
                                            radiusX = PillFrostBlurRadiusPx,
                                            radiusY = PillFrostBlurRadiusPx,
                                            edgeTreatment = TileMode.Clamp,
                                        )
                                    alpha = PillFrostOverlayAlpha
                                    clip = true
                                }.drawBehind {
                                    val offset = backdrop.contentOffsetInRoot - positionInRoot
                                    translate(offset.x, offset.y) {
                                        drawLayer(backdrop.layer)
                                    }
                                },
                    )
                }
            }
        }

        MiniPlayerBackgroundStyle.GRADIENT -> {
            val colors = requireNotNull(palette)
            Box(modifier = modifier) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops =
                                        arrayOf(
                                            0f to colors.first.copy(alpha = 0.95f),
                                            0.52f to colors.second.copy(alpha = 0.82f),
                                            1f to colors.third.copy(alpha = 0.72f),
                                        ),
                                ),
                            ),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f)),
                )
            }
        }

        MiniPlayerBackgroundStyle.GLOW -> {
            val colors = requireNotNull(palette)
            Box(
                modifier =
                    modifier.drawWithCache {
                        val width = size.width
                        val height = size.height
                        val startGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.first.copy(alpha = 0.82f), colors.first.copy(alpha = 0.38f), Color.Transparent),
                                center = Offset(width * 0.12f, height * 0.42f),
                                radius = width * 0.72f,
                            )
                        val endGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.second.copy(alpha = 0.78f), colors.second.copy(alpha = 0.34f), Color.Transparent),
                                center = Offset(width * 0.88f, height * 0.58f),
                                radius = width * 0.72f,
                            )
                        val topGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.third.copy(alpha = 0.58f), Color.Transparent),
                                center = Offset(width * 0.52f, height * 0.05f),
                                radius = width * 0.54f,
                            )
                        val bottomGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.fourth.copy(alpha = 0.46f), Color.Transparent),
                                center = Offset(width * 0.46f, height * 1.05f),
                                radius = width * 0.54f,
                            )

                        onDrawBehind {
                            drawRect(Color.Black)
                            drawRect(startGlow)
                            drawRect(endGlow)
                            drawRect(topGlow)
                            drawRect(bottomGlow)
                            drawRect(Color.Black.copy(alpha = 0.24f))
                        }
                    },
            )
        }
    }
}

@Immutable
private data class MiniPlayerBackgroundPalette(
    val first: Color,
    val second: Color,
    val third: Color,
    val fourth: Color,
) {
    companion object {
        fun from(colors: List<Color>): MiniPlayerBackgroundPalette? {
            val first = colors.firstOrNull() ?: return null
            val second = colors.getOrElse(1) { first }
            val third = colors.getOrElse(2) { second }
            val fourth = colors.getOrElse(3) { first }
            return MiniPlayerBackgroundPalette(
                first = first,
                second = second,
                third = third,
                fourth = fourth,
            )
        }
    }
}
