/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * QQ Music settings, reached from the Integration screen alongside the other sources.
 * The account card is not decoration: this source plays from the user's own QQ Music account and
 * nothing else, so a screen that did not say whether one is connected would leave every silent
 * fall-through unexplained. The card therefore carries the whole sign-in — the QR code, what the
 * scan is doing, and the way back out — and the source's own boundary is stated once, plainly,
 * rather than in a mandatory notice.
 */



package moe.rukamori.archivetune.ui.screens.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.audiosource.AudioSourceConfig
import moe.rukamori.archivetune.constants.AudioSourceOrderKey
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.QqAudioQuality
import moe.rukamori.archivetune.constants.QqMusicAudioQualityKey
import moe.rukamori.archivetune.constants.QqMusicEnabledKey
import moe.rukamori.archivetune.constants.QqMusicNicknameKey
import moe.rukamori.archivetune.constants.QqMusicUinKey
import moe.rukamori.archivetune.qqmusic.QqMusicSession
import moe.rukamori.archivetune.qqmusic.QqQrLogin
import moe.rukamori.archivetune.qqmusic.QqQrStatus
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

/** How often the scan is checked, and how long a code is offered before a fresh one replaces it. */
private const val QR_POLL_INTERVAL_MS = 2_000L
private const val QR_CODE_LIFETIME_MS = 120_000L

/** What the sign-in card is showing. */
private sealed interface QqLoginState {
    /** Asking for a code. */
    data object Requesting : QqLoginState

    /** A code is on screen and waiting to be scanned. */
    data class Ready(val image: ImageBitmap) : QqLoginState

    /** The code was scanned and the phone is being confirmed. */
    data object Scanned : QqLoginState

    /** The attempt ended; [message] says how, and a new code can be asked for. */
    data class Failed(val message: String) : QqLoginState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QqMusicSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val (enabled, onEnabledChangeRaw) = rememberPreference(QqMusicEnabledKey, false)
    val (quality, onQualityChange) =
        rememberEnumPreference(QqMusicAudioQualityKey, QqAudioQuality.Default)
    val (orderRaw, onOrderChange) = rememberPreference(AudioSourceOrderKey, "")
    val (uin, onUinChange) = rememberPreference(QqMusicUinKey, "")
    val (nickname, onNicknameChange) = rememberPreference(QqMusicNicknameKey, "")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Turning the source on has to write it into the order as well. QQ is deliberately not a member
    // of AudioSourceConfig.DEFAULT_ORDER, and the order picker can only reorder what it is given, so
    // without this the toggle would flip a preference the resolver never reads and playback would
    // stay on YouTube with no indication why.
    val onEnabledChange: (Boolean) -> Unit = { next ->
        onEnabledChangeRaw(next)
        if (next) {
            onOrderChange(AudioSourceConfig.withSourceAdded(orderRaw, AudioSourceType.QQ))
        }
    }

    val signedInAs = if (uin.isBlank()) null else nickname.ifBlank { uin }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.source_qq_music)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val topPadding = innerPadding.calculateTopPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()
        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            Modifier
                .padding(top = topPadding)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + 16.dp),
        ) {
            if (signedInAs != null) {
                QqSignedInCard(
                    modifier =
                        Modifier
                            .then(positions.modifierFor("qq_music_account"))
                            .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                            .padding(top = 12.dp, bottom = 4.dp),
                    account = signedInAs,
                    onSignOut = {
                        scope.launch {
                            withContext(Dispatchers.IO) { QqMusicSession.clear(context.dataStore) }
                            onUinChange("")
                            onNicknameChange("")
                        }
                    },
                )
            } else {
                QqLoginCard(
                    modifier =
                        Modifier
                            .then(positions.modifierFor("qq_music_account"))
                            .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                            .padding(top = 12.dp, bottom = 4.dp),
                    onSignedIn = { session ->
                        onUinChange(session.uin)
                        onNicknameChange(session.nickname.orEmpty())
                    },
                )
            }

            PreferenceGroup(title = stringResource(R.string.source_qq_music)) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("qq_music_enabled"),
                        title = { Text(stringResource(R.string.qq_music_enabled)) },
                        description = stringResource(R.string.qq_music_enabled_desc),
                        icon = { Icon(painterResource(R.drawable.ic_music), null) },
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }

                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("qq_music_quality"),
                        title = { Text(stringResource(R.string.qq_music_quality)) },
                        icon = { Icon(painterResource(R.drawable.equalizer), null) },
                        selectedValue = quality,
                        valueText = {
                            when (it) {
                                QqAudioQuality.LOSSLESS -> stringResource(R.string.qq_quality_lossless)
                                QqAudioQuality.HIGH -> stringResource(R.string.qq_quality_high)
                                QqAudioQuality.STANDARD -> stringResource(R.string.qq_quality_standard)
                            }
                        },
                        onValueSelected = onQualityChange,
                    )
                }
            }
        }
    }
}

/**
 * The signed-in card: who is connected, and the way out.
 */
@Composable
private fun QqSignedInCard(
    account: String,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    QqCard(
        modifier = modifier,
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = R.drawable.login,
        iconTint = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = stringResource(R.string.qq_music_signed_in, account),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.qq_music_signed_in_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(4.dp))
        PreferenceEntry(
            title = { Text(stringResource(R.string.qq_music_sign_out)) },
            icon = { Icon(painterResource(R.drawable.logout), null) },
            onClick = onSignOut,
        )
    }
}

/**
 * The sign-in card.
 *
 * It runs the whole login: ask for a code, show it, keep asking whether it has been scanned, and
 * refresh it when it goes stale. Only two outcomes stop it — a connection, which is written to
 * preferences by the caller, and a refusal or a failure, which is shown with a way to try again.
 */
@Composable
private fun QqLoginCard(
    onSignedIn: (QqMusicSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var attempt by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<QqLoginState>(QqLoginState.Requesting) }

    LaunchedEffect(attempt) {
        state = QqLoginState.Requesting
        val unavailable = context.getString(R.string.qq_music_login_unavailable)
        val timedOut = context.getString(R.string.qq_music_login_timed_out)
        while (true) {
            QqQrLogin.reset()
            val challenge = QqQrLogin.requestChallenge()
            if (challenge == null) {
                state = QqLoginState.Failed(unavailable)
                return@LaunchedEffect
            }
            val image =
                withContext(Dispatchers.Default) {
                    BitmapFactory
                        .decodeByteArray(challenge.image, 0, challenge.image.size)
                        ?.asImageBitmap()
                }
            if (image == null) {
                state = QqLoginState.Failed(unavailable)
                return@LaunchedEffect
            }
            state = QqLoginState.Ready(image)

            var stale = false
            val deadline = System.currentTimeMillis() + QR_CODE_LIFETIME_MS
            while (System.currentTimeMillis() < deadline) {
                delay(QR_POLL_INTERVAL_MS)
                val callback = QqQrLogin.poll(challenge) ?: continue
                when (callback.status) {
                    QqQrStatus.WAITING -> Unit

                    QqQrStatus.SCANNED -> state = QqLoginState.Scanned

                    // A stale code is not a failure: it is replaced and the user scans again.
                    QqQrStatus.EXPIRED -> {
                        stale = true
                    }

                    QqQrStatus.CONFIRMED -> {
                        val session = QqQrLogin.complete(callback)
                        if (session == null) {
                            state = QqLoginState.Failed(context.getString(R.string.qq_music_login_incomplete))
                            return@LaunchedEffect
                        }
                        withContext(Dispatchers.IO) { QqMusicSession.write(context.dataStore, session) }
                        onSignedIn(session)
                        return@LaunchedEffect
                    }

                    QqQrStatus.REFUSED -> {
                        state = QqLoginState.Failed(context.getString(R.string.qq_music_login_declined))
                        return@LaunchedEffect
                    }

                    QqQrStatus.FAILED -> {
                        state = QqLoginState.Failed(callback.message.ifBlank { unavailable })
                        return@LaunchedEffect
                    }
                }
                if (stale) break
            }
            if (!stale) {
                state = QqLoginState.Failed(timedOut)
                return@LaunchedEffect
            }
        }
    }

    val current = state
    QqCard(
        modifier = modifier,
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = R.drawable.login,
        iconTint = MaterialTheme.colorScheme.onSurface,
    ) {
        when (current) {
            QqLoginState.Requesting ->
                Text(
                    text = stringResource(R.string.qq_music_login_requesting),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

            is QqLoginState.Ready -> {
                Text(
                    text = stringResource(R.string.qq_music_login_scan),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.size(10.dp))
                Image(
                    bitmap = current.image,
                    contentDescription = stringResource(R.string.qq_music_login_scan),
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                )
            }

            QqLoginState.Scanned ->
                Text(
                    text = stringResource(R.string.qq_music_login_scanned),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

            is QqLoginState.Failed -> {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.size(4.dp))
                PreferenceEntry(
                    title = { Text(stringResource(R.string.qq_music_login_retry)) },
                    icon = { Icon(painterResource(R.drawable.arrow_back), null) },
                    onClick = { attempt++ },
                )
            }
        }
        Text(
            text = stringResource(R.string.qq_music_login_limits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The shared card shell: a titled panel with a leading icon and a column of content.
 */
@Composable
private fun QqCard(
    container: Color,
    icon: Int,
    iconTint: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SettingsDimensions.BannerCardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(SettingsDimensions.BannerIconSize)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(SettingsDimensions.BannerIconInnerSize),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content()
            }
        }
    }
}
