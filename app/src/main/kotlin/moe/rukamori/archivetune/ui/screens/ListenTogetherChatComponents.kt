/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Listen Together chat building blocks: avatars, typing indicator, message
 * bubbles with swipe-to-reply, reactions, the Instagram-style anchored action
 * popup (morph animation + liquid glass) and the full emoji picker.
 */



package moe.rukamori.archivetune.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalListenTogetherManager
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListenTogetherAvatarIndexKey
import moe.rukamori.archivetune.listentogether.ChatMessagePayload
import moe.rukamori.archivetune.listentogether.RepliedMessage
import moe.rukamori.archivetune.listentogether.TrackInfo
import moe.rukamori.archivetune.listentogether.TypingUser
import moe.rukamori.archivetune.utils.rememberPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin
import androidx.compose.animation.core.animateFloatAsState

/** Quick-reaction strip shown first in the anchored popup. */
internal val QuickReactionEmojis = listOf("❤️", "👍", "😂", "😮", "😢", "🔥")

// The full emoji keyboard lives in EmojiCatalog.kt (generated from the official
// Unicode emoji-test.txt: 3781 fully-qualified emoji across 9 CLDR groups).

private val ChatAvatarOptions = listOf(
    R.drawable.person, R.drawable.man, R.drawable.woman, R.drawable.man_1, R.drawable.man_2,
    R.drawable.man_3, R.drawable.man_4, R.drawable.man_5, R.drawable.man_6, R.drawable.woman_1,
    R.drawable.woman_2, R.drawable.woman_3, R.drawable.woman_4, R.drawable.luxury_women,
)

/**
 * Circular room-member avatar for the chat: custom broadcast picture first,
 * then the picked avatar-index asset, then the username initial.
 */
@Composable
internal fun ChatAvatar(
    userId: String,
    fallbackName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
) {
    val manager = LocalListenTogetherManager.current
    val context = LocalContext.current
    val roomState = manager?.roomState?.collectAsState()?.value
    val currentUserId = manager?.userId?.collectAsState()?.value
    val customAvatars = manager?.customAvatars?.collectAsState()?.value ?: emptyMap()

    // The avatar preference participates in the key so picking a new custom
    // profile picture refreshes the self avatar live (and falling back from a
    // custom picture to an index asset does too).
    val (selfAvatarPref) = rememberPreference(ListenTogetherAvatarIndexKey, 0)

    // Restored history from a previous session carries that session's user
    // ids, so identity falls back to the username: a message is "mine" when
    // its username matches the local user's, and another member's avatar
    // resolves through the room-state entry with their username.
    val myUsername = manager?.currentUsername
    val isSelf = userId == currentUserId || (myUsername != null && fallbackName == myUsername)

    val user =
        roomState?.users?.find { it.userId == userId }
            ?: roomState?.users?.find { it.username == fallbackName }

    val bytes =
        if (manager == null) {
            null
        } else if (isSelf) {
            remember(manager, currentUserId, selfAvatarPref) {
                manager.customAvatarFor(currentUserId)
            }
        } else {
            // Direct hit first; when the message's user id is from an older
            // session, route through the member's CURRENT id so the avatar
            // broadcast they sent at join time still applies.
            customAvatars[userId] ?: user?.let { customAvatars[it.userId] }
        }
    val bitmap: Bitmap? =
        produceState<Bitmap?>(initialValue = null, bytes) {
            value =
                bytes?.let { encoded ->
                    withContext(Dispatchers.Default) {
                        runCatching { BitmapFactory.decodeByteArray(encoded, 0, encoded.size) }.getOrNull()
                    }
                }
        }.value

    val avatarIndex = user?.avatarIndex ?: 0
    val isHost = user?.isHost == true
    val initial = (user?.username?.takeIf { it.isNotBlank() } ?: fallbackName).take(1).uppercase()

    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    when {
                        isHost -> MaterialTheme.colorScheme.primary
                        isSelf -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.tertiaryContainer
                    }
                )
                .then(
                    if (outlined) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape,
                        )
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            avatarIndex == 0 -> {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color =
                        when {
                            isHost -> MaterialTheme.colorScheme.onPrimary
                            isSelf -> MaterialTheme.colorScheme.onSecondary
                            else -> MaterialTheme.colorScheme.onTertiaryContainer
                        },
                )
            }
            else -> {
                Image(
                    painter = painterResource(ChatAvatarOptions.getOrElse(avatarIndex) { R.drawable.person }),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        }
    }
}

/**
 * Typing indicator: the typing members' avatars layered on top of each other
 * (later members draw above earlier ones, separated by a surface outline),
 * followed by bouncing dots and the member names.
 */
@Composable
internal fun TypingIndicatorRow(
    typingUsers: List<TypingUser>,
    modifier: Modifier = Modifier,
) {
    if (typingUsers.isEmpty()) return
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "typing-phase",
    )

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(((typingUsers.size - 1) * 13 + 20).dp).height(20.dp)) {
            typingUsers.take(5).forEachIndexed { index, typing ->
                ChatAvatar(
                    userId = typing.userId,
                    fallbackName = typing.username,
                    size = 20.dp,
                    outlined = index > 0,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (index * 13).dp)
                            .zIndex(index.toFloat()),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { i ->
                val step = (phase + i * 0.33f) % 1f
                val bounce = sin(step * Math.PI).toFloat()
                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = 2.dp)
                            .offset(y = (-bounce * 3).dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f + 0.65f * bounce)),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        val names =
            when {
                typingUsers.size == 1 -> typingUsers.first().username
                typingUsers.size == 2 -> "${typingUsers[0].username} & ${typingUsers[1].username}"
                else -> "${typingUsers.take(2).joinToString { it.username }}…"
            }
        Text(
            text = stringResource(R.string.listen_together_chat_typing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = names,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Data of the message whose anchored action popup is open. [isHost] is the
 * LOCAL user's host status (drives "delete for everyone" availability), as
 * opposed to [isMe] which is about the pressed message's author. */
internal data class MessageActionTarget(
    val message: ChatMessagePayload,
    val bounds: Rect,
    val isMe: Boolean,
    val isHost: Boolean = false,
)

/**
 * Instagram-style action popup anchored to a long-pressed message bubble:
 * quick reactions, the full-emoji entry and reply/copy/edit/pin/delete, opening
 * with the lyrics-popup morph (spring scale + fade from the bubble edge) over a
 * liquid-glass backdrop.
 *
 * The glass backdrop is a LOCAL one recorded from the chat content only (see
 * CommentTogetherScreen): drawing from the app-wide LocalLiquidGlassBackdrop
 * here would make the popup — which lives inside the NavHost subtree that the
 * global backdrop records — sample a layer that is still being recorded into
 * itself. That circular rendering crashes the RenderThread with SIGSEGV. The
 * popup is composed as a SIBLING of the recorded box, so sampling the local
 * layer is a plain one-way read.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MessageActionsPopup(
    target: MessageActionTarget,
    backdrop: LayerBackdrop?,
    myUsername: String?,
    onReact: (String) -> Unit,
    onOpenEmojiPicker: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onPinToggle: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var dismissed by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.35f) }
    val alphaAnim = remember { Animatable(0f) }

    var overlayWidthPx by remember { mutableIntStateOf(0) }
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    var popupWidthPx by remember { mutableIntStateOf(0) }
    var popupHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(1f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))
        }
        val alphaJob = scope.launch { alphaAnim.animateTo(1f, tween(180)) }
        scaleJob.join()
        alphaJob.join()
    }

    LaunchedEffect(dismissed) {
        if (!dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(0.35f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
        }
        val alphaJob = scope.launch { alphaAnim.animateTo(0f, tween(160)) }
        scaleJob.join()
        alphaJob.join()
        onDismiss()
    }

    fun popupPlacement(): Pair<IntOffset, Boolean> {
        val marginPx = with(density) { 12.dp.toPx() }.toInt()
        val estimatedHeight =
            if (popupHeightPx > 0) popupHeightPx else with(density) { 112.dp.toPx() }.toInt()
        val estimatedWidth =
            if (popupWidthPx > 0) popupWidthPx else with(density) { 300.dp.toPx() }.toInt()
        val screenWidthPx = if (overlayWidthPx > 0) overlayWidthPx else estimatedWidth + 2 * marginPx
        val x =
            if (target.isMe) {
                (target.bounds.right.toInt() - estimatedWidth - 8).coerceAtLeast(marginPx)
            } else {
                (target.bounds.left.toInt() + 8).coerceAtMost(screenWidthPx - estimatedWidth - marginPx)
            }.coerceIn(marginPx, (screenWidthPx - estimatedWidth - marginPx).coerceAtLeast(marginPx))
        val below = target.bounds.bottom + 6 < overlayHeightPx - estimatedHeight - marginPx
        val y =
            if (below) {
                target.bounds.bottom.toInt() + 6
            } else {
                (target.bounds.top.toInt() - estimatedHeight - 6).coerceAtLeast(marginPx)
            }
        return IntOffset(x, y.coerceAtLeast(marginPx)) to below
    }

    val scrimAlpha = 0.25f * alphaAnim.value

    // Both looks share the same rounded menu chrome: an 18dp rounded sheet with
    // divider rules between the reaction strip and the action row. With glass
    // the surface samples and refracts the chat behind it; without glass it is
    // a clean elevated dark menu (never an unclipped black square).
    val popupShape = RoundedCornerShape(18.dp)

    val frostedBlurModifier =
        remember(backdrop) {
            if (backdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    effects = {
                        colorControls(saturation = 1.7f)
                        blur(20f.dp.toPx())
                        lens(
                            refractionHeight = 16f.dp.toPx(),
                            refractionAmount = 40f.dp.toPx(),
                        )
                    },
                    onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                    onDrawSurface = {
                        // A light tint keeps the white icons/labels legible over
                        // bright chat content behind the glass.
                        drawRect(Color.Black.copy(alpha = 0.30f))
                    },
                    shape = { popupShape },
                )
            } else {
                Modifier
                    .background(Color(0xF226262B), popupShape)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), popupShape)
            }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    overlayWidthPx = size.width
                    overlayHeightPx = size.height
                }
                .background(Color.Black.copy(alpha = scrimAlpha))
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (!dismissed) dismissed = true },
                ),
    ) {
        val (offset, opensBelow) = popupPlacement()

        Column(
            modifier =
                Modifier
                    .offset { offset }
                    .onSizeChanged { size ->
                        popupWidthPx = size.width
                        popupHeightPx = size.height
                    }
                    .widthIn(max = 320.dp)
                    .graphicsLayer {
                        this.alpha = alphaAnim.value
                        this.scaleX = scaleAnim.value
                        this.scaleY = scaleAnim.value
                        this.transformOrigin =
                            TransformOrigin(
                                if (target.isMe) 0.92f else 0.08f,
                                if (opensBelow) 0f else 1f,
                            )
                        this.shadowElevation = 18.dp.toPx()
                        this.shape = popupShape
                        this.clip = false
                    }
                    .clip(popupShape)
                    .then(frostedBlurModifier)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // Quick reactions row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                QuickReactionEmojis.forEach { emoji ->
                    Box(
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        onReact(emoji)
                                        if (!dismissed) dismissed = true
                                    },
                                )
                                .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 22.sp)
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onOpenEmojiPicker,
                            )
                            .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = stringResource(R.string.listen_together_chat_all_emojis),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier
                        .padding(vertical = 6.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.14f)),
            )

            // Action row — a FlowRow so the extra delete chips wrap onto a
            // second line instead of overflowing the 320dp-wide popup.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PopupActionChip(icon = R.drawable.reply, label = stringResource(R.string.listen_together_chat_reply_label)) {
                    onReply()
                    if (!dismissed) dismissed = true
                }
                PopupActionChip(icon = R.drawable.copy, label = stringResource(R.string.listen_together_chat_copy)) {
                    onCopy()
                    if (!dismissed) dismissed = true
                }
                if (target.isMe) {
                    PopupActionChip(icon = R.drawable.edit, label = stringResource(R.string.edit)) {
                        onEdit()
                        if (!dismissed) dismissed = true
                    }
                }
                PopupActionChip(
                    icon = R.drawable.push_pin,
                    label = stringResource(
                        if (target.message.pinned) R.string.listen_together_chat_unpin
                        else R.string.listen_together_chat_pin
                    ),
                ) {
                    onPinToggle()
                    if (!dismissed) dismissed = true
                }
                if (target.isMe) {
                    // One's own messages only ever delete room-wide.
                    PopupActionChip(icon = R.drawable.delete, label = stringResource(R.string.delete)) {
                        onDeleteForEveryone()
                        if (!dismissed) dismissed = true
                    }
                } else {
                    // Other people's messages: a private hide plus, for the
                    // host, a room-wide moderation delete.
                    PopupActionChip(icon = R.drawable.delete, label = stringResource(R.string.delete)) {
                        onDeleteForMe()
                        if (!dismissed) dismissed = true
                    }
                    if (target.isHost) {
                        PopupActionChip(
                            icon = R.drawable.delete_forever,
                            label = stringResource(R.string.listen_together_chat_delete_for_everyone),
                        ) {
                            onDeleteForEveryone()
                            if (!dismissed) dismissed = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupActionChip(
    icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.10f),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Full emoji picker sheet opened from the anchored popup's "+" chip: every
 * emoji an Android keyboard offers (3781 fully-qualified sequences from the
 * Unicode emoji-test data), grouped with headers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(44.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = screenHeight * 0.72f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            EmojiCatalog.forEach { (category, emojis) ->
                item(key = "header_$category") {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                    )
                }
                gridItems(
                    items = emojis,
                    key = { emoji -> "$category$emoji" },
                ) { emoji ->
                    Box(
                        modifier =
                            Modifier
                                .padding(4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onPick(emoji) },
                                )
                                .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 24.sp)
                    }
                }
            }
        }
    }
}

/** Horizontal chip row of the reactions already applied to a message. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionsRow(
    message: ChatMessagePayload,
    myUsername: String?,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.reactions.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        message.reactions.forEach { (emoji, users) ->
            val mine = myUsername != null && users.contains(myUsername)
            Surface(
                onClick = { onToggle(emoji) },
                shape = RoundedCornerShape(999.dp),
                color =
                    if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor =
                    if (mine) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                border =
                    androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color =
                            if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant,
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = emoji, fontSize = 14.sp)
                    if (users.size > 1) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = users.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One chat message: the sender's avatar before their name (incoming) and the
 * local user's own avatar at the trailing edge (own messages — their custom
 * profile picture is visible to themselves, exactly as others' are), a
 * swipe-to-reply bubble with a long-press action popup trigger, reply preview,
 * shared-song cards, edited/pinned marks, deleted tombstones, the reaction
 * chips and a highlight flash when jumped to from the pinned banner.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageItem(
    message: ChatMessagePayload,
    isMe: Boolean,
    myUsername: String?,
    onReply: (ChatMessagePayload) -> Unit,
    onLongPress: (ChatMessagePayload, Rect) -> Unit,
    onToggleReaction: (ChatMessagePayload, String) -> Unit,
    onPlayTrack: (TrackInfo) -> Unit,
    highlighted: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val bubbleBounds = remember { mutableStateOf(Rect.Zero) }
    val swipeOffset = remember { Animatable(0f) }
    val replyThresholdPx = with(density) { 72.dp.toPx() }
    val maxSwipePx = with(density) { 130.dp.toPx() }

    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlighted) 0.35f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "chat-jump-highlight",
    )

    val bubbleColor =
        if (isMe) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isMe) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    val replyBgColor =
        if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
    ) {
        if (!isMe) {
            ChatAvatar(
                userId = message.userId,
                fallbackName = message.username,
                size = 28.dp,
                modifier = Modifier.align(Alignment.Top),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            if (!isMe) {
                Text(
                    text = message.username,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp, bottom = 3.dp),
                )
            }

            Box {
                // Reply hint revealed behind the bubble while swiping.
                val swipeProgress = (swipeOffset.value / replyThresholdPx).coerceIn(0f, 1f)
                Icon(
                    painter = painterResource(R.drawable.reply),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f + 0.75f * swipeProgress),
                    modifier =
                        Modifier
                            .align(if (isMe) Alignment.CenterEnd else Alignment.CenterStart)
                            .offset {
                                val padPx = with(this) { 6.dp.toPx() }
                                IntOffset(
                                    x = if (isMe) (swipeOffset.value + padPx).toInt() else -(swipeOffset.value + padPx).toInt(),
                                    y = 0,
                                )
                            }
                            .size(20.dp)
                            .graphicsLayer { alpha = swipeProgress },
                )

                Surface(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isMe) 16.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 16.dp,
                    ),
                    tonalElevation = 2.dp,
                    modifier =
                        Modifier
                            .onGloballyPositioned { coordinates ->
                                bubbleBounds.value = coordinates.boundsInRoot()
                            }
                            .graphicsLayer {
                                translationX = if (isMe) -swipeOffset.value else swipeOffset.value
                            }
                            .pointerInput(isMe, message.deleted) {
                                if (message.deleted) return@pointerInput
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        val target =
                                            (swipeOffset.value + if (isMe) -dragAmount else dragAmount)
                                                .coerceIn(0f, maxSwipePx)
                                        scope.launch { swipeOffset.snapTo(target) }
                                    },
                                    onDragEnd = {
                                        scope.launch {
                                            if (swipeOffset.value > replyThresholdPx) {
                                                onReply(message)
                                            }
                                            swipeOffset.animateTo(
                                                0f,
                                                spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
                                            )
                                        }
                                    },
                                )
                            }
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                enabled = !message.deleted,
                                onLongClick = {
                                    if (!message.deleted) {
                                        onLongPress(message, bubbleBounds.value)
                                    }
                                },
                            ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        message.replyTo?.let { reply ->
                            RepliedMessagePreview(reply, replyBgColor, textColor)
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        if (message.deleted) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.delete),
                                    contentDescription = null,
                                    tint = textColor.copy(alpha = 0.45f),
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = stringResource(R.string.listen_together_chat_message_deleted),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    color = textColor.copy(alpha = 0.5f),
                                )
                            }
                        } else {
                            message.sharedTrack?.let { track ->
                                SharedTrackCard(
                                    track = track,
                                    bubbleColor = bubbleColor,
                                    onPlay = { onPlayTrack(track) },
                                )
                                if (message.message.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }

                            if (message.message.isNotBlank()) {
                                Text(
                                    text = formatMessageWithLinks(message.message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor,
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .align(Alignment.End)
                                    .padding(top = 2.dp),
                        ) {
                            if (message.pinned) {
                                Icon(
                                    painter = painterResource(R.drawable.push_pin),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    modifier = Modifier.size(11.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            if (message.edited) {
                                Text(
                                    text = stringResource(R.string.listen_together_chat_edited),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    color = textColor.copy(alpha = 0.55f),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = formatTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.6f),
                            )
                        }
                    }
                }

                // Jump-to-message highlight flash (from the pinned banner).
                if (highlightAlpha > 0f) {
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = highlightAlpha }
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                    )
                }
            }

            if (!message.deleted) {
                ReactionsRow(
                    message = message,
                    myUsername = myUsername,
                    onToggle = { emoji -> onToggleReaction(message, emoji) },
                    modifier =
                        Modifier.padding(
                            top = 3.dp,
                            start = if (isMe) 0.dp else 4.dp,
                            end = if (isMe) 4.dp else 0.dp,
                        ),
                )
            }
        }

        if (isMe) {
            Spacer(modifier = Modifier.width(8.dp))
            ChatAvatar(
                userId = message.userId,
                fallbackName = message.username,
                size = 28.dp,
                modifier = Modifier.align(Alignment.Top),
            )
        }
    }
}

/**
 * Instagram-style shared-song card inside a chat bubble: thumbnail, title,
 * artist and duration on a rounded tile; tapping it starts the song in the room
 * (host applies it directly, guests suggest it).
 */
@Composable
internal fun SharedTrackCard(
    track: TrackInfo,
    bubbleColor: Color,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(12.dp),
        color = bubbleColor.copy(alpha = 0.65f),
        border =
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            AsyncImage(
                model = track.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatTrackDuration(track.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.solar_play_linear),
                contentDescription = stringResource(R.string.listen_together_chat_play_song),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** mm:ss for a track duration in milliseconds (blank when unknown). */
internal fun formatTrackDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes >= 60L) {
        val hours = minutes / 60L
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes % 60L, seconds)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}

/**
 * Marker drawn where the restored (persisted) history ends and the live
 * conversation begins, so it is always clear which messages are older.
 */
@Composable
internal fun OlderMessagesDivider(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        )
        Icon(
            painter = painterResource(R.drawable.history),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.listen_together_chat_older_messages),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        )
    }
}

@Composable
internal fun RepliedMessagePreview(
    reply: RepliedMessage,
    replyBgColor: Color,
    textColor: Color,
) {
    Surface(
        color = replyBgColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(MaterialTheme.colorScheme.primary)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = reply.username,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = reply.message,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Banner bar over the chat list showing the pinned messages as a stacked
 * carousel — ONE constant-height row no matter how many messages are pinned.
 * The latest pin always displays first; tapping the bar steps to the pin
 * before it (wrapping around to the latest at the end) and jumps the chat to
 * that message, while swiping horizontally browses older/newer pins without
 * scrolling the list. The trailing button unpins the message on display.
 */
@Composable
internal fun PinnedMessagesStack(
    messages: List<ChatMessagePayload>,
    onUnpin: (ChatMessagePayload) -> Unit,
    onJumpTo: (ChatMessagePayload) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 48.dp.toPx() }
    val maxSwipePx = with(density) { 96.dp.toPx() }
    val dragOffset = remember { Animatable(0f) }

    // Latest pin first: recency of the pin itself, falling back to the message
    // timestamp for history pinned before the stamp existed.
    val ordered = remember(messages) {
        messages.sortedByDescending { if (it.pinnedAt > 0) it.pinnedAt else it.timestamp }
    }

    var displayedKey by remember { mutableStateOf<String?>(null) }
    var previousKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var slideDirection by remember { mutableIntStateOf(1) }

    // Follow the pinned set: a freshly pinned message takes over the bar (the
    // "latest pin displays first" rule), the shown pin is kept while it stays
    // pinned, and a shown pin that vanishes falls back to the latest.
    LaunchedEffect(ordered) {
        val keys = ordered.map { pinnedKeyOf(it) }
        val added = keys.toSet() - previousKeys
        when {
            displayedKey == null -> displayedKey = keys.firstOrNull()
            added.isNotEmpty() -> displayedKey = keys.firstOrNull()
            displayedKey !in keys -> displayedKey = keys.firstOrNull()
        }
        previousKeys = keys.toSet()
    }

    val index = ordered.indexOfFirst { pinnedKeyOf(it) == displayedKey }.takeIf { it >= 0 } ?: 0
    val displayed = ordered.getOrNull(index) ?: ordered.first()

    fun showOlder() {
        if (ordered.size < 2) return
        slideDirection = 1
        displayedKey = pinnedKeyOf(ordered[(index + 1) % ordered.size])
    }

    fun showNewer() {
        if (ordered.size < 2) return
        slideDirection = -1
        displayedKey = pinnedKeyOf(ordered[(index - 1 + ordered.size) % ordered.size])
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    // Keyed on the displayed pin as well: browsing via tap changes
                    // the shown message without changing the pinned set, and a
                    // stale closure would then swipe-navigate from an old index.
                    .pointerInput(ordered, displayedKey) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    dragOffset.snapTo(
                                        (dragOffset.value + dragAmount).coerceIn(-maxSwipePx, maxSwipePx),
                                    )
                                }
                            },
                            onDragEnd = {
                                scope.launch {
                                    if (dragOffset.value <= -swipeThresholdPx) {
                                        showOlder()
                                    } else if (dragOffset.value >= swipeThresholdPx) {
                                        showNewer()
                                    }
                                    dragOffset.snapTo(0f)
                                }
                            },
                        )
                    }
                    .clickable {
                        if (ordered.size > 1) {
                            // Tapping walks back through the pin history — and
                            // lands the chat on the message it lands on.
                            slideDirection = 1
                            val next = ordered[(index + 1) % ordered.size]
                            displayedKey = pinnedKeyOf(next)
                            onJumpTo(next)
                        } else {
                            onJumpTo(displayed)
                        }
                    }
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.push_pin),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))

            if (ordered.size > 1) {
                Text(
                    text = stringResource(
                        R.string.listen_together_chat_pinned_position,
                        index + 1,
                        ordered.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // The carousel slot: exactly one preview at a time, sliding in the
            // direction of travel and dragged along with the finger mid-gesture.
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = displayedKey,
                    transitionSpec = {
                        if (slideDirection >= 0) {
                            (slideInHorizontally(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { -it / 2 } + fadeOut(tween(160)))
                        } else {
                            (slideInHorizontally(tween(220)) { -it / 2 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { it / 2 } + fadeOut(tween(160)))
                        }
                    },
                    label = "pinned-carousel",
                    modifier =
                        Modifier.graphicsLayer {
                            translationX = dragOffset.value
                        },
                ) { key ->
                    val pinned = ordered.firstOrNull { pinnedKeyOf(it) == key } ?: displayed
                    Column {
                        Text(
                            text = pinned.username,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        val preview =
                            when {
                                pinned.deleted -> stringResource(R.string.listen_together_chat_message_deleted)
                                pinned.sharedTrack != null -> "♪ ${pinned.sharedTrack.title} • ${pinned.sharedTrack.artist}"
                                else -> pinned.message
                            }
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            IconButton(onClick = { onUnpin(displayed) }) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = stringResource(R.string.listen_together_chat_unpin),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Stable identity of a chat message across list updates. */
private fun pinnedKeyOf(message: ChatMessagePayload): String = "${message.userId}:${message.timestamp}"

@Composable
internal fun formatMessageWithLinks(text: String): AnnotatedString {
    val context = LocalContext.current
    // Any http(s) link is tappable. YouTube Music song links open in-app; every
    // other URL opens directly through the system's default handler.
    val urlRegex = Regex("(https?://[^\\s<>\\\"]+)")
    val matches = urlRegex.findAll(text)

    if (matches.none()) return AnnotatedString(text)

    return buildAnnotatedString {
        var lastIdx = 0
        for (match in matches) {
            append(text.substring(lastIdx, match.range.first))

            val url = match.value
            val linkAnnotation = LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Bold,
                    )
                ),
                linkInteractionListener = {
                    try {
                        if (url.contains("music.youtube.com")) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                `package` = context.packageName
                            }
                            context.startActivity(intent)
                        } else {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    } catch (e: Exception) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e2: Exception) {
                            Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            withLink(linkAnnotation) {
                append(url)
            }

            lastIdx = match.range.last + 1
        }
        if (lastIdx < text.length) {
            append(text.substring(lastIdx))
        }
    }
}

internal fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
