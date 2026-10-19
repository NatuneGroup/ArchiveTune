/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * One-on-one chat screen with a saved Telegram bot. The user pastes a song link, the app sends it
 * to the bot via [TdApi.SendMessage], then waits on [TelegramBotClient.messagesForChat] for audio
 * replies. Each reply is persisted as a Song + Format row so it can be played / downloaded /
 * added-to-playlist through the existing infrastructure.
 *
 * When the user adds a bot-fetched song to a Telegram-channel playlist (LPtg<chatId>) AND the
 * "Auto-forward to my channel" toggle is on, the original bot message is forwarded to that channel
 * via [TdApi.ForwardMessages] — matching the user's spec: "if I add it to my telegram playlist the
 * song should also get forwarded to my own channel automatically".
 *
 * "Streaming a lot of files": the collector returns every audio reply that arrives within the
 * timeout window, and the "Play all" button loads them all into the player queue at once.
 *
 * Quality picker: many music bots reply with an inline keyboard ("Choose quality: ALAC / AAC /
 * Cancel") instead of the audio file directly. The screen surfaces those buttons as a row of
 * chips. When the user taps one, the screen calls [TelegramBotClient.clickInlineButton] (which
 * fires a [TdApi.GetCallbackQueryAnswer]) and then re-enters the collector with
 * `afterMessageId = prompt.messageId` so the bot's resulting audio reply is captured.
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.TelegramBotForwardToChannelKey
import moe.rukamori.archivetune.constants.TelegramBotsKey
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.telegram.BotReply
import moe.rukamori.archivetune.telegram.TelegramBot
import moe.rukamori.archivetune.telegram.TelegramBotClient
import moe.rukamori.archivetune.telegram.TelegramBotCodec
import moe.rukamori.archivetune.telegram.TelegramBotCommand
import moe.rukamori.archivetune.telegram.TelegramBotPrompt
import moe.rukamori.archivetune.telegram.TelegramBotPromptButton
import moe.rukamori.archivetune.telegram.TelegramTrack
import moe.rukamori.archivetune.telegram.telegramArtworkModel
import moe.rukamori.archivetune.telegram.toFormatEntity
import moe.rukamori.archivetune.telegram.toMediaMetadata
import moe.rukamori.archivetune.ui.component.FrostedTopAppBar
import moe.rukamori.archivetune.ui.menu.AddToPlaylistDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.LocalDatabase
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TelegramBotChatScreen(
    botId: String,
    navController: NavController,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val downloadUtil = LocalDownloadUtil.current
    val coroutineScope = rememberCoroutineScope()
    val (rawBots, onBotsChange) = rememberPreference(TelegramBotsKey, "")
    val (forwardToChannel) = rememberPreference(TelegramBotForwardToChannelKey, true)

    val bot: TelegramBot? = remember(rawBots) {
        TelegramBotCodec.decode(rawBots).find { it.id == botId }
    }

    var songLink by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var noReply by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<TelegramTrack>() }
    // Latest inline-keyboard prompt from the bot (e.g. "Choose quality: ALAC / AAC / Cancel").
    // Replaced when the bot sends a newer prompt. Hidden once a track arrives from the chosen
    // option so the result list takes the focus.
    var pendingPrompt by remember { mutableStateOf<TelegramBotPrompt?>(null) }
    // Track the highest message id we've ever seen in this chat so the next collector cycle
    // (e.g. after the user picks a quality) doesn't re-process old messages.
    var highestSeenMessageId by remember { mutableLongStateOf(0L) }
    // Which prompt button the user just tapped (for showing a spinner on that chip while the
    // bot processes the choice).
    var pendingChoiceText by remember { mutableStateOf<String?>(null) }

    // The track the add-to-playlist dialog is open for; null keeps the dialog hidden.
    var addToPlaylistTrack by remember { mutableStateOf<TelegramTrack?>(null) }

    // Bot command picker state. Some music bots require slash commands (e.g. `/search <query>`,
    // `/download <link>`) to search and download songs — pasting a bare URL doesn't work. We
    // fetch the bot's advertised commands via TdApi.GetCommands and show them in a "/"-button
    // dropdown so the user can discover and insert them. Common fallback commands are always
    // shown (even if the bot hasn't registered any) because many bots accept standard commands
    // without advertising them.
    var botCommands by remember { mutableStateOf<List<TelegramBotCommand>>(emptyList()) }
    var showCommandMenu by remember { mutableStateOf(false) }
    var commandsFetchedForChatId by remember { mutableLongStateOf(0L) }

    // Fetch the bot's advertised commands once the chat id is known. The fetch is best-effort —
    // if it fails (e.g. bot hasn't registered any commands), the common fallbacks still appear.
    LaunchedEffect(bot?.chatId) {
        val chatId = bot?.chatId ?: 0L
        if (chatId != 0L && chatId != commandsFetchedForChatId) {
            commandsFetchedForChatId = chatId
            botCommands = TelegramBotClient.fetchBotCommands(chatId)
        }
    }

    if (bot == null) {
        // Bot not found — the user must have removed it from another device. Bounce back.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { navController.navigateUp() }) {
                Text("Bot not found — tap to go back")
            }
        }
        return
    }

    fun persistBot(updated: TelegramBot) {
        val list = TelegramBotCodec.decode(rawBots).map { if (it.id == updated.id) updated else it }
        onBotsChange(TelegramBotCodec.encode(list))
    }

    suspend fun ensureBotChatId(): TelegramBot {
        if (bot.chatId != 0L) return bot
        val resolved = TelegramBotClient.resolveBot(bot.username) ?: return bot
        val title = runCatching {
            val type = resolved.type as org.drinkless.tdlib.TdApi.ChatTypePrivate
            moe.rukamori.archivetune.telegram.TelegramClient.send(
                org.drinkless.tdlib.TdApi.GetUser(type.userId),
            ).firstName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: bot.title
        val updated = bot.copy(chatId = resolved.id, title = title.ifBlank { bot.title })
        persistBot(updated)
        return updated
    }

    /**
     * Persists each track as a song + format row (so playback / download / playlist-add work
     * through the existing code paths) and surfaces them in the on-screen results list.
     *
     * Defined BEFORE collectAndApply because Kotlin local functions can only reference functions
     * declared earlier in the same scope — collectAndApply calls this from inside its body.
     */
    suspend fun persistTracks(tracks: List<TelegramTrack>, sourceTitle: String) {
        database.withTransaction {
            tracks.forEach { track ->
                insert(track.toMediaMetadata(sourceTitle))
                upsert(track.toFormatEntity(getFormatsByIds(listOf(track.mediaId)).firstOrNull()))
            }
        }
        results.clear()
        results.addAll(tracks)
    }

    /**
     * Collects every reply (tracks + inline prompts) that arrives on [chatId] within the timeout
     * window, then returns the result so the caller can drive UI state from a coroutine scope.
     */
    suspend fun collectAndApply(
        chatId: Long,
        afterMessageId: Long,
        sourceTitle: String,
    ) {
        val replies = TelegramBotClient.collectBotReplies(
            chatId = chatId,
            afterMessageId = afterMessageId,
        )
        // Track the highest message id we've seen so the next collector cycle starts after it.
        replies.maxOfOrNull { reply ->
            when (reply) {
                is BotReply.Track -> reply.track.messageId
                is BotReply.Prompt -> reply.prompt.messageId
            }
        }?.let { if (it > highestSeenMessageId) highestSeenMessageId = it }

        val newTracks = replies.filterIsInstance<BotReply.Track>().map { it.track }
        val latestPrompt = replies.filterIsInstance<BotReply.Prompt>().lastOrNull()?.prompt

        if (newTracks.isNotEmpty()) {
            // We got audio — clear any pending prompt (the user's choice has been fulfilled) and
            // surface the tracks in the results list.
            noReply = false
            pendingPrompt = null
            persistTracks(newTracks, sourceTitle)
        } else if (latestPrompt != null) {
            // No audio yet, but the bot sent a new prompt — show it.
            pendingPrompt = latestPrompt
        } else {
            // Nothing arrived (or only text messages we ignore). If we don't already have a prompt
            // displayed, mark the request as having no reply so the UI shows the empty state.
            if (pendingPrompt == null) noReply = true
        }
    }

    fun send() {
        val link = songLink.trim()
        if (link.isBlank() || sending) return
        sending = true
        noReply = false
        results.clear()
        pendingPrompt = null
        pendingChoiceText = null
        coroutineScope.launch {
            val activeBot = ensureBotChatId()
            if (activeBot.chatId == 0L) {
                sending = false
                Toast.makeText(context, R.string.telegram_bots_resolve_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sent = runCatching {
                TelegramBotClient.sendTextMessage(activeBot.chatId, link)
            }.getOrElse {
                sending = false
                Toast.makeText(
                    context,
                    context.getString(R.string.telegram_error, it.message ?: "?"),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            highestSeenMessageId = sent.id
            collectAndApply(
                chatId = activeBot.chatId,
                afterMessageId = sent.id,
                sourceTitle = activeBot.title,
            )
            sending = false
        }
    }

    /**
     * User tapped a button on the bot's inline keyboard — fire the callback and collect the
     * resulting audio reply.
     */
    fun choosePromptOption(button: TelegramBotPromptButton) {
        val prompt = pendingPrompt ?: return
        // URL buttons (e.g. "HQ Artwork") open an external link — don't fire a callback.
        if (button.callbackData == null) {
            button.url?.let { url ->
                runCatching {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            return
        }
        if (sending) return
        sending = true
        pendingChoiceText = button.text
        coroutineScope.launch {
            // Cancel buttons: just clear the prompt and bail out — the bot's cancel handler may
            // or may not send a follow-up message, but we don't want to keep the spinner spinning.
            if (prompt.isCancelButton(button)) {
                pendingPrompt = null
                pendingChoiceText = null
                sending = false
                return@launch
            }
            TelegramBotClient.clickInlineButton(
                chatId = prompt.chatId,
                messageId = prompt.messageId,
                callbackData = button.callbackData,
            )
            // Collect everything that arrives AFTER the prompt. The bot will typically send the
            // audio file (or a download progress message followed by the audio file).
            collectAndApply(
                chatId = prompt.chatId,
                afterMessageId = prompt.messageId,
                sourceTitle = bot.title,
            )
            pendingChoiceText = null
            sending = false
        }
    }

    fun playTrack(track: TelegramTrack) {
        if (playerConnection == null) return
        val mediaItem = track.toMediaMetadata(bot.title).toMediaItem()
        playerConnection.playQueue(ListQueue(title = bot.title, items = listOf(mediaItem)))
    }

    fun playAll() {
        if (playerConnection == null || results.isEmpty()) return
        val items = results.map { it.toMediaMetadata(bot.title).toMediaItem() }
        playerConnection.playQueue(ListQueue(title = bot.title, items = items))
    }

    fun downloadTrack(track: TelegramTrack) {
        // DownloadRequest uses the song id as both the media id and the URI. The same
        // SchemeRoutingDataSource that powers streaming also handles downloads for telegram:// URIs.
        runCatching { downloadUtil.downloadCache.removeResource(track.mediaId) }
        val request = DownloadRequest.Builder(track.mediaId, track.mediaId.toUri())
            .setCustomCacheKey(track.mediaId)
            .setData(track.displayTitle.toByteArray())
            .build()
        runCatching {
            DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
        }.onFailure {
            Toast.makeText(context, R.string.telegram_error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    fun addToPlaylist(track: TelegramTrack) {
        addToPlaylistTrack = track
    }

    // After AddToPlaylistDialog closes, check whether the song was added to any Telegram-channel
    // playlist (LPtg…) and forward the original bot message to those channels.
    suspend fun maybeForwardToTelegramChannels(track: TelegramTrack) {
        if (!forwardToChannel) return
        // playlistDuplicates returns the song ids present in the playlist, so a non-empty result
        // means the song was added. Snapshot the playlist list once, then probe each Telegram-
        // channel playlist (LPtg<chatId>) for the just-added song.
        val playlists = database.playlistsByCreateDateAsc().first()
        val tgPlaylists = playlists.filter { it.id.startsWith("LPtg") }
        for (playlist in tgPlaylists) {
            val inPlaylist = database.withTransaction {
                playlistDuplicates(playlist.id, listOf(track.mediaId)).isNotEmpty()
            }
            if (!inPlaylist) continue
            val chatId = playlist.id.removePrefix("LPtg").toLongOrNull() ?: continue
            runCatching {
                TelegramBotClient.forwardMessage(
                    toChatId = chatId,
                    fromChatId = track.chatId,
                    messageId = track.messageId,
                )
            }.onSuccess {
                Toast.makeText(context, R.string.telegram_bot_forwarded, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(
                    context,
                    context.getString(R.string.telegram_bot_forward_failed, e.message ?: "?"),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    addToPlaylistTrack?.let { track ->
        AddToPlaylistDialog(
            isVisible = true,
            onGetSong = { listOf(track.mediaId) },
            onDismiss = { addToPlaylistTrack = null },
            onAddComplete = { _, _ ->
                // Auto-forward to Telegram-channel playlists if enabled. The dialog runs the add
                // inside its own transaction; we re-snapshot on completion.
                if (forwardToChannel) {
                    coroutineScope.launch { maybeForwardToTelegramChannels(track) }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            FrostedTopAppBar(
                title = { Text(stringResource(R.string.telegram_bot_chat_title, bot.username)) },
                onBack = navController::navigateUp,
                onBackLongClick = navController::backToMain,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = songLink,
                    onValueChange = { songLink = it },
                    placeholder = { Text(stringResource(R.string.telegram_bot_chat_hint)) },
                    singleLine = true,
                    enabled = !sending,
                    leadingIcon = {
                        Box {
                            IconButton(
                                onClick = { showCommandMenu = true },
                                enabled = !sending,
                            ) {
                                Text(
                                    text = "/",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            DropdownMenu(
                                expanded = showCommandMenu,
                                onDismissRequest = { showCommandMenu = false },
                            ) {
                                // Bot's advertised commands (from @BotFather registration) — shown
                                // first so the user sees the bot's own command set at a glance.
                                if (botCommands.isNotEmpty()) {
                                    botCommands.forEach { cmd ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = cmd.withSlash,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                    if (cmd.description.isNotBlank()) {
                                                        Text(
                                                            text = cmd.description,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                songLink = "${cmd.withSlash} "
                                                showCommandMenu = false
                                            },
                                        )
                                    }
                                    // Separator between advertised and fallback commands.
                                    androidx.compose.material3.HorizontalDivider()
                                }
                                // Common fallback commands that most music bots accept even
                                // without @BotFather registration. These cover the typical
                                // search/download/help flows the user needs.
                                CommonBotCommands.forEach { cmd ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = cmd.withSlash,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                if (cmd.description.isNotBlank()) {
                                                    Text(
                                                        text = cmd.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            songLink = "${cmd.withSlash} "
                                            showCommandMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = ::send, enabled = !sending && songLink.isNotBlank()) {
                            if (sending && pendingChoiceText == null) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(painterResource(R.drawable.solar_send_square_linear), contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (sending && pendingPrompt == null && results.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.telegram_bot_waiting))
                    }
                }
            }

            // Quality picker / inline keyboard prompt. Shown above the results list so the user
            // picks the format before the audio arrives. Hidden when results.isEmpty() is false
            // (the choice has been fulfilled) or when sending just started with no prompt yet.
            pendingPrompt?.let { prompt ->
                PromptCard(
                    prompt = prompt,
                    pendingChoiceText = pendingChoiceText,
                    onChoose = ::choosePromptOption,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (noReply) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.telegram_bot_no_reply),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (results.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.telegram_bot_results, results.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (results.size > 1) {
                        TextButton(onClick = ::playAll) {
                            Icon(painterResource(R.drawable.solar_play_linear), contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.telegram_bot_play_all))
                        }
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(results, key = TelegramTrack::mediaId) { track ->
                        BotResultRow(
                            track = track,
                            onStream = { playTrack(track) },
                            onDownload = { downloadTrack(track) },
                            onAddToPlaylist = { addToPlaylist(track) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders an inline-keyboard prompt from the bot as a card with a text body (if any) and the
 * buttons laid out in [FlowRow] chips. Tracks the user's pending choice so the tapped chip
 * shows a spinner while the bot processes the callback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptCard(
    prompt: TelegramBotPrompt,
    pendingChoiceText: String?,
    onChoose: (TelegramBotPromptButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
    ) {
        if (prompt.text.isNotBlank()) {
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        // Render each row of the original inline keyboard as its own FlowRow so multi-row
        // keyboards (e.g. "[ALAC][AAC] / [LRC] / [Cancel]") keep their grouping.
        prompt.rows.forEach { row ->
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { button ->
                    val isPending = pendingChoiceText == button.text
                    FilterChip(
                        selected = false,
                        onClick = { onChoose(button) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(button.text)
                            }
                        },
                        enabled = pendingChoiceText == null,
                    )
                }
            }
        }
    }
}

@Composable
private fun BotResultRow(
    track: TelegramTrack,
    onStream: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    // Resolve the thumbnail URL the same way toMediaMetadata() does — this lets the bot-result
    // row show artwork the moment a track arrives, instead of a static play-arrow placeholder.
    // The tgart:// URI is handled by TelegramThumbnailFetcher (catalogue cover → embedded cover
    // → minithumbnail fallback chain).
    val thumbModel = remember(track) {
        val metadata = track.lookupMetadata
        telegramArtworkModel(track.thumbnailFileId, metadata.title, metadata.artist)
            ?: moe.rukamori.archivetune.telegram.TelegramClient.cacheArtwork(
                uniqueKey = track.fileUniqueId.ifEmpty { "${track.chatId}-${track.messageId}" },
                data = track.albumCoverMinithumbnail,
            )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // Solar music-note placeholder shows behind the AsyncImage while the artwork loads
            // (or if it fails to load — e.g. bot track has no embedded cover and no catalogue
            // match). When the artwork loads it covers the placeholder completely.
            Icon(
                painter = painterResource(R.drawable.solar_music_note_2_linear),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val performer = track.performer ?: track.lookupMetadata.artist
            if (!performer.isNullOrBlank()) {
                Text(
                    text = performer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onStream) {
            Icon(
                painter = painterResource(R.drawable.solar_play_linear),
                contentDescription = stringResource(R.string.telegram_bot_stream),
            )
        }
        IconButton(onClick = onDownload) {
            Icon(
                painter = painterResource(R.drawable.solar_download_linear),
                contentDescription = stringResource(R.string.telegram_bot_download),
            )
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                painter = painterResource(R.drawable.solar_playlist_linear),
                contentDescription = stringResource(R.string.telegram_bot_add_to_playlist),
            )
        }
    }
}

/**
 * Common slash-commands that most Telegram music bots accept even without @BotFather registration.
 * Shown in the "/" command picker as fallbacks after the bot's own advertised commands. The
 * descriptions are generic hints — the bot may interpret them slightly differently.
 *
 * `command` does NOT include the leading `/` — [TelegramBotCommand.withSlash] adds it.
 */
private val CommonBotCommands = listOf(
    TelegramBotCommand("start", "Initialize / restart the bot"),
    TelegramBotCommand("help", "Show the bot's help / usage guide"),
    TelegramBotCommand("search", "Search for a song by name or artist"),
    TelegramBotCommand("download", "Download a song from a link or search query"),
    TelegramBotCommand("song", "Search for a song by name"),
    TelegramBotCommand("music", "Search for music by query"),
    TelegramBotCommand("lyrics", "Fetch lyrics for a song"),
    TelegramBotCommand("flac", "Request FLAC (lossless) quality"),
    TelegramBotCommand("alac", "Request ALAC (Apple Lossless) quality"),
    TelegramBotCommand("mp3", "Request MP3 (lossy) quality"),
    TelegramBotCommand("cancel", "Cancel the current operation"),
)
