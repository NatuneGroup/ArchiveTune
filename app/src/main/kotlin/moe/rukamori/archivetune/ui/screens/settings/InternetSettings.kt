/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.ui.component.*
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.AppProxy
import moe.rukamori.archivetune.utils.ProxyUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.layout.asPaddingValues

@Composable
fun InternetWarningBox(modifier: Modifier = Modifier) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(SettingsDimensions.BannerCardCornerRadius),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(SettingsDimensions.BannerIconSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(SettingsDimensions.BannerIconInnerSize),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.internet_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.internet_warning_doh),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
                Text(
                    text = stringResource(R.string.internet_warning_proxy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InternetSettings(navController: NavController, scrollTo: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = moe.rukamori.archivetune.utils.rememberPreferenceSnapshot()
    val restartPrompt = LocalRestartPrompt.current

    val (dnsOverHttpsEnabled, onDnsOverHttpsEnabledChange) = prefs.state(key = EnableDnsOverHttpsKey, defaultValue = false)
    val (dnsProvider, onDnsProviderChange) = prefs.state(key = DnsOverHttpsProviderKey, defaultValue = "Cloudflare")
    val (customDnsUrl, onCustomDnsUrlChange) = prefs.state(key = stringPreferencesKey("customDnsUrl"), defaultValue = "https://")
    val (proxyEnabled, onProxyEnabledChange) = prefs.state(key = ProxyEnabledKey, defaultValue = false)
    val (proxyType, onProxyTypeChange) = prefs.enumState(key = ProxyTypeKey, defaultValue = Proxy.Type.HTTP)
    val (proxyHost, onProxyHostChange) = prefs.state(key = ProxyHostKey, defaultValue = "")
    val (proxyPort, onProxyPortChange) = prefs.state(key = ProxyPortKey, defaultValue = 8080)
    val (proxyUsername, onProxyUsernameChange) = prefs.state(key = ProxyUsernameKey, defaultValue = "")
    val (proxyPassword, onProxyPasswordChange) = prefs.state(key = ProxyPasswordKey, defaultValue = "")
    val (streamBypassProxy, onStreamBypassProxyChange) = prefs.state(key = StreamBypassProxyKey, defaultValue = false)

    val (ipRotationEnabled, onIpRotationEnabledChange) = prefs.state(key = IpRotationEnabledKey, defaultValue = false)
    var loadingIpRotation by remember { mutableStateOf(false) }
    var refreshingIpRotation by remember { mutableStateOf(false) }
    val activeProxyCount by YouTube.ipRotationActiveCount.collectAsStateWithLifecycle()

    val (ytMusicRegion) =
        prefs.state(key = YouTubeMusicRegionKey, defaultValue = SYSTEM_DEFAULT)
    val ytRegionValues = remember { listOf(SYSTEM_DEFAULT) + CountryCodeToName.keys.toList() }

    var testingProxy by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val dnsProviders = remember { listOf("Cloudflare", "Google", "AdGuard", "Quad9", "Custom") }
    val proxyTypes = remember { listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS) }
    val ipRotationDescription =
        when {
            loadingIpRotation -> stringResource(R.string.ip_rotation_loading)
            refreshingIpRotation -> stringResource(R.string.ip_rotation_refreshing)
            ipRotationEnabled -> stringResource(R.string.ip_rotation_active_proxies, activeProxyCount)
            else -> stringResource(R.string.ip_rotation_desc)
        }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.internet)) },
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
                // Chained before verticalScroll so it measures the viewport, not the scrolling content.
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            InternetWarningBox()

            PreferenceGroup(
                modifier = positions.modifierFor("yt_music_region"),
                title = stringResource(R.string.youtube_music_region),
            ) {
                item {
                    ListPreference(
                        title = { Text(stringResource(R.string.youtube_music_region)) },
                        description = stringResource(R.string.youtube_music_region_desc),
                        icon = { Icon(painterResource(R.drawable.location_on), null) },
                        selectedValue = ytMusicRegion,
                        values = ytRegionValues,
                        valueText = { code ->
                            if (code == SYSTEM_DEFAULT) {
                                stringResource(R.string.system_default)
                            } else {
                                CountryCodeToName.getOrElse(code) { code }
                            }
                        },
                        onValueSelected = { newValue ->
                            if (newValue != ytMusicRegion) {
                                restartPrompt.edit(prefs, onCommitted = {
                                    val deviceLocale = Locale.getDefault()
                                    val resolvedGl = newValue.takeIf { it != SYSTEM_DEFAULT }
                                        ?: deviceLocale.country.takeIf { it in CountryCodeToName }
                                        ?: "US"
                                    YouTube.regionSpooferActive = newValue != SYSTEM_DEFAULT
                                    YouTube.visitorData = null
                                    YouTube.locale = YouTube.locale.copy(gl = resolvedGl)
                                }) {
                                    this[YouTubeMusicRegionKey] = newValue
                                    // Keep login/dataSyncId; only the region-pinned visitor is invalid.
                                    remove(VisitorDataKey)
                                }
                            }
                        },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("enable_tor"),
                title = stringResource(R.string.dns_over_https),
            ) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("dns_over_https"),
                        title = { Text(stringResource(R.string.dns_over_https)) },
                        description = stringResource(R.string.dns_over_https_desc),
                        icon = { Icon(painterResource(R.drawable.security), null) },
                        checked = dnsOverHttpsEnabled,
                        onCheckedChange = onDnsOverHttpsEnabledChange,
                    )
                }

                item(visible = dnsOverHttpsEnabled) {
                    ListPreference(
                        modifier = positions.modifierFor("dns_provider"),
                        title = { Text(stringResource(R.string.dns_provider)) },
                        icon = { Icon(painterResource(R.drawable.website), null) },
                        selectedValue = dnsProvider,
                        values = dnsProviders,
                        valueText = { it },
                        onValueSelected = onDnsProviderChange,
                    )
                }

                item(visible = dnsOverHttpsEnabled && dnsProvider == "Custom") {
                    EditTextPreference(
                        modifier = positions.modifierFor("dns_custom_url"),
                        title = { Text(stringResource(R.string.dns_custom_url)) },
                        value = customDnsUrl,
                        onValueChange = onCustomDnsUrlChange,
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("proxy_settings"),
                title = stringResource(R.string.proxy),
            ) {
                item {
                    // App.kt applies every proxy preference as it is written, so these handlers only store.
                    SwitchPreference(
                        title = { Text(stringResource(R.string.enable_proxy)) },
                        description = stringResource(R.string.proxy_enable_desc),
                        icon = { Icon(painterResource(R.drawable.wifi_proxy), null) },
                        checked = proxyEnabled,
                        onCheckedChange = onProxyEnabledChange,
                    )
                }

                item(visible = proxyEnabled) {
                    ListPreference(
                        modifier = positions.modifierFor("proxy_type"),
                        title = { Text(stringResource(R.string.proxy_type)) },
                        selectedValue = proxyType,
                        values = proxyTypes,
                        valueText = { it.name },
                        onValueSelected = onProxyTypeChange,
                    )
                }

                item(visible = proxyEnabled) {
                    EditTextPreference(
                        modifier = positions.modifierFor("proxy_host"),
                        title = { Text(stringResource(R.string.proxy_host)) },
                        value = proxyHost,
                        onValueChange = { onProxyHostChange(it.trim()) },
                        isInputValid = ProxyUtils::isValidHostInput,
                    )
                }

                item(visible = proxyEnabled) {
                    NumberEditTextPreference(
                        modifier = positions.modifierFor("proxy_port"),
                        title = { Text(stringResource(R.string.proxy_port)) },
                        value = proxyPort,
                        onValueChange = onProxyPortChange,
                        isInputValid = { it.toIntOrNull() in 1..65535 },
                    )
                }
            }

            if (proxyEnabled) {
                PreferenceGroup(title = stringResource(R.string.proxy_auth)) {
                    item {
                        EditTextPreference(
                            modifier = positions.modifierFor("proxy_username"),
                            title = { Text(stringResource(R.string.proxy_username)) },
                            value = proxyUsername,
                            onValueChange = onProxyUsernameChange,
                        )
                    }

                    item {
                        EditTextPreference(
                            modifier = positions.modifierFor("proxy_password"),
                            title = { Text(stringResource(R.string.proxy_password)) },
                            value = proxyPassword,
                            onValueChange = onProxyPasswordChange,
                        )
                    }

                    item {
                        SwitchPreference(
                            modifier = positions.modifierFor("stream_bypass_proxy"),
                            title = { Text(stringResource(R.string.stream_bypass_proxy)) },
                            description = stringResource(R.string.stream_bypass_proxy_desc),
                            icon = { Icon(painterResource(R.drawable.wifi_proxy), null) },
                            checked = streamBypassProxy,
                            onCheckedChange = onStreamBypassProxyChange,
                        )
                    }

                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("test_proxy"),
                            title = { Text(stringResource(R.string.test_proxy_connection)) },
                            icon = { Icon(painterResource(R.drawable.check), null) },
                            onClick = {
                                if (testingProxy) return@PreferenceEntry
                                scope.launch(Dispatchers.IO) {
                                    testingProxy = true
                                    try {
                                        testResult =
                                            testProxy(
                                                context = context,
                                                config =
                                                    ProxyUtils.configOrNull(
                                                        type = proxyType,
                                                        host = proxyHost,
                                                        port = proxyPort,
                                                        username = proxyUsername,
                                                        password = proxyPassword,
                                                        bypassStreams = false,
                                                    ),
                                            )
                                    } finally {
                                        testingProxy = false
                                    }
                                }
                            },
                        )
                    }
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("download_speed_limit"),
                title = stringResource(R.string.ip_rotation),
            ) {
                item {
                    IpRotationPreference(
                        modifier = positions.modifierFor("ip_rotation"),
                        title = { Text(stringResource(R.string.ip_rotation)) },
                        description = ipRotationDescription,
                        icon = { Icon(painterResource(R.drawable.wifi_proxy), null) },
                        checked = ipRotationEnabled,
                        isBusy = loadingIpRotation || refreshingIpRotation,
                        onCheckedChange = { enabled ->
                            onIpRotationEnabledChange(enabled)
                            if (enabled) {
                                scope.launch {
                                    loadingIpRotation = true
                                    try {
                                        YouTube.enableIpRotation()
                                        // enableIpRotation() succeeds even when every candidate
                                        // proxy failed validation, so the switch would read on with
                                        // the description stuck at "0 active proxies" and no
                                        // explanation. Revert and say what happened instead.
                                        if (YouTube.ipRotationActiveCount.value == 0) {
                                            YouTube.disableIpRotation()
                                            onIpRotationEnabledChange(false)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.ip_rotation_no_proxies),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    } catch (_: Exception) {
                                        onIpRotationEnabledChange(false)
                                    } finally {
                                        loadingIpRotation = false
                                    }
                                }
                            } else {
                                YouTube.disableIpRotation()
                            }
                        },
                        onRefresh = refreshIp@{
                            if (loadingIpRotation || refreshingIpRotation) return@refreshIp
                            scope.launch {
                                refreshingIpRotation = true
                                try {
                                    YouTube.refreshIpRotation()
                                } catch (_: Exception) {
                                } finally {
                                    refreshingIpRotation = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (testingProxy) {
        DefaultDialog(
            onDismiss = { },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.testing_proxy_connection))
        }
    }

    if (testResult != null) {
        ActionPromptDialog(
            title = stringResource(R.string.test_proxy_connection),
            onDismiss = { testResult = null },
            onConfirm = { testResult = null },
            content = {
                Text(testResult!!)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IpRotationPreference(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    description: String,
    icon: @Composable () -> Unit,
    checked: Boolean,
    isBusy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    PreferenceEntry(
        modifier = modifier,
        title = title,
        description = description,
        icon = icon,
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (checked) {
                    if (isBusy) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        FilledTonalIconButton(onClick = onRefresh) {
                            Icon(
                                painterResource(R.drawable.sync),
                                contentDescription = stringResource(R.string.ip_rotation_refresh),
                            )
                        }
                    }
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = !isBusy,
                    thumbContent = {
                        AnimatedContent(
                            targetState = checked,
                            transitionSpec = {
                                fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                            },
                            label = "ipRotationSwitchThumbIcon",
                        ) { isChecked ->
                            Icon(
                                painter =
                                    painterResource(
                                        id = if (isChecked) R.drawable.check else R.drawable.close,
                                    ),
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    },
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedIconColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                            uncheckedIconColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
            }
        },
        onClick =
            if (isBusy) {
                null
            } else {
                { onCheckedChange(!checked) }
            },
    )
}

/**
 * Fetches YouTube Music's 204 endpoint through [config] alone (never the process default, so a
 * stale or half-applied setting cannot make a broken proxy look fine), and turns the outcome into
 * something a user can act on. In mainland China that host is reachable only through a working
 * proxy, so a success here means the proxy really carries traffic.
 */
private fun testProxy(
    context: Context,
    config: AppProxy.Config?,
): String {
    if (config == null) {
        return context.getString(
            R.string.proxy_connection_failed,
            context.getString(R.string.proxy_connection_invalid_configuration),
        )
    }
    val endpoint = "${config.host}:${config.port}"
    val client =
        OkHttpClient
            .Builder()
            .proxy(config.proxy)
            .proxyAuthenticator(AppProxy.authenticatorFor { config })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    val request = Request.Builder().url(PROXY_TEST_URL).build()
    val startedAt = System.nanoTime()
    return try {
        client.newCall(request).execute().use { response ->
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            when {
                response.code == 407 -> context.getString(R.string.proxy_test_auth_failed)
                response.isSuccessful -> context.getString(R.string.proxy_test_success_latency, elapsedMs)
                else -> context.getString(R.string.proxy_test_http_status, response.code)
            }
        }
    } catch (e: Exception) {
        describeProxyFailure(context, config, endpoint, e)
    } finally {
        client.connectionPool.evictAll()
    }
}

private fun describeProxyFailure(
    context: Context,
    config: AppProxy.Config,
    endpoint: String,
    error: Exception,
): String {
    val message = error.message.orEmpty()
    return when {
        error is UnknownHostException -> context.getString(R.string.proxy_test_unknown_host, config.host)
        error is ConnectException -> context.getString(R.string.proxy_test_unreachable, endpoint)
        error is SocketTimeoutException || error is InterruptedIOException ->
            context.getString(R.string.proxy_test_timeout)
        message.contains("407") || message.contains("authenticat", ignoreCase = true) ->
            context.getString(R.string.proxy_test_auth_failed)
        // What a SOCKS handshake against an HTTP port, or a CONNECT against a SOCKS port, ends in.
        error is ProtocolException ||
            message.contains("malformed reply", ignoreCase = true) ||
            message.contains("bad version", ignoreCase = true) ||
            message.contains("unexpected end of stream", ignoreCase = true) ->
            context.getString(
                R.string.proxy_test_wrong_type,
                if (config.type == Proxy.Type.SOCKS) "SOCKS5" else "HTTP",
            )
        else ->
            context.getString(
                R.string.proxy_connection_failed,
                message.ifBlank { context.getString(R.string.error_unknown) },
            )
    }
}

private const val PROXY_TEST_URL = "https://music.youtube.com/generate_204"
