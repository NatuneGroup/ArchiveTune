/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.auth.YouTubeOAuthRepository
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.InfoLabel
import moe.rukamori.archivetune.ui.utils.appBarScrollBehavior
import moe.rukamori.archivetune.ui.utils.backToMain

const val YOUTUBE_OAUTH_ROUTE = "settings/youtube/oauth"

private val OAuthContentMaxWidth = 840.dp

private sealed interface DeviceCodeUiState {
    data object Requesting : DeviceCodeUiState

    data class Waiting(val code: YouTubeOAuthRepository.DeviceCode) : DeviceCodeUiState

    /** [reason] is the raw OAuth error code; it is mapped to a message at render time. */
    data class Failed(val reason: String) : DeviceCodeUiState
}

/**
 * OAuth2 device-code sign-in: ArchiveTune asks Google for a short code, the user types it at
 * google.com/device on whatever device is convenient, and the poll here finishes the grant.
 *
 * This is an alternative to — not a replacement for — the WebView cookie login. The token
 * authenticates as the YouTube VR client, so it only ever signs `/player` requests; library,
 * playlists and browse still need the browser sign-in. [YouTubeOAuthRepository] documents why.
 */
@Composable
fun YouTubeOAuthLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollBehavior = appBarScrollBehavior()

    var state by remember { mutableStateOf<DeviceCodeUiState>(DeviceCodeUiState.Requesting) }
    // Bumping this restarts the request/poll effect. Retrying needs a brand new device code — the
    // old one is either expired or already refused, so re-polling it would fail identically.
    var attempt by remember { mutableIntStateOf(0) }

    val codeCopiedMessage = stringResource(R.string.yt_oauth_code_copied)
    val noBrowserMessage = stringResource(R.string.yt_oauth_no_browser)
    val signedInMessage = stringResource(R.string.yt_oauth_signed_in)

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    LaunchedEffect(attempt) {
        state = DeviceCodeUiState.Requesting
        val code = YouTubeOAuthRepository.requestDeviceCode()
        if (code == null) {
            state = DeviceCodeUiState.Failed("network")
            return@LaunchedEffect
        }
        state = DeviceCodeUiState.Waiting(code)
        when (val result = YouTubeOAuthRepository.pollForToken(context, code)) {
            is YouTubeOAuthRepository.PollResult.Success -> {
                toast(signedInMessage)
                navController.navigateUp()
            }
            is YouTubeOAuthRepository.PollResult.Failed -> state = DeviceCodeUiState.Failed(result.reason)
            // pollForToken only produces Pending inside its own loop, never as a return value.
            YouTubeOAuthRepository.PollResult.Pending -> Unit
        }
    }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.yt_oauth_sign_in),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = Color.Transparent,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal),
                    ),
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = OAuthContentMaxWidth)
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            PaddingValues(
                                start = 16.dp,
                                top = innerPadding.calculateTopPadding() + 8.dp,
                                end = 16.dp,
                                bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                            ),
                        ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (val current = state) {
                    DeviceCodeUiState.Requesting ->
                        BusyBlock(message = stringResource(R.string.yt_oauth_requesting))

                    is DeviceCodeUiState.Waiting -> {
                        Text(
                            text = stringResource(R.string.yt_oauth_step_open),
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        UserCodeCard(
                            userCode = current.code.userCode,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(current.code.userCode))
                                toast(codeCopiedMessage)
                            },
                        )

                        Button(
                            onClick = {
                                val opened =
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, current.code.verificationUrl.toUri())
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }.isSuccess
                                // No browser, or a profile that blocks the handoff: the code still
                                // works on another device, so say that instead of failing the flow.
                                if (!opened) toast(noBrowserMessage)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.link),
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.yt_oauth_open_page))
                        }

                        BusyBlock(message = stringResource(R.string.yt_oauth_waiting))
                        InfoLabel(text = stringResource(R.string.yt_oauth_scope_note))
                    }

                    is DeviceCodeUiState.Failed ->
                        FailureBlock(
                            message = deviceCodeFailureMessage(current.reason),
                            onRetry = { attempt++ },
                        )
                }
            }
        }
    }
}

/**
 * Maps the OAuth error code to something the user can act on. An unrecognised code is shown raw
 * rather than swallowed — that is exactly what a bug report needs.
 */
@Composable
private fun deviceCodeFailureMessage(reason: String): String =
    when (reason) {
        "network" -> stringResource(R.string.yt_oauth_failed_network)
        "expired" -> stringResource(R.string.yt_oauth_failed_expired)
        "access_denied" -> stringResource(R.string.yt_oauth_failed_denied)
        else -> stringResource(R.string.yt_oauth_failed_other, reason)
    }

@Composable
private fun UserCodeCard(
    userCode: String,
    onCopy: () -> Unit,
) {
    Card(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionContainer {
                Text(
                    text = userCode,
                    style =
                        MaterialTheme.typography.displaySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                        ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            OutlinedButton(
                onClick = onCopy,
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.copy),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.copy))
            }
        }
    }
}

@Composable
private fun BusyBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FailureBlock(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.error),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.retry))
        }
    }
}
