/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.constants.CompactBottomPillsKey
import moe.rukamori.archivetune.constants.NavigationBarHideOnScrollKey
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_HEIGHT_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_LABEL_SPACING_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_OPACITY_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_TRANSPARENCY_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_WIDTH_DEFAULT
import moe.rukamori.archivetune.constants.NavigationBarCornerRadiusKey
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.constants.NavigationBarBitchordKey
import moe.rukamori.archivetune.constants.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarHeight
import moe.rukamori.archivetune.constants.NavigationBarHeightKey
import moe.rukamori.archivetune.constants.NavigationBarLabelSpacingKey
import moe.rukamori.archivetune.constants.NavigationBarOpacityKey
import moe.rukamori.archivetune.constants.NavigationBarStyle
import moe.rukamori.archivetune.constants.NavigationBarStyleKey
import moe.rukamori.archivetune.constants.CompactLayoutChoice
import moe.rukamori.archivetune.constants.NavigationBarTransparencyKey
import moe.rukamori.archivetune.constants.NavigationBarWidthKey
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.rememberAppleMusicExperience
import moe.rukamori.archivetune.ui.screens.Screens
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.asPaddingValues

@Composable
fun NavigationBarSettings(navController: NavController, scrollTo: String? = null) {
    val (navigationBarStyle, onNavigationBarStyleChange) =
        rememberEnumPreference(
            NavigationBarStyleKey,
            defaultValue = NavigationBarStyle.DEFAULT,
        )
    val (compactBottomPills, onCompactBottomPillsChange) =
        rememberPreference(CompactBottomPillsKey, defaultValue = false)
    val appleMusicExperience = rememberAppleMusicExperience()
    val (navigationBarFrostedBlur, onNavigationBarFrostedBlurChange) =
        rememberPreference(NavigationBarFrostedBlurKey, defaultValue = false)
    val (navigationBarTintFrostedBlur, onNavigationBarTintFrostedBlurChange) =
        rememberPreference(NavigationBarTintFrostedBlurKey, defaultValue = false)
    // Liquid Glass master toggle (Appearance) + nav-bar sub-toggle. The sub-toggle
    // is only effective when the master is on AND on Android 12+; otherwise the
    // FloatingNavigationToolbar falls back to its non-glass style.
    val (liquidGlassEnabled) =
        rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val (liquidGlassNavBarEnabled, onLiquidGlassNavBarEnabledChange) =
        rememberPreference(LiquidGlassNavBarEnabledKey, defaultValue = false)
    // Turning on any nav treatment turns the other treatments off.
    val (navigationBarBitchord, onNavigationBarBitchordChange) =
        rememberPreference(NavigationBarBitchordKey, defaultValue = false)
    // Mutual-exclusivity wrappers: turning one frosted variant on turns the other off.
    val onFrostedBlurChange: (Boolean) -> Unit = { checked ->
        onNavigationBarFrostedBlurChange(checked)
        if (checked) {
            if (navigationBarTintFrostedBlur) onNavigationBarTintFrostedBlurChange(false)
            if (navigationBarBitchord) onNavigationBarBitchordChange(false)
            if (liquidGlassNavBarEnabled) onLiquidGlassNavBarEnabledChange(false)
        }
    }
    val onTintFrostedBlurChange: (Boolean) -> Unit = { checked ->
        onNavigationBarTintFrostedBlurChange(checked)
        if (checked) {
            if (navigationBarFrostedBlur) onNavigationBarFrostedBlurChange(false)
            if (navigationBarBitchord) onNavigationBarBitchordChange(false)
            if (liquidGlassNavBarEnabled) onLiquidGlassNavBarEnabledChange(false)
        }
    }
    val onLiquidGlassNavBarChange: (Boolean) -> Unit = { checked ->
        onLiquidGlassNavBarEnabledChange(checked)
        if (checked) {
            if (navigationBarFrostedBlur) onNavigationBarFrostedBlurChange(false)
            if (navigationBarTintFrostedBlur) onNavigationBarTintFrostedBlurChange(false)
            if (navigationBarBitchord) onNavigationBarBitchordChange(false)
        }
    }
    val onBitchordChange: (Boolean) -> Unit = { checked ->
        onNavigationBarBitchordChange(checked)
        if (checked) {
            if (navigationBarFrostedBlur) onNavigationBarFrostedBlurChange(false)
            if (navigationBarTintFrostedBlur) onNavigationBarTintFrostedBlurChange(false)
            if (liquidGlassNavBarEnabled) onLiquidGlassNavBarEnabledChange(false)
        }
    }
    val (hideNavigationBarLabels, onHideNavigationBarLabelsChange) =
        rememberPreference(HideNavigationBarLabelsKey, defaultValue = false)
    val (hideOnScroll, onHideOnScrollChange) =
        rememberPreference(NavigationBarHideOnScrollKey, defaultValue = false)

    // Customization sliders. Defaults are the constants defined alongside
    // their preference keys so the pre-existing look is preserved.
    val (navigationBarWidth, onNavigationBarWidthChange) =
        rememberPreference(NavigationBarWidthKey, defaultValue = NAVIGATION_BAR_WIDTH_DEFAULT)
    val (navigationBarHeight, onNavigationBarHeightChange) =
        rememberPreference(NavigationBarHeightKey, defaultValue = NAVIGATION_BAR_HEIGHT_DEFAULT)
    val (navigationBarOpacity, onNavigationBarOpacityChange) =
        rememberPreference(NavigationBarOpacityKey, defaultValue = NAVIGATION_BAR_OPACITY_DEFAULT)
    val (navigationBarTransparency, onNavigationBarTransparencyChange) =
        rememberPreference(
            NavigationBarTransparencyKey,
            defaultValue = NAVIGATION_BAR_TRANSPARENCY_DEFAULT,
        )
    val (navigationBarLabelSpacing, onNavigationBarLabelSpacingChange) =
        rememberPreference(
            NavigationBarLabelSpacingKey,
            defaultValue = NAVIGATION_BAR_LABEL_SPACING_DEFAULT,
        )
    val (navigationBarCornerRadius, onNavigationBarCornerRadiusChange) =
        rememberPreference(
            NavigationBarCornerRadiusKey,
            defaultValue = NAVIGATION_BAR_CORNER_RADIUS_DEFAULT,
        )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.navigation_bar_settings_title)) },
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
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal,
                    ),
                )
                // Chained before verticalScroll so it measures the viewport, not the scrolling content.
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = stringResource(R.string.general)) {
                item {
                    Column(modifier = positions.modifierFor("navigation_bar_style")) {
                        EnumListPreference(
                            title = { Text(stringResource(R.string.navigation_bar_style)) },
                            icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                            selectedValue = navigationBarStyle,
                            onValueSelected = onNavigationBarStyleChange,
                            valueText = {
                                when (it) {
                                    NavigationBarStyle.DEFAULT ->
                                        stringResource(R.string.navigation_bar_style_default)
                                    NavigationBarStyle.FLOATING ->
                                        stringResource(R.string.navigation_bar_style_floating)
                                    NavigationBarStyle.APPLE_MUSIC ->
                                        stringResource(R.string.navigation_bar_style_apple_music)
                                }
                            },
                        )
                    }
                }

                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("compact_bottom_pills"),
                        title = { Text(stringResource(R.string.navigation_bar_layout)) },
                        description = stringResource(R.string.navigation_bar_layout_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        selectedValue = if (compactBottomPills) CompactLayoutChoice.COMPACT else CompactLayoutChoice.DEFAULT,
                        onValueSelected = { onCompactBottomPillsChange(it == CompactLayoutChoice.COMPACT) },
                        valueText = {
                            when (it) {
                                CompactLayoutChoice.DEFAULT -> stringResource(R.string.default_style)
                                CompactLayoutChoice.COMPACT -> stringResource(R.string.compact_layout)
                            }
                        },
                        isEnabled = !appleMusicExperience && navigationBarStyle != NavigationBarStyle.APPLE_MUSIC,
                    )
                }

                item {
                    Column {
                        SwitchPreference(
                            modifier = positions.modifierFor("navigation_bar_frosted_blur"),
                            title = { Text(stringResource(R.string.navigation_bar_frosted_blur)) },
                            description = stringResource(R.string.navigation_bar_frosted_blur_desc),
                            icon = { Icon(painterResource(R.drawable.blur_on), null) },
                            checked = navigationBarFrostedBlur,
                            onCheckedChange = onFrostedBlurChange,
                        )
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && navigationBarFrostedBlur) {
                            Text(
                                text = stringResource(R.string.navigation_bar_frosted_blur_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp),
                            )
                        }
                    }
                }

                item {
                    Column {
                        SwitchPreference(
                            modifier = positions.modifierFor("navigation_bar_tint_frosted_blur"),
                            title = { Text(stringResource(R.string.navigation_bar_tint_frosted_blur)) },
                            description = stringResource(R.string.navigation_bar_tint_frosted_blur_desc),
                            icon = { Icon(painterResource(R.drawable.blur_on), null) },
                            checked = navigationBarTintFrostedBlur,
                            onCheckedChange = onTintFrostedBlurChange,
                        )
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && navigationBarTintFrostedBlur) {
                            Text(
                                text = stringResource(R.string.navigation_bar_frosted_blur_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp),
                            )
                        }
                    }
                }

                item {
                    // Why the row is greyed out belongs IN the row, as its description — a loose
                    // Text hung underneath was a second, differently-styled thing to read, and it
                    // only ever appeared in the state where the row is already unusable.
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    SwitchPreference(
                        modifier = positions.modifierFor("liquid_glass_nav_bar"),
                        title = { Text(stringResource(R.string.liquid_glass_nav_bar)) },
                        description =
                            when {
                                !supported -> stringResource(R.string.liquid_glass_effects_unsupported)
                                !liquidGlassEnabled -> stringResource(R.string.liquid_glass_nav_bar_disabled)
                                else -> stringResource(R.string.liquid_glass_nav_bar_desc)
                            },
                        icon = { Icon(painterResource(R.drawable.blur_on), null) },
                        checked = liquidGlassNavBarEnabled,
                        // Disabled when the master Liquid Glass switch is off (Appearance →
                        // Liquid Glass effects) or on pre-Android 12: the kyant RuntimeShader
                        // stack requires API 31+.
                        isEnabled = liquidGlassEnabled && supported,
                        onCheckedChange = onLiquidGlassNavBarChange,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("navigation_bar_bitchord"),
                        title = { Text(stringResource(R.string.navigation_bar_bitchord)) },
                        description = stringResource(R.string.navigation_bar_bitchord_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        checked = navigationBarBitchord,
                        onCheckedChange = onBitchordChange,
                    )
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("hide_navigation_bar_labels"),
                        title = { Text(stringResource(R.string.hide_navigation_bar_labels)) },
                        description = stringResource(R.string.hide_navigation_bar_labels_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        checked = hideNavigationBarLabels,
                        onCheckedChange = onHideNavigationBarLabelsChange,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("navigation_bar_hide_on_scroll"),
                        title = { Text(stringResource(R.string.navigation_bar_hide_on_scroll)) },
                        description = stringResource(R.string.navigation_bar_hide_on_scroll_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        checked = hideOnScroll,
                        onCheckedChange = onHideOnScrollChange,
                    )
                }
            }

            // Customization sliders: only meaningfully affect the FLOATING style (and the
            // corner radius for DEFAULT). They are shown unconditionally so the user can
            // pre-configure the floating look before switching to it. Each slider opens a
            // dialog with a live preview that reflects the in-progress value (and the
            // committed values of the other dimensions) so the user can see exactly how
            // the bar will look before committing.
            PreferenceGroup(
                modifier = positions.modifierFor("navigation_bar_dimensions"),
                title = stringResource(R.string.navigation_bar_dimensions),
            ) {
                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_width),
                        description = stringResource(R.string.navigation_bar_width_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarWidth,
                        onValueChange = onNavigationBarWidthChange,
                        range = 0.5f..1.0f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_WIDTH_DEFAULT,
                        preview = { tempWidth ->
                            NavBarPreview(
                                widthFraction = tempWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = navigationBarOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = navigationBarCornerRadius,
                                style = navigationBarStyle,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_height),
                        description = stringResource(R.string.navigation_bar_height_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarHeight,
                        onValueChange = onNavigationBarHeightChange,
                        range = 0.8f..1.4f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_HEIGHT_DEFAULT,
                        preview = { tempHeight ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = tempHeight,
                                opacity = navigationBarOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = navigationBarCornerRadius,
                                style = navigationBarStyle,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_opacity),
                        description = stringResource(R.string.navigation_bar_opacity_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarOpacity,
                        onValueChange = onNavigationBarOpacityChange,
                        range = 0.2f..1.0f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_OPACITY_DEFAULT,
                        preview = { tempOpacity ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = tempOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = navigationBarCornerRadius,
                                style = navigationBarStyle,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_transparency),
                        description = stringResource(R.string.navigation_bar_transparency_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarTransparency,
                        onValueChange = onNavigationBarTransparencyChange,
                        range = 0.0f..0.95f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_TRANSPARENCY_DEFAULT,
                        preview = { tempTransparency ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = navigationBarOpacity,
                                transparency = tempTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = navigationBarCornerRadius,
                                style = navigationBarStyle,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_label_spacing),
                        description = stringResource(R.string.navigation_bar_label_spacing_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarLabelSpacing,
                        onValueChange = onNavigationBarLabelSpacingChange,
                        range = 0f..16f,
                        valueLabel = { "${it.roundToInt()} dp" },
                        default = NAVIGATION_BAR_LABEL_SPACING_DEFAULT,
                        preview = { tempSpacing ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = navigationBarOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = tempSpacing,
                                cornerRadius = navigationBarCornerRadius,
                                style = navigationBarStyle,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_corner_radius),
                        description = stringResource(R.string.navigation_bar_corner_radius_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarCornerRadius,
                        onValueChange = onNavigationBarCornerRadiusChange,
                        range = 0f..48f,
                        valueLabel = { "${it.roundToInt()} dp" },
                        default = NAVIGATION_BAR_CORNER_RADIUS_DEFAULT,
                        preview = { tempRadius ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = navigationBarOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = tempRadius,
                                style = navigationBarStyle,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                // Reset all six dimension values to their defaults in one tap. The button is
                // disabled (greyed out) when every value is already at its default, so the
                // user can see at a glance whether they have any unsaved customizations.
                // Also disabled when Liquid Glass nav bar is active (the Liquid Glass bar
                // uses SukiSU's exact dimensions and ignores the user's preferences, so
                // resetting them has no visible effect).
                item {
                    val allDefaults =
                        navigationBarWidth == NAVIGATION_BAR_WIDTH_DEFAULT &&
                            navigationBarHeight == NAVIGATION_BAR_HEIGHT_DEFAULT &&
                            navigationBarOpacity == NAVIGATION_BAR_OPACITY_DEFAULT &&
                            navigationBarTransparency == NAVIGATION_BAR_TRANSPARENCY_DEFAULT &&
                            navigationBarLabelSpacing == NAVIGATION_BAR_LABEL_SPACING_DEFAULT &&
                            navigationBarCornerRadius == NAVIGATION_BAR_CORNER_RADIUS_DEFAULT

                    OutlinedButton(
                        onClick = {
                            onNavigationBarWidthChange(NAVIGATION_BAR_WIDTH_DEFAULT)
                            onNavigationBarHeightChange(NAVIGATION_BAR_HEIGHT_DEFAULT)
                            onNavigationBarOpacityChange(NAVIGATION_BAR_OPACITY_DEFAULT)
                            onNavigationBarTransparencyChange(NAVIGATION_BAR_TRANSPARENCY_DEFAULT)
                            onNavigationBarLabelSpacingChange(NAVIGATION_BAR_LABEL_SPACING_DEFAULT)
                            onNavigationBarCornerRadiusChange(NAVIGATION_BAR_CORNER_RADIUS_DEFAULT)
                        },
                        enabled = !allDefaults && !liquidGlassNavBarEnabled,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.restore),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(R.string.navigation_bar_reset_dimensions))
                    }
                }
            }
        }
    }
}

/**
 * A preference row that opens a slider dialog when tapped. Mirrors the swipe-sensitivity UX used in
 * PlayerSettings / AppearanceSettings so all float-valued tuning knobs share the same interaction
 * model.
 */
@Composable
private fun SliderPreferenceRow(
    title: String,
    description: String,
    iconRes: Int,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    default: Float? = null,
    preview: (@Composable (Float) -> Unit)? = null,
    // SukiSU-Ultra: when the Liquid Glass nav bar is active, the customization
    // sliders are DISABLED (greyed out) because the Liquid Glass bar uses
    // SukiSU's exact dimensions and ignores the user's preferences. The user
    // explicitly asked for this: "Customisation of navigation bar in Liquid
    // Glass should be unavailable because it should use the exact same
    // dimensions from suki su for everything".
    enabled: Boolean = true,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    if (showDialog) {
        var tempValue by remember { mutableFloatStateOf(value) }

        DefaultDialog(
            onDismiss = {
                tempValue = value
                showDialog = false
            },
            buttons = {
                // Reset button — snaps the slider to the default value (or the range start
                // if no explicit default was supplied). Stays in the dialog so the user can
                // preview the default and then either confirm or keep adjusting.
                if (default != null) {
                    TextButton(
                        onClick = { tempValue = default },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        tempValue = value
                        showDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onValueChange(tempValue)
                        showDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Live preview — re-rendered on every tempValue change so the user sees
                // the effect of dragging the slider in real time. The preview lambda
                // receives tempValue and applies it to the dimension being adjusted,
                // while the other dimensions use their committed (saved) values.
                if (preview != null) {
                    Text(
                        text = stringResource(R.string.preview),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    preview(tempValue)
                    Spacer(modifier = Modifier.padding(top = 16.dp))
                }

                Text(
                    text = valueLabel(tempValue),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Slider(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.padding(top = 12.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    PreferenceEntry(
        title = { Text(title) },
        description = valueLabel(value),
        icon = { Icon(painterResource(iconRes), null) },
        onClick = { showDialog = true },
        isEnabled = enabled,
    )
}

/**
 * A miniature, self-contained mock of the floating / docked navigation bar used inside the
 * slider-dialog preview.
 */
@Composable
private fun NavBarPreview(
    widthFraction: Float,
    heightMultiplier: Float,
    opacity: Float,
    transparency: Float,
    labelSpacing: Float,
    cornerRadius: Float,
    style: NavigationBarStyle,
) {
    val isFloating = style == NavigationBarStyle.FLOATING
    val resolvedBarHeight = NavigationBarHeight * heightMultiplier
    val shape =
        if (isFloating) {
            RoundedCornerShape(cornerRadius.dp)
        } else {
            RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = cornerRadius.dp,
                bottomEnd = cornerRadius.dp,
            )
        }
    // Mirror the production color logic: opacity always applies; transparency only
    // applies when frosted blur is off (the preview never enables frost, so transparency
    // always applies here).
    val baseColor = MaterialTheme.colorScheme.surfaceContainer
    val effectiveAlpha = opacity * (1f - transparency)
    val barColor = baseColor.copy(alpha = effectiveAlpha.coerceIn(0.05f, 1f))
    val indicatorColor =
        if (isFloating) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }

    // Faux screen background: a vertical gradient from primary-tinted to surface-variant
    // so opacity / transparency changes in the bar are immediately visible against it.
    val fauxScreenBrush =
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(fauxScreenBrush),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(
                        bottom = if (isFloating) 16.dp else 0.dp,
                        start = if (isFloating) 16.dp else 0.dp,
                        end = if (isFloating) 16.dp else 0.dp,
                    ).fillMaxWidth(if (isFloating) widthFraction.coerceIn(0.5f, 1f) else 1f)
                    .height(resolvedBarHeight),
            shape = shape,
            color = barColor,
            tonalElevation = NavigationBarDefaults.Elevation,
            shadowElevation = if (isFloating) 8.dp else NavigationBarDefaults.Elevation,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val items = Screens.MainScreens
                items.forEachIndexed { index, screen ->
                    val selected = index == 0 // Home is always selected in the preview
                    val selectedColor = MaterialTheme.colorScheme.primary
                    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f),
                    ) {
                        // Indicator pill wraps just the icon (label sits outside, matching
                        // the production bar's layout).
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(if (selected) indicatorColor else Color.Transparent)
                                    .padding(horizontal = 18.dp, vertical = 7.dp),
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (selected) screen.iconIdActive else screen.iconIdInactive,
                                    ),
                                contentDescription = null,
                                tint = if (selected) selectedColor else unselectedColor,
                            )
                        }
                        Spacer(Modifier.height(labelSpacing.dp))
                        Text(
                            text = stringResource(screen.titleId),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) selectedColor else unselectedColor,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
