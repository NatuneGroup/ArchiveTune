/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Changelog screen. UI pattern ported from vivi-music
 * (https://github.com/vivizzz007/vivi-music) under GPL-3.0:
 *   - Version-selection chips (segmented ToggleButtons) at the top
 *   - Bullet-point rendering with clickable URL annotations
 *   - Warning section (parsed from the release body)
 *   - Pull-to-refresh
 *
 * Data layer (Updater.kt) is ArchiveTune's existing GitHub-releases-backed
 * implementation; only the rendering is vivi-music-style.
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.UpdateChannel
import moe.rukamori.archivetune.effectiveUpdateChannel
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.ReleaseInfo
import moe.rukamori.archivetune.utils.Updater
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    navController: NavController,
    channel: UpdateChannel = UpdateChannel.STABLE,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var releases by remember { mutableStateOf<List<ReleaseInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var currentTag by remember { mutableStateOf<String?>(null) }

    // Same build-based lock as every other update path: a nightly build always reads the nightly
    // feed, even if a stale link or argument names Stable.
    val channel = effectiveUpdateChannel(channel)

    suspend fun loadReleases(forceRefresh: Boolean) {
        val result =
            when (channel) {
                UpdateChannel.NIGHTLY -> Updater.getAllNightlyReleases(forceRefresh = forceRefresh)
                else -> Updater.getAllReleases(forceRefresh = forceRefresh)
            }
        result
            .onSuccess { r ->
                releases = r
                error = null
                if (currentTag == null && r.isNotEmpty()) currentTag = r.first().tagName
            }.onFailure { e ->
                if (releases.isEmpty()) {
                    error = e.message
                }
            }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        val cachedReleases =
            when (channel) {
                UpdateChannel.NIGHTLY -> Updater.getCachedNightlyReleases()
                else -> Updater.getCachedReleases()
            }
        if (cachedReleases.isNotEmpty()) {
            releases = cachedReleases
            currentTag = cachedReleases.first().tagName
            isLoading = false
        }
        loadReleases(forceRefresh = true)
    }

    val pullToRefreshState = rememberPullToRefreshState()
    val isRefreshing = isLoading
    val scaleFraction = {
        if (isRefreshing) 1f
        else LinearOutSlowInEasing.transform(pullToRefreshState.distanceFraction).coerceIn(0f, 1f)
    }

    val currentRelease = releases.firstOrNull { it.tagName == currentTag } ?: releases.firstOrNull()

    Scaffold(
        modifier =
            Modifier.pullToRefresh(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                onRefresh = {
                    isLoading = true
                    coroutineScope.launch { loadReleases(forceRefresh = true) }
                },
            ),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.changelog)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Version selection chips (vivi-music style: segmented ToggleButtons in a horizontal scroll).
                if (releases.isNotEmpty()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            releases.take(10).forEachIndexed { index, release ->
                                ToggleButton(
                                    checked = currentTag == release.tagName,
                                    onCheckedChange = {
                                        if (currentTag != release.tagName) currentTag = release.tagName
                                    },
                                    shapes =
                                        when {
                                            releases.size == 1 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                            index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                            index == releases.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                        },
                                    modifier = Modifier.semantics { role = Role.RadioButton },
                                ) {
                                    Text(
                                        text = release.tagName,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .padding(start = 8.dp)
                                        .size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                when {
                    error != null && releases.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp),
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp),
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.error_loading_changelog),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        isLoading = true
                                        error = null
                                        coroutineScope.launch { loadReleases(forceRefresh = true) }
                                    },
                                ) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }

                    releases.isEmpty() && !isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.no_releases),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    else -> {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                        ) {
                            currentRelease?.let { release ->
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    // Header row: version tag + formatted date (vivi-music layout).
                                    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
                                    val displayDateFormat = remember { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()) }
                                    val formattedDate =
                                        remember(release.publishedAt) {
                                            try {
                                                val date = dateFormat.parse(release.publishedAt.substring(0, 10))
                                                date?.let { displayDateFormat.format(it) } ?: release.publishedAt
                                            } catch (e: Exception) {
                                                release.publishedAt
                                            }
                                        }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = release.tagName,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = formattedDate,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    // Render the release body as bullet-point lines with clickable URLs
                                    // (mirrors vivi-music's ClickableText + buildAnnotatedString pattern).
                                    if (!release.body.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(16.dp))
                                        val bodyLines =
                                            release.body
                                                .lines()
                                                .filter { it.isNotBlank() }
                                                .map { it.removePrefix("-").removePrefix("*").trim() }
                                        bodyLines.forEach { line ->
                                            val urls = extractUrls(line)
                                            val annotatedText =
                                                buildAnnotatedString {
                                                    append(line)
                                                    urls.forEach { (range, url) ->
                                                        addStringAnnotation("URL", url, range.first, range.last + 1)
                                                        addStyle(
                                                            SpanStyle(
                                                                color = MaterialTheme.colorScheme.primary,
                                                                textDecoration = TextDecoration.Underline,
                                                            ),
                                                            range.first,
                                                            range.last + 1,
                                                        )
                                                    }
                                                }
                                            Row(
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .padding(top = 8.dp)
                                                            .size(6.dp)
                                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                                )
                                                ClickableText(
                                                    text = annotatedText,
                                                    onClick = { offset ->
                                                        annotatedText.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                                                            ContextCompat.startActivity(
                                                                context,
                                                                Intent(Intent.ACTION_VIEW, Uri.parse(it.item)),
                                                                null,
                                                            )
                                                        }
                                                    },
                                                    style =
                                                        MaterialTheme.typography.bodyLarge.copy(
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                        ),
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(32.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Pull-to-refresh loading indicator at the top center.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        scaleX = scaleFraction()
                        scaleY = scaleFraction()
                    },
            ) {
                PullToRefreshDefaults.LoadingIndicator(state = pullToRefreshState, isRefreshing = isRefreshing)
            }
        }
    }
}

/** URL extractor matching vivi-music's regex (http/https/www./pic. prefixes). */
private val URL_REGEX =
    Regex("(?:^|[\\s])((https?://|www\\.|pic\\.)[\\w-]+(\\.[\\w-]+)+([/?].*)?)")

private fun extractUrls(text: String): List<Pair<IntRange, String>> {
    val urlList = mutableListOf<Pair<IntRange, String>>()
    for (match in URL_REGEX.findAll(text)) {
        val group = match.groups[1] ?: continue
        val url = group.value.trim().takeIf { it.isNotEmpty() } ?: continue
        val range = IntRange(group.range.first, group.range.last)
        urlList.add(range to (if (url.startsWith("http")) url else "https://$url"))
    }
    return urlList
}
