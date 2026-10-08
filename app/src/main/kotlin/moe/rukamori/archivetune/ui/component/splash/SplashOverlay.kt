/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Opening animation overlay composable — ported from YumaPlayer (github.com/MuwMx/YumaPlayer),
 * ui/component/splash/SplashOverlay.kt (GPL-3.0).
 */



package moe.rukamori.archivetune.ui.component.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import moe.rukamori.archivetune.utils.isLowEndDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.constants.SplashOverlayEnabledKey
import moe.rukamori.archivetune.utils.rememberPreference

@Composable
fun SplashOverlay(
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
    contentColor: Color? = null,
    primaryColor: Color? = null,
    skip: Boolean = false,
    onBurstStart: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val splashOverlayEnabled by rememberPreference(SplashOverlayEnabledKey, defaultValue = true)
    if (splashOverlayEnabled) {
        val colorScheme = MaterialTheme.colorScheme
        val resolvedContentColor = remember(isDark, contentColor, colorScheme) {
            contentColor ?: if (isDark) Color.White else colorScheme.onSurface
        }
        val resolvedPrimaryColor = remember(isDark, primaryColor, colorScheme) {
            primaryColor ?: if (isDark) Color.White else colorScheme.primary
        }
        val vv = SplashSlots.vectorVersion
        val animationsDisabled = LocalAnimationsDisabled.current
        var showSplash by remember { mutableStateOf(!animationsDisabled) }
        val currentOnBurstStart by rememberUpdatedState(onBurstStart)
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        var isInitialized by remember { mutableStateOf(false) }
        var burstTriggered by remember { mutableStateOf(false) }

        // One way out for every path — the engine finishing, the host asking to skip, the ceiling
        // below and a tap — so dismissal cannot half-happen: the layer starts leaving and the host
        // is told in the same step.
        val dismissSplash = {
            if (showSplash) {
                showSplash = false
                currentOnDismiss()
            }
        }

        val density = LocalDensity.current.density
        val context = LocalContext.current
        // Low-end budget, set synchronously: onSizeChanged can fire init() before
        // any LaunchedEffect runs, so the budget must exist at remember time.
        // 64 -> 24 particles where per-frame Canvas work would fight first
        // composition + DB/IO init. Same animation and timing, fewer sprites.
        val engine = remember {
            SplashEngine().apply {
                if (context.isLowEndDevice()) memberBudget = SplashEngine.MEMBER_COUNT
            }
        }
        val renderer = remember { SplashRenderer() }
        var frameTick by remember { mutableLongStateOf(0L) }

        LaunchedEffect(vv) {
            if (vv > 0) engine.rebuildSlots()
        }

        // The safety net: whatever the engine is doing, the overlay gives the screen back.
        LaunchedEffect(Unit) {
            delay(SplashConfig.Reveal.MAX_VISIBLE_MS.toLong())
            dismissSplash()
        }

        LaunchedEffect(skip) {
            if (skip) dismissSplash()
        }

        LaunchedEffect(showSplash) {
            if (!showSplash) return@LaunchedEffect
            var lastTime = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (!isInitialized) return@withFrameNanos
                    if (lastTime == 0L) {
                        lastTime = now
                        return@withFrameNanos
                    }
                    val dt = ((now - lastTime) / 1_000_000_000f).coerceIn(0f, 0.033f)
                    lastTime = now
                    engine.update(dt, now / 1_000_000)
                    frameTick = now

                    val burstLimit =
                        if (engine.isShort) SplashConfig.Timings.BURST_SHORT_MS else SplashConfig.Timings.BURST_FULL_MS
                    if (engine.currentPhase == SplashPhase.Burst &&
                        !burstTriggered &&
                        engine.phaseElapsedMs >= burstLimit * SplashConfig.Reveal.START_FRACTION
                    ) {
                        burstTriggered = true
                        currentOnBurstStart()
                    }

                    if (engine.currentPhase == SplashPhase.Idle && engine.shockwave == null) {
                        dismissSplash()
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSplash,
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(durationMillis = SplashConfig.Reveal.FADE_MS)),
            modifier = modifier
                .fillMaxSize()
                .clickable(
                    enabled = showSplash,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    // A tap is the reader asking to move on — the same exit as the ceiling, so a
                    // long ring tail never has to be waited out. It also swallows the tap, which
                    // is what keeps the invisible content behind it untappable.
                    onClick = { dismissSplash() },
                ),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (w > 0f && h > 0f && (engine.width != w || engine.height != h)) {
                            engine.density = density
                            engine.init(w, h)
                            engine.startGather(SplashSlots.SHAPE_LOGO)
                            isInitialized = true
                        }
                    },
            ) {
                frameTick
                with(renderer) {
                    render(
                        engine = engine,
                        isDark = isDark,
                        contentColor = resolvedContentColor,
                        primaryColor = resolvedPrimaryColor,
                    )
                }
            }
        }
    }
}
