/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collect
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.playback.MusicVolumeRange
import moe.rukamori.archivetune.playback.VolumeInteractionPhase
import moe.rukamori.archivetune.playback.VolumeInteractionState

internal val LocalDeviceMusicVolumeController = staticCompositionLocalOf<DeviceMusicVolumeController?> { null }

@Stable
internal class DeviceMusicVolumeController(private val audioManager: AudioManager) {
    private val handler = Handler(Looper.getMainLooper())
    private val interaction = VolumeInteractionState(readRange(), readLevel())
    var volumeFraction by mutableFloatStateOf(interaction.displayFraction)
        private set
    var observedFraction by mutableFloatStateOf(interaction.observedFraction)
        private set
    var phase by mutableStateOf(interaction.phase)
        private set
    private var confirmation: Runnable? = null

    fun startInteraction() {
        if (interaction.phase == VolumeInteractionPhase.Dragging) return
        clearConfirmation()
        interaction.start()
        publish()
    }

    @JvmName("setDeviceMusicVolumeFraction")
    fun setVolumeFraction(fraction: Float) {
        if (!fraction.isFinite()) return
        val dragging = interaction.phase == VolumeInteractionPhase.Dragging
        // Non-gesture callers (keyboard and existing numbered sliders) get the same bounded hold.
        if (interaction.phase == VolumeInteractionPhase.Idle) startInteraction()
        val command = interaction.change(fraction)
        publish()
        if (command != null) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, command, 0) }
        }
        refresh()
        if (!dragging) finishInteraction()
    }

    fun finishInteraction() {
        if (interaction.phase == VolumeInteractionPhase.Idle) {
            refresh(fresh = true)
            return
        }
        clearConfirmation()
        val generation = interaction.finish(SystemClock.uptimeMillis())
        refresh(fresh = true)
        if (interaction.phase == VolumeInteractionPhase.Idle) return
        val deadline = interaction.confirmationDeadline ?: return
        confirmation = Runnable {
            confirmation = null
            if (generation != interaction.generation) return@Runnable
            refresh(fresh = true)
            interaction.expire(generation, SystemClock.uptimeMillis())
            publish()
        }.also { handler.postDelayed(it, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)) }
    }

    // Cancelling does not undo device commands already sent.
    fun cancelInteraction() = finishInteraction()

    fun refresh(fresh: Boolean = false) {
        interaction.observe(readLevel(), readRange(), fresh)
        publish()
    }

    fun reset() {
        clearConfirmation()
        interaction.reset()
        refresh()
    }

    private fun publish() {
        volumeFraction = interaction.displayFraction
        observedFraction = interaction.observedFraction
        phase = interaction.phase
    }

    private fun clearConfirmation() {
        confirmation?.let(handler::removeCallbacks)
        confirmation = null
    }

    private fun readLevel() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    private fun readRange(): MusicVolumeRange {
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else 0
        return MusicVolumeRange(min, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(min + 1))
    }
}

@Composable
internal fun rememberDeviceMusicVolumeController(owner: Any? = Unit): DeviceMusicVolumeController {
    LocalDeviceMusicVolumeController.current?.let { return it }
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context, owner) {
        DeviceMusicVolumeController(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    }
    DisposableEffect(context, controller, lifecycleOwner) {
        var observing = false
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = controller.refresh()
        }
        fun stop() {
            if (observing) context.contentResolver.unregisterContentObserver(observer)
            observing = false
            controller.reset()
        }
        fun start() {
            if (!observing) {
                context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
                observing = true
            }
            controller.reset()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            stop()
        }
    }
    return controller
}

@Composable
internal fun rememberVolumeSliderInteractions(
    controller: DeviceMusicVolumeController?,
): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source, controller) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> controller?.startInteraction()
                is DragInteraction.Stop -> controller?.finishInteraction()
                is DragInteraction.Cancel -> controller?.cancelInteraction()
                else -> Unit
            }
        }
    }
    DisposableEffect(controller) {
        onDispose {
            if (controller?.phase == VolumeInteractionPhase.Dragging) controller.cancelInteraction()
        }
    }
    return source
}

@Composable
internal fun volumeDisplayFraction(controller: DeviceMusicVolumeController): Float {
    val immediate = LocalAnimationsDisabled.current || controller.phase != VolumeInteractionPhase.Idle
    val animated by animateFloatAsState(
        controller.volumeFraction,
        animationSpec = if (immediate) snap() else tween(180),
        label = "deviceVolumeIdle",
    )
    return if (immediate) controller.volumeFraction else animated
}
