/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.constants.BackAnimationStyle
import moe.rukamori.archivetune.constants.BottomSheetAnimationSpec
import moe.rukamori.archivetune.constants.BottomSheetSoftAnimationSpec

/**
 * Bottom Sheet
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 *
 * @param keepContentAlive When true, the [content] composable is kept in the
 *   composition tree even when the sheet is collapsed (it's hidden via
 *   alpha=0 instead of being unmounted). This is used by the player sheet
 *   to keep the [InlineVideoPlayer]'s ExoPlayer alive across collapse/expand
 *   cycles — without this, collapsing the player to the mini player would
 *   release the ExoPlayer, and expanding it again would require re-resolving
 *   the stream URL and re-buffering (causing the "video pauses, audio keeps
 *   playing" bug). Default is false to preserve the original behavior for
 *   other sheets (queue, etc.) that don't need this.
 * @param morphMode When true, the sheet fades + scales in place (0.94 → 1.0) over
 *   450ms with FastOutSlowInEasing instead of a plain slide on open. The sheet
 *   still slides vertically with the finger while dragging. This is used by the
 *   queue to add an in-place morph open transition on top of the normal
 *   bottom-sheet behavior. Default is false to preserve the original slide
 *   behavior for other sheets.
 * @param opaqueBackground When true, the sheet's outer background is rendered
 *   fully opaque (alpha = [backgroundColor].alpha) as soon as the sheet is
 *   visible, instead of fading in proportionally to [BottomSheetState.progress].
 *   This is needed by the queue sheet: non-Apple-Music player styles render a
 *   zoomed/gradient/blur artwork backdrop behind the player, and the default
 *   progress-based alpha fade let that artwork bleed through the queue sheet
 *   while dragging. With this flag set, the outer background is opaque from
 *   the very first pixel of drag, fully covering the player artwork, while
 *   the inner content (queue rows) still fades in via its own graphicsLayer
 *   alpha. Default is false to preserve the original behavior for the player
 *   sheet and any other callers.
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onDismiss: (() -> Unit)? = null,
    keepContentAlive: Boolean = false,
    morphMode: Boolean = false,
    backHandlerEnabled: Boolean = true,
    opaqueBackground: Boolean = false,
    onCollapsedContentClick: (() -> Unit)? = null,
    /**
     * When false, a collapsed sheet ignores drags, so it can only be opened by tapping (or by code).
     * An open sheet stays draggable either way, so it can still be swiped closed.
     */
    collapsedDragEnabled: Boolean = true,
    /**
     * Shared predictive back progress for this sheet. The player sheet is handed the state owned
     * by MainActivity so the gesture scrubs the sheet from wherever the handler lives; callers
     * without one get their own state and the sheet's internal back handler drives it.
     */
    predictiveBackState: PredictiveSheetBackState? = null,
    collapsedContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val dragEnabled = collapsedDragEnabled || !state.isCollapsed
    val predictiveBack = predictiveBackState ?: rememberPredictiveSheetBackState()
    val predictiveBackStyle = LocalBackAnimationStyle.current == BackAnimationStyle.PREDICTIVE
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (predictiveBackStyle) {
                        val p = predictiveBack.progress.value
                        if (p > 0f) {
                            val scale = 1f - 0.08f * p
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - 0.25f * p
                        }
                    }
                }
                .offset {
                    val y =
                        (state.expandedBound - state.value)
                            .roundToPx()
                            .coerceAtLeast(0)
                    IntOffset(x = 0, y = y)
                }.then(if (dragEnabled) Modifier.bottomSheetDraggable(state, onDismiss) else Modifier)
                .clip(
                    RoundedCornerShape(
                        topStart = if (!state.isExpanded) 16.dp else 0.dp,
                        topEnd = if (!state.isExpanded) 16.dp else 0.dp,
                    ),
                ).background(
                    if (opaqueBackground) {
                        // Render the outer background fully opaque ONLY when the sheet is actually
                        // sliding up or expanded (progress > 0).
                        if (state.progress > 0f) {
                            backgroundColor
                        } else {
                            Color.Transparent
                        }
                    } else {
                        backgroundColor.copy(
                            alpha = backgroundColor.alpha * state.progress.coerceIn(0f, 1f),
                        )
                    },
                ),
    ) {
        if (state.isExpandedOrExpanding && backHandlerEnabled) {
            PredictiveStyleBackHandler(
                backState = predictiveBack,
                enabled = true,
                onBack = state::collapseSoft,
            )
        }

        if (keepContentAlive) {
            // Always compose the content, but hide it when collapsed.
            // This keeps stateful composables (e.g. InlineVideoPlayer's
            // ExoPlayer) alive across collapse/expand cycles.
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (morphMode) {
                                // Morph: fade + scale (0.94 → 1.0) based on
                                // expand progress, with a 25% dead-band so the
                                // content stays opaque for the first part of a
                                // drag. No offset — the offset is applied on
                                // the sheet root so the whole sheet slides.
                                val p = state.progress.coerceIn(0f, 1f)
                                alpha = ((p - 0.25f) * 4).coerceIn(0f, 1f)
                                scaleX = 0.94f + 0.06f * p
                                scaleY = 0.94f + 0.06f * p
                            } else {
                                alpha = if (state.isCollapsed) 0f else ((state.progress - 0.25f) * 4).coerceIn(0f, 1f)
                            }
                        },
                content = content,
            )
        } else if (!state.isCollapsed) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (morphMode) {
                                val p = state.progress.coerceIn(0f, 1f)
                                alpha = ((p - 0.25f) * 4).coerceIn(0f, 1f)
                                scaleX = 0.94f + 0.06f * p
                                scaleY = 0.94f + 0.06f * p
                            } else {
                                alpha = ((state.progress - 0.25f) * 4).coerceIn(0f, 1f)
                            }
                        },
                content = content,
            )
        }

        if (!state.isExpanded && (onDismiss == null || !state.isDismissed)) {
            Box(
                modifier =
                    Modifier
                        .graphicsLayer {
                            alpha = 1f - (state.progress * 4).coerceAtMost(1f)
                        }.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCollapsedContentClick ?: state::expandSoft,
                        ).fillMaxWidth()
                        .height(state.collapsedBound),
                content = collapsedContent,
            )
        }
    }
}

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    private val animationsDisabled: Boolean,
    val collapsedBound: Dp,
    initialAnchor: Int = DISMISSED_ANCHOR,
) : DraggableState by draggableState {
    val dismissedBound: Dp
        get() = animatable.lowerBound!!

    val expandedBound: Dp
        get() = animatable.upperBound!!

    val value by animatable.asState()

    var targetAnchor by mutableIntStateOf(initialAnchor)
        private set

    val isDismissed by derivedStateOf {
        value == animatable.lowerBound!!
    }

    val isCollapsed by derivedStateOf {
        value == collapsedBound
    }

    val isExpanded by derivedStateOf {
        value == animatable.upperBound
    }

    val isExpandedOrExpanding: Boolean
        get() = targetAnchor == EXPANDED_ANCHOR

    val progress by derivedStateOf {
        1f - (animatable.upperBound!! - animatable.value) / (animatable.upperBound!! - collapsedBound)
    }

    private fun updateAnchor(anchor: Int) {
        targetAnchor = anchor
        onAnchorChanged(anchor)
    }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        updateAnchor(COLLAPSED_ANCHOR)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        updateAnchor(EXPANDED_ANCHOR)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(animatable.upperBound!!, animationSpec)
        }
    }

    private fun collapse() {
        collapse(if (animationsDisabled) snap() else BottomSheetAnimationSpec)
    }

    private fun expand() {
        expand(if (animationsDisabled) snap() else BottomSheetAnimationSpec)
    }

    fun collapseSoft() {
        collapse(if (animationsDisabled) snap() else BottomSheetSoftAnimationSpec)
    }

    fun expandSoft() {
        expand(if (animationsDisabled) snap() else BottomSheetSoftAnimationSpec)
    }

    fun dismiss() {
        updateAnchor(DISMISSED_ANCHOR)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(animatable.lowerBound!!, if (animationsDisabled) snap() else BottomSheetAnimationSpec)
        }
    }

    fun snapTo(value: Dp) {
        updateAnchor(
            when (value) {
                expandedBound -> EXPANDED_ANCHOR
                collapsedBound -> COLLAPSED_ANCHOR
                dismissedBound -> DISMISSED_ANCHOR
                else -> COLLAPSED_ANCHOR
            },
        )
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.snapTo(value)
        }
    }

    fun performFling(
        velocity: Float,
        onDismiss: (() -> Unit)?,
    ) {
        if (velocity > 250) {
            expand()
        } else if (velocity < -250) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse()
            }
        } else {
            val l0 = dismissedBound
            val l1 = (collapsedBound - dismissedBound) / 2
            val l2 = (expandedBound - collapsedBound) / 2
            val l3 = expandedBound

            when (value) {
                in l0..l1 -> {
                    if (onDismiss != null) {
                        dismiss()
                        onDismiss.invoke()
                    } else {
                        collapse()
                    }
                }

                in l1..l2 -> {
                    collapse()
                }

                in l2..l3 -> {
                    expand()
                }

                else -> {
                    Unit
                }
            }
        }
    }

    /**
     * Single instance per sheet, deliberately: the connection carries per-sheet state
     * (`isTopReached`), so every read must return the same object.
     */
    val preUpPostDownNestedScrollConnection: NestedScrollConnection by lazy {
        object : NestedScrollConnection {
            var isTopReached = false

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (isExpanded && available.y < 0) {
                    isTopReached = false
                }

                return if (isTopReached && available.y < 0 && source == NestedScrollSource.UserInput) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!isTopReached) {
                    isTopReached = consumed.y == 0f && available.y > 0
                }

                return if (isTopReached && source == NestedScrollSource.UserInput) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (isTopReached) {
                    val velocity = -available.y
                    performFling(velocity, null)

                    available
                } else {
                    Velocity.Zero
                }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                isTopReached = false
                return Velocity.Zero
            }
        }
    }
}

const val EXPANDED_ANCHOR = 2
const val COLLAPSED_ANCHOR = 1
const val DISMISSED_ANCHOR = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = DISMISSED_ANCHOR,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val animationsDisabled = LocalAnimationsDisabled.current

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }
    val animatable =
        remember {
            Animatable(0.dp, Dp.VectorConverter)
        }

    return remember(dismissedBound, expandedBound, collapsedBound, coroutineScope, animationsDisabled) {
        val initialValue =
            when (previousAnchor) {
                EXPANDED_ANCHOR -> expandedBound
                COLLAPSED_ANCHOR -> collapsedBound
                DISMISSED_ANCHOR -> dismissedBound
                else -> error("Unknown BottomSheet anchor")
            }

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), expandedBound)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            animatable.animateTo(initialValue, if (animationsDisabled) snap() else BottomSheetAnimationSpec)
        }

        BottomSheetState(
            draggableState =
                DraggableState { delta ->
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        animatable.snapTo(animatable.value - with(density) { delta.toDp() })
                    }
                },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            animationsDisabled = animationsDisabled,
            collapsedBound = collapsedBound,
            initialAnchor = previousAnchor,
        )
    }
}

@Composable
fun Modifier.bottomSheetDraggable(
    state: BottomSheetState,
    onDismiss: (() -> Unit)? = null,
): Modifier =
    this.pointerInput(state) {
        val velocityTracker = VelocityTracker()

        detectVerticalDragGestures(
            onVerticalDrag = { change, dragAmount ->
                velocityTracker.addPointerInputChange(change)
                state.dispatchRawDelta(dragAmount)
            },
            onDragCancel = {
                val velocity = -velocityTracker.calculateVelocity().y
                velocityTracker.resetTracking()
                state.performFling(velocity, onDismiss)
            },
            onDragEnd = {
                val velocity = -velocityTracker.calculateVelocity().y
                velocityTracker.resetTracking()
                state.performFling(velocity, onDismiss)
            },
        )
    }
