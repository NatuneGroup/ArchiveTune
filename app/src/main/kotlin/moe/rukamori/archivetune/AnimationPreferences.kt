/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

val LocalAnimationsDisabled = compositionLocalOf { false }

/**
 * Whether the app may blur at all, decided once from "Disable blur effects" and provided at the
 * root. Every blur or glass effect reads the policy through [LocalBlurPolicy] instead of the raw
 * preference, so a site that forgets to check still cannot render one. [renderEffect] covers the
 * platform paths (Modifier.blur, BlurEffect, Haze, the kyant backdrop) and [cpuFallback] the
 * pre-Android-12 stack-blur bitmaps; the split exists because those are the two families call
 * sites branch on.
 */
@Immutable
data class BlurPolicy(
    val userEnabled: Boolean,
) {
    /** Platform RenderEffect blur: allowed only when the user has not disabled it, and only on S+. */
    val renderEffect: Boolean get() = blurRenderEffectAllowed(userEnabled, Build.VERSION.SDK_INT)

    /** CPU stack-blur fallbacks (rememberPreBlurredBitmap, ImageBlurUtils): the pre-S complement. */
    val cpuFallback: Boolean get() = blurCpuFallbackAllowed(userEnabled, Build.VERSION.SDK_INT)

    /** May anything in the app blur, by any technique. */
    val any: Boolean get() = userEnabled
}

internal fun blurRenderEffectAllowed(
    userEnabled: Boolean,
    sdkInt: Int,
): Boolean = userEnabled && sdkInt >= Build.VERSION_CODES.S

internal fun blurCpuFallbackAllowed(
    userEnabled: Boolean,
    sdkInt: Int,
): Boolean = userEnabled && sdkInt < Build.VERSION_CODES.S

val LocalBlurPolicy = staticCompositionLocalOf { BlurPolicy(userEnabled = true) }

/**
 * The system's animator duration scale, as set in developer options.
 *
 * 1.0 is the default; 0.5 halves every duration; 0 turns animations off system-wide. Compose feeds
 * this into its own clock, so durations already shorten on their own — what it does not do is tell
 * a spring to settle in fewer oscillations, which is why a springy transition reads as stepping
 * rather than as speed once the scale drops. [LocalAnimationScale] carries the value so a spec can
 * answer for itself.
 */
val LocalAnimationScale = compositionLocalOf { 1f }

/** Reads the scale once. Returns 1 when the setting is missing, which is how a fresh device reads. */
fun Context.systemAnimationScale(): Float =
    runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

/**
 * The live system animator duration scale. Compose's own animations follow this value through their
 * coroutine context already; timers that must wait for a Compose animation, and specs that reshape
 * themselves around the scale ([scaleAwareSpring]), read it from here. The developer option does
 * not recreate a running Activity, so a one-shot read goes stale — this observes the setting and
 * keeps the returned [State] current for as long as the caller is composed.
 */
@Composable
fun rememberAnimatorDurationScale(): State<Float> {
    val context = LocalContext.current.applicationContext
    val scale = remember { mutableFloatStateOf(context.systemAnimationScale()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scale.floatValue = context.systemAnimationScale()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return scale
}

/** The pure math behind every scaled wait and spec below: the span as scaled by [scale]. */
fun scaleDuration(
    millis: Long,
    scale: Float,
): Long = (millis * scale).roundToLong()

/**
 * Waits for a span that mirrors an on-screen animation, stretched or shrunk with the animator
 * duration scale; returns at once when animations are off. Must run in a composition-scoped
 * coroutine (LaunchedEffect / rememberCoroutineScope); elsewhere there is no MotionDurationScale
 * and the delay is unscaled.
 */
suspend fun scaledDelay(millis: Long) {
    val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    if (scale <= 0f || millis <= 0L) return
    delay(scaleDuration(millis, scale))
}

/**
 * A spring that stays smooth below 1x — no overshoot to squeeze into fewer frames, the same
 * adaptation as [moe.rukamori.archivetune.ui.screens.settings.SettingsAnimations.pressSpring] — and
 * snaps at 0, where the system has turned animation off. At 1x and above it is the plain spring;
 * Compose shortens it through the animation scale on its own.
 */
fun <T> scaleAwareSpring(
    scale: Float,
    dampingRatio: Float = Spring.DampingRatioNoBouncy,
    stiffness: Float = Spring.StiffnessMedium,
    visibilityThreshold: T? = null,
): FiniteAnimationSpec<T> =
    when {
        scale <= 0f -> snap()
        scale >= 1f -> spring(dampingRatio, stiffness, visibilityThreshold)
        else ->
            spring(
                dampingRatio = 1f + (dampingRatio - 1f) * scale,
                stiffness = stiffness * (0.5f + 0.5f * scale),
                visibilityThreshold = visibilityThreshold,
            )
    }

/**
 * App-wide scrollbar toggle. When `true`, all LazyColumn / LazyGrid /
 * ScrollState scrollbars in the app are suppressed. Provided by MainActivity
 * from the [HideScrollbarKey] preference; consumed by the scrollbar modifier
 * extension in [moe.rukamori.archivetune.ui.utils.ScrollUtils].
 */
val LocalHideScrollbar = compositionLocalOf { false }
