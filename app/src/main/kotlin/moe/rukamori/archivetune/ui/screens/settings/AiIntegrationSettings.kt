/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import moe.rukamori.archivetune.utils.rememberPreferenceSnapshot
import moe.rukamori.archivetune.ui.component.preferenceGroup
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ai.AiModelOption
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiApiValidationStatus
import moe.rukamori.archivetune.constants.AiApiValidationStatusKey
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AiCustomModelKey
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.AiSelectedModelKey
import moe.rukamori.archivetune.constants.AiRomanizeExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AiRomanizeLyricsKey
import moe.rukamori.archivetune.constants.AutoAiRomanizeLyricsKey
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.DeeplApiKeyKey
import moe.rukamori.archivetune.constants.DeeplFormalityKey
import moe.rukamori.archivetune.constants.HideAiMixKey
import moe.rukamori.archivetune.constants.OpenRouterApiKeyKey
import moe.rukamori.archivetune.constants.OpenRouterBaseUrlKey
import moe.rukamori.archivetune.constants.OpenRouterModelKey
import moe.rukamori.archivetune.constants.TranslateModeKey
import moe.rukamori.archivetune.constants.TranslateLanguageKey
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.EditTextPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.TranslatorLang
import moe.rukamori.archivetune.utils.TranslatorLanguages
import moe.rukamori.archivetune.viewmodels.AiIntegrationSettingsViewModel

private enum class TestApiVisualState { Idle, Testing, Success, Failed }

@Composable
fun AiIntegrationSettings(
    navController: NavController,
    viewModel: AiIntegrationSettingsViewModel = hiltViewModel(),
    scrollTo: String? = null,
) {
    val prefs = rememberPreferenceSnapshot()
    val context = LocalContext.current
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val (provider, setProvider) = prefs.enumState(AiProviderKey, AiProvider.NONE)
    val (apiKey, setApiKey) = prefs.state(AiApiKeyKey, "")
    val (customEndpoint, setCustomEndpoint) = prefs.state(AiCustomEndpointKey, "")
    val (validationStatus, setValidationStatus) =
        prefs.enumState(AiApiValidationStatusKey, AiApiValidationStatus.UNKNOWN)
    val (selectedModel, setSelectedModel) = prefs.state(AiSelectedModelKey, "")
    val (customModel, setCustomModel) = prefs.state(AiCustomModelKey, "")
    val (hideAiMix, onHideAiMixChange) = prefs.state(HideAiMixKey, defaultValue = false)
    val (autoTranslateLyrics, onAutoTranslateLyricsChange) =
        prefs.state(AutoTranslateLyricsKey, defaultValue = false)
    // DeepL / OpenRouter / Mistral-specific preferences (ported from vivi-music).
    val (deeplApiKey, setDeeplApiKey) = prefs.state(DeeplApiKeyKey, "")
    val (deeplFormality, setDeeplFormality) = prefs.state(DeeplFormalityKey, "default")
    val (openRouterApiKey, setOpenRouterApiKey) = prefs.state(OpenRouterApiKeyKey, "")
    val (openRouterBaseUrl, setOpenRouterBaseUrl) = prefs.state(OpenRouterBaseUrlKey, "")
    val (openRouterModel, setOpenRouterModel) = prefs.state(OpenRouterModelKey, "openai/gpt-4o-mini")
    val (translateMode, setTranslateMode) = prefs.state(TranslateModeKey, "translate")
    val (translateLanguage, setTranslateLanguage) = prefs.state(TranslateLanguageKey, "en")
    var showDeeplKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenRouterKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenRouterModelDialog by rememberSaveable { mutableStateOf(false) }
    val (excludedLanguageCodes, onExcludedLanguageCodesChange) =
        prefs.state(AutoTranslateExcludedLanguagesKey, defaultValue = emptySet())
    var showExcludedLanguagesDialog by rememberSaveable { mutableStateOf(false) }
    val (aiRomanizeLyrics, onAiRomanizeLyricsChange) =
        prefs.state(AiRomanizeLyricsKey, defaultValue = false)
    val (autoAiRomanizeLyrics, onAutoAiRomanizeLyricsChange) =
        prefs.state(AutoAiRomanizeLyricsKey, defaultValue = false)
    val (romanizeExcludedLanguageCodes, onRomanizeExcludedLanguageCodesChange) =
        prefs.state(AiRomanizeExcludedLanguagesKey, defaultValue = emptySet())
    var showRomanizeExcludedLanguagesDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateLanguageDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val hasCustomEndpoint = provider != AiProvider.CUSTOM || customEndpoint.isNotBlank()
    val hasApiConfiguration =
        when (provider) {
            AiProvider.DEEPL -> deeplApiKey.isNotBlank()
            AiProvider.OPENROUTER -> openRouterApiKey.isNotBlank()
            AiProvider.MISTRAL -> apiKey.isNotBlank()
            AiProvider.NONE -> false
            else -> apiKey.isNotBlank() && hasCustomEndpoint
        }
    val hasModelConfiguration =
        when (provider) {
            AiProvider.CUSTOM -> customModel.isNotBlank()
            AiProvider.DEEPL -> true // No model picker for DeepL; the API key determines the tier.
            AiProvider.NONE -> false
            else -> selectedModel.isNotBlank() || openRouterModel.isNotBlank()
        }
    val canUseModelPicker =
        provider != AiProvider.NONE &&
            provider != AiProvider.CUSTOM &&
            provider != AiProvider.DEEPL &&
            provider != AiProvider.OPENROUTER &&
            apiKey.isNotBlank()
    val canTestApi = hasApiConfiguration && hasModelConfiguration && !actionState.isTesting

    if (showApiKeyDialog) {
        ApiKeyDialog(
            value = apiKey,
            onDismiss = { showApiKeyDialog = false },
            onSave = { value ->
                setApiKey(value.trim())
                setValidationStatus(AiApiValidationStatus.UNKNOWN)
                viewModel.clearAvailableModels()
            },
        )
    }

    if (showDeeplKeyDialog) {
        ApiKeyDialog(
            value = deeplApiKey,
            onDismiss = { showDeeplKeyDialog = false },
            onSave = { value ->
                setDeeplApiKey(value.trim())
                setValidationStatus(AiApiValidationStatus.UNKNOWN)
            },
        )
    }

    if (showExcludedLanguagesDialog) {
        ExcludedLanguagesDialog(
            initialSelected = excludedLanguageCodes,
            titleRes = R.string.auto_translate_excluded_languages,
            descriptionRes = R.string.auto_translate_excluded_languages_desc,
            onDismiss = { showExcludedLanguagesDialog = false },
            onConfirm = { newSet ->
                onExcludedLanguageCodesChange(newSet)
                showExcludedLanguagesDialog = false
            },
        )
    }

    if (showRomanizeExcludedLanguagesDialog) {
        ExcludedLanguagesDialog(
            initialSelected = romanizeExcludedLanguageCodes,
            titleRes = R.string.ai_romanize_excluded_languages,
            descriptionRes = R.string.ai_romanize_excluded_languages_desc,
            onDismiss = { showRomanizeExcludedLanguagesDialog = false },
            onConfirm = { newSet ->
                onRomanizeExcludedLanguageCodesChange(newSet)
                showRomanizeExcludedLanguagesDialog = false
            },
        )
    }

    if (showOpenRouterKeyDialog) {
        ApiKeyDialog(
            value = openRouterApiKey,
            onDismiss = { showOpenRouterKeyDialog = false },
            onSave = { value ->
                setOpenRouterApiKey(value.trim())
                setValidationStatus(AiApiValidationStatus.UNKNOWN)
            },
        )
    }

    if (showDeeplFormalityDialog) {
        DefaultDialog(
            onDismiss = { showDeeplFormalityDialog = false },
            title = { Text(stringResource(R.string.deepl_formality)) },
            buttons = {
                TextButton(onClick = { showDeeplFormalityDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            Column {
                listOf("default" to R.string.deepl_formality_default, "more" to R.string.deepl_formality_more, "less" to R.string.deepl_formality_less).forEach { (value, labelRes) ->
                    TextButton(
                        onClick = {
                            setDeeplFormality(value)
                            showDeeplFormalityDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }
        }
    }

    if (showTranslateModeDialog) {
        DefaultDialog(
            onDismiss = { showTranslateModeDialog = false },
            title = { Text(stringResource(R.string.translate_mode)) },
            buttons = {
                TextButton(onClick = { showTranslateModeDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            Column {
                listOf("translate" to R.string.translate_mode_translate, "romanize" to R.string.translate_mode_romanize).forEach { (value, labelRes) ->
                    TextButton(
                        onClick = {
                            setTranslateMode(value)
                            showTranslateModeDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }
        }
    }

    if (showTranslateLanguageDialog) {
        var langField by remember { mutableStateOf(translateLanguage) }
        DefaultDialog(
            onDismiss = { showTranslateLanguageDialog = false },
            title = { Text(stringResource(R.string.translate_language)) },
            buttons = {
                TextButton(onClick = { showTranslateLanguageDialog = false }) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = {
                        setTranslateLanguage(langField.trim())
                        showTranslateLanguageDialog = false
                    },
                    enabled = langField.isNotBlank(),
                ) { Text(stringResource(R.string.save)) }
            },
        ) {
            OutlinedTextField(
                value = langField,
                onValueChange = { langField = it },
                singleLine = true,
                label = { Text(stringResource(R.string.translate_language_hint)) },
            )
        }
    }

    // Mini-player aware bottom padding — keeps the last settings row from being
    // covered by the persistent mini player when a song is loaded. Other settings
    // screens (e.g. NavigationBarSettings) use the same pattern.
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()
    val listState = rememberLazyListState()
    val positions = rememberPreferencePositions()
    androidx.compose.runtime.LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, listState) }

    // Lazy, not a verticalScroll Column: every row composed before the first frame, inside the
    // opening transition.
    val aiProviderSettingsTitle = stringResource(R.string.ai_provider_settings)
    val aiTranslationSettingsTitle = stringResource(R.string.ai_translation_settings)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        modifier =
            Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                .then(positions.containerModifier()),
    ) {
        item {
            Spacer(
                Modifier.windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
                ),
            )
        }

        preferenceGroup(title = aiProviderSettingsTitle) {
            item {
                ListPreference(
                    modifier = positions.modifierFor("ai_provider"),
                    title = { Text(stringResource(R.string.ai_provider)) },
                    description = stringResource(R.string.ai_provider_desc),
                    icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                    selectedValue = provider,
                    values =
                        listOf(
                            AiProvider.GEMINI,
                            AiProvider.CHATGPT,
                            AiProvider.DEEPL,
                            AiProvider.OPENROUTER,
                            AiProvider.MISTRAL,
                            AiProvider.CUSTOM,
                            AiProvider.NONE,
                        ),
                    valueText = { it.label() },
                    onValueSelected = { selectedProvider ->
                        if (provider != selectedProvider) {
                            setSelectedModel("")
                            viewModel.clearAvailableModels()
                        }
                        setProvider(selectedProvider)
                        setValidationStatus(AiApiValidationStatus.UNKNOWN)
                    },
                )
            }

            item(visible = provider == AiProvider.CUSTOM) {
                EditTextPreference(
                    modifier = positions.modifierFor("ai_custom_endpoint"),
                    title = { Text(stringResource(R.string.ai_custom_endpoint)) },
                    icon = { Icon(painterResource(R.drawable.website), null) },
                    value = customEndpoint,
                    onValueChange = {
                        setCustomEndpoint(it.trim())
                        setValidationStatus(AiApiValidationStatus.UNKNOWN)
                        viewModel.clearError()
                    },
                    isInputValid = { it.startsWith("https://") || it.startsWith("http://") },
                )
            }

            // Inline hint that tells the user where to obtain an API key for the
            // currently-selected provider. Tapping the row opens the provider's
            // developer console / sign-up page in the system browser. Hidden for
            // CUSTOM (user supplies their own endpoint/key) and NONE.
            item(visible = provider != AiProvider.NONE && provider != AiProvider.CUSTOM) {
                val keyPortalUrl = provider.apiKeyPortalUrl()
                val keyPortalLabel = provider.apiKeyPortalLabel()
                PreferenceEntry(
                    title = { Text("Get API key") },
                    description = keyPortalLabel,
                    icon = { Icon(painterResource(R.drawable.link), null) },
                    onClick = {
                        if (!keyPortalUrl.isNullOrBlank()) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(keyPortalUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                        }
                    },
                )
            }

            item {
                PreferenceEntry(
                    modifier = positions.modifierFor("ai_api_key"),
                    title = { Text(stringResource(R.string.ai_api_key)) },
                    description =
                        if (apiKey.isBlank()) {
                            stringResource(R.string.ai_api_key_missing)
                        } else {
                            stringResource(R.string.ai_api_key_configured)
                        },
                    icon = { Icon(painterResource(R.drawable.token), null) },
                    onClick = { showApiKeyDialog = true },
                    isEnabled = provider != AiProvider.NONE,
                )
            }

            item(visible = provider != AiProvider.NONE && provider != AiProvider.CUSTOM) {
                ModelPickerPreference(
                    selectedModel = selectedModel,
                    availableModels = availableModels,
                    isFetching = actionState.isFetchingModels,
                    isEnabled = canUseModelPicker,
                    canFetch = apiKey.isNotBlank() && !actionState.isFetchingModels,
                    onModelSelected = {
                        setSelectedModel(it)
                        setValidationStatus(AiApiValidationStatus.UNKNOWN)
                        viewModel.clearError()
                    },
                    onFetch = { viewModel.fetchModels(provider, apiKey, customEndpoint) },
                )
            }

            item(visible = provider == AiProvider.CUSTOM) {
                EditTextPreference(
                    modifier = positions.modifierFor("ai_model"),
                    title = { Text(stringResource(R.string.ai_model)) },
                    icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                    value = customModel,
                    onValueChange = {
                        setCustomModel(it)
                        setValidationStatus(AiApiValidationStatus.UNKNOWN)
                        viewModel.clearError()
                    },
                )
            }

            item {
                val testVisualState =
                    when {
                        actionState.isTesting -> TestApiVisualState.Testing
                        validationStatus == AiApiValidationStatus.SUCCESS -> TestApiVisualState.Success
                        validationStatus == AiApiValidationStatus.FAILED -> TestApiVisualState.Failed
                        else -> TestApiVisualState.Idle
                    }
                PreferenceEntry(
                    modifier = positions.modifierFor("ai_test_api"),
                    title = { Text(stringResource(R.string.ai_test_api)) },
                    icon = {
                        AnimatedContent(
                            targetState = testVisualState,
                            transitionSpec = {
                                (
                                    scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) +
                                        fadeIn(tween(200))
                                ) togetherWith
                                    (scaleOut(tween(100)) + fadeOut(tween(100)))
                            },
                            label = "testApiIcon",
                        ) { state ->
                            when (state) {
                                TestApiVisualState.Success -> {
                                    Icon(painterResource(R.drawable.done), null)
                                }

                                TestApiVisualState.Failed -> {
                                    Icon(painterResource(R.drawable.error), null, tint = MaterialTheme.colorScheme.error)
                                }

                                else -> {
                                    Icon(painterResource(R.drawable.sync), null)
                                }
                            }
                        }
                    },
                    content = {
                        Spacer(Modifier.height(2.dp))
                        AnimatedContent(
                            targetState = testVisualState,
                            transitionSpec = {
                                (slideInVertically { -it } + fadeIn(tween(250))) togetherWith
                                    (slideOutVertically { it } + fadeOut(tween(150)))
                            },
                            label = "testApiDesc",
                        ) { state ->
                            Text(
                                text =
                                    when (state) {
                                        TestApiVisualState.Testing -> stringResource(R.string.ai_api_testing)
                                        else -> validationStatus.label()
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                    when (state) {
                                        TestApiVisualState.Success -> MaterialTheme.colorScheme.primary
                                        TestApiVisualState.Failed -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                        actionState.errorMessage?.let { message ->
                            Spacer(Modifier.height(10.dp))
                            AiErrorHintRow(message = message)
                        }
                    },
                    trailingContent = {
                        AnimatedContent(
                            targetState = actionState.isTesting,
                            transitionSpec = {
                                (scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(tween(200))) togetherWith
                                    (scaleOut(tween(150)) + fadeOut(tween(150)))
                            },
                            label = "testApiTrailing",
                        ) { isTesting ->
                            if (isTesting) {
                                CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    },
                    onClick = viewModel::testApi,
                    isEnabled = canTestApi,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("hide_ai_mix"),
                    title = { Text(stringResource(R.string.hide_ai_mix)) },
                    description = stringResource(R.string.hide_ai_mix_desc),
                    icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                    checked = hideAiMix,
                    onCheckedChange = onHideAiMixChange,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("auto_translate_lyrics"),
                    title = { Text(stringResource(R.string.auto_translate_lyrics)) },
                    description = stringResource(R.string.auto_translate_lyrics_desc),
                    icon = { Icon(painterResource(R.drawable.translate), null) },
                    checked = autoTranslateLyrics,
                    onCheckedChange = onAutoTranslateLyricsChange,
                    // Without an AI provider + API key, there's no engine to run the
                    // translation, so the toggle is greyed out rather than silently ignored.
                    isEnabled = hasApiConfiguration,
                )
            }

            // "Don't auto translate these languages" — multi-select. Visible only when
            // auto-translate is on. Lets users pick language codes that should NEVER be
            // auto-translated (e.g. they understand Japanese and don't want it translated).
            item(visible = autoTranslateLyrics) {
                val languages = remember(context) { TranslatorLanguages.load(context) }
                val selectedNames =
                    remember(excludedLanguageCodes, languages) {
                        languages
                            .filter { it.code in excludedLanguageCodes }
                            .joinToString(", ") { it.name }
                            .ifEmpty { null }
                    }
                PreferenceEntry(
                    modifier = positions.modifierFor("auto_translate_excluded_languages"),
                    title = { Text(stringResource(R.string.auto_translate_excluded_languages)) },
                    description =
                        selectedNames
                            ?: stringResource(R.string.auto_translate_excluded_languages_none),
                    icon = { Icon(painterResource(R.drawable.block), null) },
                    onClick = { showExcludedLanguagesDialog = true },
                )
            }

            // ── AI romanisation ──
            // Master switch. Turning it on also switches the built-in romanisers off (see
            // `LyricsRomanizationPreferences.aiHandled`) so one song never mixes two schemes.
            item {
                SwitchPreference(
                    modifier = positions.modifierFor("ai_romanize_lyrics"),
                    title = { Text(stringResource(R.string.ai_romanize_lyrics)) },
                    description = stringResource(R.string.ai_romanize_lyrics_desc),
                    icon = { Icon(painterResource(R.drawable.language), null) },
                    checked = aiRomanizeLyrics,
                    onCheckedChange = onAiRomanizeLyricsChange,
                    // Same reasoning as auto-translate: with no provider there is no engine to run.
                    isEnabled = hasApiConfiguration,
                )
            }

            // Separate from the master switch because these are billed network calls: enabling the
            // feature should not commit the user to one request per track. With this off, the request
            // is made from Lyrics menu → "Romanise with AI".
            item(visible = aiRomanizeLyrics) {
                SwitchPreference(
                    modifier = positions.modifierFor("auto_ai_romanize_lyrics"),
                    title = { Text(stringResource(R.string.auto_ai_romanize_lyrics)) },
                    description = stringResource(R.string.auto_ai_romanize_lyrics_desc),
                    icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                    checked = autoAiRomanizeLyrics,
                    onCheckedChange = onAutoAiRomanizeLyricsChange,
                    isEnabled = hasApiConfiguration,
                )
            }

            item(visible = aiRomanizeLyrics) {
                val languages = remember(context) { TranslatorLanguages.load(context) }
                val selectedRomanizeNames =
                    remember(romanizeExcludedLanguageCodes, languages) {
                        languages
                            .filter { it.code in romanizeExcludedLanguageCodes }
                            .joinToString(", ") { it.name }
                            .ifEmpty { null }
                    }
                PreferenceEntry(
                    modifier = positions.modifierFor("ai_romanize_excluded_languages"),
                    title = { Text(stringResource(R.string.ai_romanize_excluded_languages)) },
                    description =
                        selectedRomanizeNames
                            ?: stringResource(R.string.ai_romanize_excluded_languages_none),
                    icon = { Icon(painterResource(R.drawable.block), null) },
                    onClick = { showRomanizeExcludedLanguagesDialog = true },
                )
            }
        }

        // DeepL / OpenRouter / Mistral provider-specific configuration. These three providers
        // (ported from vivi-music) have dedicated preference keys separate from the generic
        // CHATGPT/GEMINI/CUSTOM chat-completion path, so they each get their own subsection.
        if (provider == AiProvider.DEEPL || provider == AiProvider.OPENROUTER || provider == AiProvider.MISTRAL) {
            preferenceGroup(title = aiTranslationSettingsTitle) {
                if (provider == AiProvider.DEEPL) {
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("deepl_api_key"),
                            title = { Text(stringResource(R.string.deepl_api_key)) },
                            description = if (deeplApiKey.isBlank()) stringResource(R.string.deepl_api_key_not_set) else stringResource(R.string.deepl_api_key_set),
                            icon = { Icon(painterResource(R.drawable.token), null) },
                            onClick = { showDeeplKeyDialog = true },
                        )
                    }
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("deepl_formality"),
                            title = { Text(stringResource(R.string.deepl_formality)) },
                            description = deeplFormality,
                            icon = { Icon(painterResource(R.drawable.text_fields), null) },
                            onClick = { showDeeplFormalityDialog = true },
                        )
                    }
                }
                if (provider == AiProvider.OPENROUTER) {
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("openrouter_api_key"),
                            title = { Text(stringResource(R.string.openrouter_api_key)) },
                            description = if (openRouterApiKey.isBlank()) stringResource(R.string.openrouter_api_key_not_set) else stringResource(R.string.openrouter_api_key_set),
                            icon = { Icon(painterResource(R.drawable.token), null) },
                            onClick = { showOpenRouterKeyDialog = true },
                        )
                    }
                    item {
                        EditTextPreference(
                            title = { Text(stringResource(R.string.openrouter_base_url)) },
                            icon = { Icon(painterResource(R.drawable.website), null) },
                            value = openRouterBaseUrl,
                            onValueChange = { setOpenRouterBaseUrl(it.trim()) },
                            isInputValid = { it.isBlank() || it.startsWith("http://") || it.startsWith("https://") },
                        )
                    }
                    item {
                        EditTextPreference(
                            title = { Text(stringResource(R.string.openrouter_model)) },
                            icon = { Icon(painterResource(R.drawable.tune), null) },
                            value = openRouterModel,
                            onValueChange = { setOpenRouterModel(it.trim()) },
                            isInputValid = { it.isNotBlank() },
                        )
                    }
                }
                if (provider == AiProvider.MISTRAL) {
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("mistral_api_key"),
                            title = { Text(stringResource(R.string.mistral_api_key)) },
                            description = if (apiKey.isBlank()) stringResource(R.string.mistral_api_key_not_set) else stringResource(R.string.mistral_api_key_set),
                            icon = { Icon(painterResource(R.drawable.token), null) },
                            onClick = { showApiKeyDialog = true },
                        )
                    }
                    item {
                        EditTextPreference(
                            title = { Text(stringResource(R.string.mistral_model)) },
                            icon = { Icon(painterResource(R.drawable.tune), null) },
                            value = selectedModel,
                            onValueChange = { setSelectedModel(it.trim()) },
                            isInputValid = { it.isNotBlank() },
                        )
                    }
                }
                // Translation target language + mode shared by DeepL/OpenRouter/Mistral.
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("translate_language"),
                        title = { Text(stringResource(R.string.translate_language)) },
                        description = translateLanguage,
                        icon = { Icon(painterResource(R.drawable.translate), null) },
                        onClick = { showTranslateLanguageDialog = true },
                    )
                }
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("translate_mode"),
                        title = { Text(stringResource(R.string.translate_mode)) },
                        description = if (translateMode == "romanize") stringResource(R.string.translate_mode_romanize) else stringResource(R.string.translate_mode_translate),
                        icon = { Icon(painterResource(R.drawable.text_fields), null) },
                        onClick = { showTranslateModeDialog = true },
                    )
                }
            }
        }
    }

    SettingsTopAppBar(
        title = { Text(stringResource(R.string.ai_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.back_button_desc),
                )
            }
        },
    )
}

@Composable
private fun ApiKeyDialog(
    value: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(painterResource(R.drawable.token), contentDescription = null) },
        title = { Text(stringResource(R.string.ai_api_key)) },
        buttons = {
            ApiKeyDialogButtons(
                canSave = field.text.isNotBlank(),
                onDismiss = onDismiss,
                onSave = {
                    onSave(field.text)
                    onDismiss()
                },
            )
        },
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            label = { Text(stringResource(R.string.ai_api_key)) },
        )
    }
}

@Composable
private fun RowScope.ApiKeyDialogButtons(
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
        Text(stringResource(android.R.string.cancel))
    }
    TextButton(
        enabled = canSave,
        onClick = onSave,
        shapes = ButtonDefaults.shapes(),
    ) {
        Text(stringResource(R.string.save))
    }
}

@Composable
private fun AiProvider.label(): String =
    when (this) {
        AiProvider.CHATGPT -> "OpenAI"
        AiProvider.GEMINI -> "Gemini"
        AiProvider.OPENROUTER -> stringResource(R.string.ai_provider_openrouter)
        AiProvider.CUSTOM -> stringResource(R.string.custom)
        AiProvider.DEEPL -> "DeepL"
        AiProvider.MISTRAL -> "Mistral AI"
        AiProvider.NONE -> stringResource(R.string.ai_provider_none)
    }

/**
 * Returns the developer-portal URL where the user can sign up for / fetch an API
 * key for this provider. Null for providers that don't have a public self-serve
 * portal (CUSTOM relies on the user's own endpoint, NONE is the off state).
 */
private fun AiProvider.apiKeyPortalUrl(): String? =
    when (this) {
        AiProvider.CHATGPT -> "https://platform.openai.com/api-keys"
        AiProvider.GEMINI -> "https://aistudio.google.com/app/apikey"
        AiProvider.DEEPL -> "https://www.deepl.com/pro-api"
        AiProvider.OPENROUTER -> "https://openrouter.ai/keys"
        AiProvider.MISTRAL -> "https://console.mistral.ai/api-keys"
        AiProvider.CUSTOM, AiProvider.NONE -> null
    }

/**
 * One-line description shown under the "Get API key" row. Tells the user which
 * portal the row opens and (where relevant) which plan is needed for API access.
 */
@Composable
private fun AiProvider.apiKeyPortalLabel(): String =
    when (this) {
        AiProvider.CHATGPT -> "platform.openai.com/api-keys — create a key with the \"ChatGPT\" or \"Project\" scope"
        AiProvider.GEMINI -> "aistudio.google.com/app/apikey — free tier available with a Google account"
        AiProvider.DEEPL -> "deepl.com/pro-api — DeepL Pro plan required for API access"
        AiProvider.OPENROUTER -> "openrouter.ai/keys — free + paid models, billable by usage"
        AiProvider.MISTRAL -> "console.mistral.ai/api-keys — create a key under \"API Keys\""
        AiProvider.CUSTOM, AiProvider.NONE -> ""
    }

@Composable
private fun AiApiValidationStatus.label(): String =
    when (this) {
        AiApiValidationStatus.UNKNOWN -> stringResource(R.string.ai_api_status_unknown)
        AiApiValidationStatus.SUCCESS -> stringResource(R.string.ai_api_status_success)
        AiApiValidationStatus.FAILED -> stringResource(R.string.ai_api_status_failed)
    }

@Composable
private fun AiErrorHintRow(message: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModelPickerPreference(
    selectedModel: String,
    availableModels: List<AiModelOption>,
    isFetching: Boolean,
    isEnabled: Boolean,
    canFetch: Boolean,
    onModelSelected: (String) -> Unit,
    onFetch: () -> Unit,
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val filteredModels by remember(availableModels) {
        derivedStateOf {
            val query = searchQuery.trim()
            if (query.isBlank()) {
                availableModels
            } else {
                availableModels.filter { model ->
                    model.displayName.contains(query, ignoreCase = true) ||
                        model.id.contains(query, ignoreCase = true)
                }
            }
        }
    }

    val description =
        when {
            isFetching && availableModels.isEmpty() -> stringResource(R.string.ai_model_loading)
            availableModels.isEmpty() && !canFetch -> stringResource(R.string.ai_model_api_key_required)
            availableModels.isEmpty() -> stringResource(R.string.ai_model_fetch_hint)
            selectedModel.isBlank() -> stringResource(R.string.ai_model_not_selected)
            else -> availableModels.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel
        }

    LaunchedEffect(showSheet) {
        if (!showSheet) {
            searchQuery = ""
        }
    }

    LaunchedEffect(isEnabled) {
        if (!isEnabled) {
            showSheet = false
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = stringResource(R.string.ai_model),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .padding(horizontal = 26.dp)
                        .padding(top = 18.dp, bottom = 22.dp),
            )
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text(stringResource(R.string.ai_model_search)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = null,
                            )
                        },
                        trailingIcon =
                            if (searchQuery.isNotBlank()) {
                                {
                                    androidx.compose.material3.IconButton(
                                        onClick = { searchQuery = "" },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.close),
                                            contentDescription = stringResource(R.string.clear),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                    )
                },
                expanded = false,
                onExpandedChange = {},
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp)
                        .padding(bottom = 18.dp),
            ) {}
            LazyColumn(
                contentPadding = PaddingValues(start = 26.dp, end = 26.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
            ) {
                if (filteredModels.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Text(
                            text = stringResource(R.string.ai_model_no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp),
                        )
                    }
                }
                items(
                    items = filteredModels,
                    key = { it.id },
                    contentType = { "model" },
                ) { model ->
                    val id = model.id
                    val displayName = model.displayName
                    val selected = id == selectedModel
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                ).selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onModelSelected(id)
                                        coroutineScope
                                            .launch {
                                                sheetState.hide()
                                            }.invokeOnCompletion {
                                                showSheet = false
                                            }
                                    },
                                ).padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (displayName != id) {
                                Text(
                                    text = id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color =
                                        if (selected) {
                                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    PreferenceEntry(
        title = { Text(stringResource(R.string.ai_model)) },
        description = description,
        icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
        trailingContent = {
            if (isFetching) {
                CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                FilledTonalIconButton(
                    onClick = onFetch,
                    enabled = canFetch,
                ) {
                    Icon(
                        painterResource(R.drawable.sync),
                        contentDescription = stringResource(R.string.ai_fetch_models),
                    )
                }
            }
        },
        onClick = if (isEnabled && availableModels.isNotEmpty()) ({ showSheet = true }) else null,
        isEnabled = isEnabled,
    )
}

/**
 * Multi-select dialog behind both "Don't auto translate these languages" and "Don't romanise these
 * languages".
 */
@Composable
private fun ExcludedLanguagesDialog(
    initialSelected: Set<String>,
    titleRes: Int,
    descriptionRes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val languages = remember(context) { TranslatorLanguages.load(context) }
    val selected = remember { mutableStateOf(initialSelected.toMutableSet()) }

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(painterResource(R.drawable.block), contentDescription = null) },
        title = { Text(stringResource(titleRes)) },
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
            TextButton(
                onClick = { onConfirm(selected.value.toSet()) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
            ) {
                itemsIndexed(languages, key = { _, lang -> lang.code }) { _, lang ->
                    val isChecked = lang.code in selected.value
                    ListItem(
                        headlineContent = { Text(lang.name) },
                        leadingContent = {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val updated = selected.value.toMutableSet()
                                    if (checked) {
                                        updated.add(lang.code)
                                    } else {
                                        updated.remove(lang.code)
                                    }
                                    selected.value = updated
                                },
                            )
                        },
                        modifier = Modifier.clickable {
                            val updated = selected.value.toMutableSet()
                            if (lang.code in updated) {
                                updated.remove(lang.code)
                            } else {
                                updated.add(lang.code)
                            }
                            selected.value = updated
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
