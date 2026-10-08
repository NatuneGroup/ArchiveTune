/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



/*
 * Bitchord player style — the inline queue.
 *
 * Ported from BitChord (https://github.com/kushagrasinghx/BitChord), the
 * InlineQueue / QueueDragState / InlineQueueRow / edgeScrollSpeed /
 * stableQueueKeys sections of ui/player/NowPlayingScreen.kt.
 *
 * Adaptations for ArchiveTune (documented inline):
 *  - BitChord's `Song` model is replaced by a lightweight [BitChordQueueSong]
 *    mapped from ArchiveTune's ExoPlayer timeline windows.
 *  - BitChord's AutoPlay queue section (fromAutoplay / autoplaySectionStart)
 *    is omitted: ArchiveTune has no autoplay engine, so the queue is a single
 *    section with the same rows, drag-to-reorder, edge auto-scroll and Clear.
 *
 * Belongs exclusively to the Bitchord player style; not shared with any other
 * player style, per the self-containment rule for player styles.
 */

package moe.rukamori.archivetune.ui.player.bitchord

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.composed
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

/** One row of the inline queue, mapped from an ExoPlayer timeline window. */
internal data class BitChordQueueSong(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
)

/** A hairline border that keeps dark artwork from melting into the backdrop. */
private fun Modifier.thumbnailBorder(shape: Shape): Modifier = composed {
    this.border(
        width = 1.dp,
        color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
        shape = shape,
    )
}

/** The live queue, in the player itself. */
@Composable
internal fun InlineQueue(
    queue: List<BitChordQueueSong>,
    currentIndex: Int,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val keepScroll = remember(listState) { keepScrollInList(listState) }

    // A song can be queued twice, so the id alone isn't always a unique key
    // — LazyColumn throws on a repeat. Suffixing by how many times that id
    // has already been seen keeps every key unique while staying stable
    // across a reorder, which plain id+index (the previous key) wasn't: that
    // changed on every swap and silently broke animateItem's ability to
    // tell "this row moved" from "this row was replaced".
    val keys = remember(queue) { queue.stableQueueKeys() }

    // Nothing moves at or above the track playing right now: what's already
    // been played is history, and the current row is the boundary the section
    // is drawn from. Only what's still to come is the user's to reorder.
    val firstMovable = currentIndex + 1
    val drag = rememberQueueDragState(
        listState = listState,
        lazyRange = firstMovable until queue.size,
        lazyOffset = 0,
        onMove = onMove,
    )

    // Open on what's playing, not at the top of a long queue.
    //
    // Never mid-drag, though. A track ending while a row is held would jump the
    // list out from under the finger, and the jump takes the list's scroll off
    // the edge auto-scroll below — which would leave the rest of that drag
    // unable to scroll at all. Reordering is also the one time the user is
    // certainly looking somewhere other than at the current track.
    LaunchedEffect(currentIndex) {
        val holding = drag.draggedKey != null
        if (!holding && currentIndex in queue.indices) {
            listState.scrollToItem(currentIndex)
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Clear",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .bleedHorizontally(PLAYER_GUTTER)
                // Without this the sheet treats the list's leftover scroll as a
                // drag on itself and slides the whole player away.
                .nestedScroll(keepScroll)
                .fadingEdges(),
            contentPadding = PaddingValues(horizontal = PLAYER_GUTTER),
        ) {
            itemsIndexed(
                items = queue,
                key = { index, _ -> keys[index] },
            ) { index, song ->
                val key = keys[index]
                val dragging = drag.draggedKey == key
                InlineQueueRow(
                    song = song,
                    isCurrent = index == currentIndex,
                    onClick = { onJumpTo(index) },
                    onRemove = { onRemove(index) },
                    // Only what's still queued ahead. The playing track and
                    // everything already played sit above the line a drag
                    // can't cross.
                    draggable = index >= firstMovable,
                    dragging = dragging,
                    onDragStart = { drag.onDragStart(key) },
                    onDrag = drag::onDrag,
                    onDragEnd = drag::onDragEnd,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) drag.renderOffset else 0f }
                        // The dragged row follows the finger, so it is the one
                        // row that must not also be animating to a slot. Its
                        // neighbours skip the animation too, for as long as
                        // *anything* in the section is being dragged.
                        .then(if (drag.draggedKey != null) Modifier else Modifier.animateItem()),
                )
            }
        }
    }
}

/**
 * A key per row, stable across a reorder and unique even when the same song
 * appears twice — the Nth time a given id is seen gets suffixed with that
 * count, so two copies of one song each keep their own identity instead of
 * colliding on the same LazyColumn key.
 */
private fun List<BitChordQueueSong>.stableQueueKeys(prefix: String = ""): List<String> {
    val seen = HashMap<String, Int>()
    return map { song ->
        val n = seen.getOrDefault(song.id, 0)
        seen[song.id] = n + 1
        if (n == 0) "$prefix${song.id}" else "$prefix${song.id}#$n"
    }
}

/**
 * How far in from either end of the queue a held row starts scrolling the list, and how fast it
 * scrolls once it is all the way at the edge.
 */
private val QUEUE_EDGE_SCROLL_ZONE = 40.dp
private val QUEUE_EDGE_SCROLL_SPEED = 340.dp

/**
 * The pace, in pixels a second, to scroll a list at while a row occupying [top] to [bottom] is held
 * in a viewport spanning [viewportStart] to [viewportEnd] — negative towards the start of the list,
 * positive towards its end, and zero while the row is clear of both edges.
 */
internal fun edgeScrollSpeed(
    top: Float,
    bottom: Float,
    viewportStart: Int,
    viewportEnd: Int,
    zone: Float,
    speed: Float,
): Float {
    if (zone <= 0f) return 0f
    val intoStart = (viewportStart + zone) - top
    val intoEnd = bottom - (viewportEnd - zone)
    val reach = when {
        intoStart > 0f && intoEnd <= 0f -> -intoStart
        intoEnd > 0f && intoStart <= 0f -> intoEnd
        else -> return 0f
    }
    val ramp = speed * (0.2f + 0.8f * (abs(reach) / zone).coerceAtMost(1f))
    return if (reach < 0f) -ramp else ramp
}

/** Drag-to-reorder for the queue's LazyColumn. */
@Composable
private fun rememberQueueDragState(
    listState: LazyListState,
    lazyRange: IntRange,
    lazyOffset: Int,
    onMove: (Int, Int) -> Unit,
): QueueDragState {
    val state = remember(listState) { QueueDragState(listState) }
    state.lazyRange = lazyRange
    state.lazyOffset = lazyOffset
    state.onMove = onMove
    with(LocalDensity.current) {
        state.edgeZone = QUEUE_EDGE_SCROLL_ZONE.toPx()
        state.edgeSpeed = QUEUE_EDGE_SCROLL_SPEED.toPx()
    }

    // Held near either end of the list, the row scrolls it. A track can be
    // moved across a queue many screens long without letting go, where before
    // the only way down was to drop the row at the edge, scroll by hand and
    // pick it up again, once per screenful.
    val direction = state.autoScrollDir
    LaunchedEffect(state, direction) {
        if (direction == 0) return@LaunchedEffect
        listState.scroll {
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // A frame the system dropped, paid back in full, lands as a
                // lurch — so it isn't.
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
                previous = now
                val scrolled = scrollBy(state.autoScrollSpeed * seconds)
                // Nowhere left to scroll, or the row has left the edge and the
                // speed has gone to nothing. Let the list's scroll go rather
                // than spin on it holding the lock.
                if (scrolled == 0f) break
                state.onScrolled()
            }
        }
    }
    return state
}

/**
 * Where a held row is being held, what it may do from there, and the moves it has sent to the
 * player on the way.
 */
private class QueueDragState(private val listState: LazyListState) {
    var lazyRange: IntRange = IntRange.EMPTY
    var lazyOffset: Int = 0
    var onMove: (Int, Int) -> Unit = { _, _ -> }

    /** [QUEUE_EDGE_SCROLL_ZONE] and [QUEUE_EDGE_SCROLL_SPEED], in pixels. */
    var edgeZone: Float = 0f
    var edgeSpeed: Float = 0f

    /** LazyColumn key of the row being dragged; null at rest. */
    var draggedKey by mutableStateOf<Any?>(null)
        private set

    /** How far from its own slot to draw the held row, in pixels. */
    var renderOffset by mutableFloatStateOf(0f)
        private set

    /**
     * Which way the list is scrolling itself under the held row: -1 towards the
     * start of the queue, 1 towards its end, 0 not at all. State, because this
     * is what starts and stops the loop that does the scrolling.
     */
    var autoScrollDir by mutableIntStateOf(0)
        private set

    /**
     * How fast it is doing so, signed, in pixels a second — and deliberately
     * *not* state. It changes with every pixel of drag travel, and only the
     * loop reads it, once a frame; as state it would recompose the whole queue
     * on every touch event to tell the composition something it has no use for.
     */
    var autoScrollSpeed: Float = 0f
        private set

    /**
     * Where the finger is holding the row's centre, in viewport pixels. NaN
     * until the first drag event, which takes it from the row's own slot — a
     * drag begins with the row exactly where it already was.
     */
    private var heldCenter: Float = Float.NaN

    /** Where the last swap put the row, until the list is laid out with it. */
    private var awaiting: Int? = null

    fun onDragStart(key: Any) {
        draggedKey = key
        heldCenter = Float.NaN
        renderOffset = 0f
        awaiting = null
        setAutoScroll(0f)
    }

    /** The finger moved [deltaY] pixels and the list stayed put. */
    fun onDrag(deltaY: Float) = settle(deltaY)

    /** The list moved under the finger and the finger stayed put. */
    fun onScrolled() = settle(0f)

    fun onDragEnd() {
        draggedKey = null
        heldCenter = Float.NaN
        renderOffset = 0f
        awaiting = null
        setAutoScroll(0f)
    }

    /**
     * Takes the drag in [deltaY] pixels further, then reads the list back to
     * see where that leaves the row: where to draw it, whether it has reached
     * an edge, and whether it has reached a neighbour worth trading with.
     */
    private fun settle(deltaY: Float) {
        val key = draggedKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        // The row's own slot is off screen. There is nothing to measure an
        // edge or a swap against and nothing to draw against either, so the
        // way back is to stand still and let the swap already sent land and
        // bring the slot into view.
        val dragged = items.find { it.key == key } ?: run {
            setAutoScroll(0f)
            return
        }
        val half = dragged.size / 2f
        if (heldCenter.isNaN()) heldCenter = dragged.offset + half
        heldCenter += deltaY
        holdToSection(items, dragged)

        val top = heldCenter - half
        // Aimed before the guard below, not after: a swap in flight is a frame
        // or two of the list not having caught up yet, and the scroll should
        // carry on evenly through those rather than stutter once per row.
        aimAutoScroll(top, dragged)
        renderOffset = insideViewport(top, dragged.size) - dragged.offset

        // A swap already sent but not yet laid out: deciding the next one off
        // a position the list has moved on from would send a second move for
        // a swap that has already happened, and the two would fight.
        awaiting?.let {
            if (dragged.index != it) return
            awaiting = null
        }
        val target = swapTarget(items, dragged) ?: return
        onMove(dragged.index - lazyOffset, target.index - lazyOffset)
        awaiting = target.index
    }

    /**
     * The neighbour [heldCenter] has reached far enough into to trade places
     * with, or null while there is none to trade with yet.
     */
    private fun swapTarget(
        items: List<LazyListItemInfo>,
        dragged: LazyListItemInfo,
    ): LazyListItemInfo? {
        // Only rows of this section are fair targets.
        val target = items
            .filter { it.index in lazyRange && it.index != dragged.index }
            .minByOrNull { abs((it.offset + it.size / 2f) - heldCenter) }
            ?: return null
        // Held short of halfway the rows would swap back and forth over a
        // single pixel of travel; a full half-height of overlap is what makes
        // one swap per row crossed.
        if (abs(heldCenter - (target.offset + target.size / 2f)) > target.size / 2f) return null
        // Never with the row the list is keeping its own place by, while there
        // is still list above it to scroll.
        if (target.index == listState.firstVisibleItemIndex && listState.canScrollBackward) {
            return null
        }
        return target
    }

    /**
     * Points the auto-scroll at whichever edge the row now spanning [top] has
     * reached, if either — but only while there is both a row that way for it
     * to swap with and list left to scroll.
     */
    private fun aimAutoScroll(top: Float, dragged: LazyListItemInfo) {
        val info = listState.layoutInfo
        val speed = edgeScrollSpeed(
            top = top,
            bottom = top + dragged.size,
            viewportStart = info.viewportStartOffset,
            viewportEnd = info.viewportEndOffset,
            zone = edgeZone,
            speed = edgeSpeed,
        )
        val blocked = when {
            speed < 0f -> dragged.index <= lazyRange.first || !listState.canScrollBackward
            speed > 0f -> dragged.index >= lazyRange.last || !listState.canScrollForward
            else -> true
        }
        setAutoScroll(if (blocked) 0f else speed)
    }

    /** Holds the drag inside the section it started in. */
    private fun holdToSection(items: List<LazyListItemInfo>, dragged: LazyListItemInfo) {
        val half = dragged.size / 2f
        items.firstOrNull { it.index == lazyRange.first }?.let {
            heldCenter = heldCenter.coerceAtLeast(it.offset + half)
        }
        items.firstOrNull { it.index == lazyRange.last }?.let {
            heldCenter = heldCenter.coerceAtMost(it.offset + it.size - half)
        }
    }

    /** [top], kept where a row of [size] can still be seen — see [renderOffset]. */
    private fun insideViewport(top: Float, size: Int): Float {
        val info = listState.layoutInfo
        val minTop = info.viewportStartOffset.toFloat()
        val maxTop = (info.viewportEndOffset - size).toFloat().coerceAtLeast(minTop)
        return top.coerceIn(minTop, maxTop)
    }

    private fun setAutoScroll(speed: Float) {
        autoScrollSpeed = speed
        val direction = when {
            speed > 0f -> 1
            speed < 0f -> -1
            else -> 0
        }
        if (autoScrollDir != direction) autoScrollDir = direction
    }
}

@Composable
private fun InlineQueueRow(
    song: BitChordQueueSong,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    draggable: Boolean = false,
    dragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    // LazyColumn disposes a row the instant its slot leaves the viewport, and
    // that takes the drag gesture below down with it: the coroutine running
    // [detectDragGestures] is cancelled where it stands, so neither onDragEnd
    // nor onDragCancel is ever reached and the drag is left held by nothing —
    // the row comes back into view highlighted and offset from its slot, and
    // stays that way until the queue is closed. The swap guard in
    // [QueueDragState.swapTarget] is what stops the slot being thrown out of
    // the viewport in the first place; this is here because "the gesture ended
    // and nothing was told" should not be a state the queue can be left in at
    // all, whatever put it there.
    val heldOnDispose by rememberUpdatedState(dragging)
    val endDrag by rememberUpdatedState(onDragEnd)
    DisposableEffect(Unit) {
        onDispose { if (heldOnDispose) endDrag() }
    }
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (dragging) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (draggable) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "Drag to reorder",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(20.dp)
                    // DragHandle's glyph sits well inset from the edges of
                    // its own bounding box — this pulls it back to the row's
                    // actual left edge instead of leaving a gap in front of it.
                    .offset(x = (-4).dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                        )
                    },
            )
            Spacer(Modifier.width(4.dp))
        }
        AsyncImage(
            model = ImageRequest.Builder(context).data(song.thumbnailUrl).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .thumbnailBorder(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = "Now playing",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove from queue",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
