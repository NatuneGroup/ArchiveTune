/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.WindowInsets
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.constants.ArchiveTuneCanvasKey
import moe.rukamori.archivetune.constants.SpotifyCanvasKey
import moe.rukamori.archivetune.constants.AudioNormalizationKey
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.AutoDownloadOnLikeKey
import moe.rukamori.archivetune.constants.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.constants.AutoStartOnBluetoothKey
import moe.rukamori.archivetune.constants.BackdropEnabledKey
import moe.rukamori.archivetune.constants.CropThumbnailToSquareKey
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.CrossfadeGaplessKey
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.DisableScreenshotKey
import moe.rukamori.archivetune.constants.EnableVideoPlaybackKey
import moe.rukamori.archivetune.constants.EnablePipModeKey
import moe.rukamori.archivetune.constants.DynamicThemeKey
import moe.rukamori.archivetune.constants.EnableDiscordRPCKey
import moe.rukamori.archivetune.constants.EnableLastFMScrobblingKey
import moe.rukamori.archivetune.constants.EnableTranslatorKey
import moe.rukamori.archivetune.constants.ExternalDownloaderEnabledKey
import moe.rukamori.archivetune.constants.ForceHighRefreshRateKey
import moe.rukamori.archivetune.constants.HideAiMixKey
import moe.rukamori.archivetune.constants.HideStatusBarKey
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.constants.HidePlayerThumbnailKey
import moe.rukamori.archivetune.constants.HideScrollbarKey
import moe.rukamori.archivetune.constants.NavigationBarHideOnScrollKey
import moe.rukamori.archivetune.constants.HideSearchChromeWhileScrollingKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.SplashOverlayEnabledKey
import moe.rukamori.archivetune.constants.TikTokMainLyricsEnabledKey
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.ListenBrainzEnabledKey
import moe.rukamori.archivetune.constants.ListenTogetherSyncVolumeKey
import moe.rukamori.archivetune.constants.LowDataModeKey
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsScrollKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarBitchordKey
import moe.rukamori.archivetune.constants.NetworkMeteredKey
import moe.rukamori.archivetune.constants.PauseListenHistoryKey
import moe.rukamori.archivetune.constants.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.constants.PauseSearchHistoryKey
import moe.rukamori.archivetune.constants.PermanentShuffleKey
import moe.rukamori.archivetune.constants.PersistentQueueKey
import moe.rukamori.archivetune.constants.ProxyEnabledKey
import moe.rukamori.archivetune.constants.PureBlackKey
import moe.rukamori.archivetune.constants.RandomThemeOnStartupKey
import moe.rukamori.archivetune.constants.SeekExtraSeconds
import moe.rukamori.archivetune.constants.ShowHomeCategoryChipsKey
import moe.rukamori.archivetune.constants.ShowLyricsKey
import moe.rukamori.archivetune.constants.ShowPlayerVolumeBarKey
import moe.rukamori.archivetune.constants.ShowSpotifyPlaylistsKey
import moe.rukamori.archivetune.constants.SkipSilenceKey
import moe.rukamori.archivetune.constants.SmartTrimmerKey
import moe.rukamori.archivetune.constants.StopMusicOnTaskClearKey
import moe.rukamori.archivetune.constants.SyncPlaybackToYouTubeHistoryKey
import moe.rukamori.archivetune.constants.SwipeToSongKey
import moe.rukamori.archivetune.constants.TelegramLosslessOnlyKey
import moe.rukamori.archivetune.constants.TidalArtworkFallbackEnabledKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.constants.UsePoolAccountsKey
import moe.rukamori.archivetune.constants.TranslateLyricsKey
import moe.rukamori.archivetune.constants.UseLyricsV2Key
import moe.rukamori.archivetune.constants.UseSystemFontKey
import moe.rukamori.archivetune.constants.WakelockKey
import moe.rukamori.archivetune.utils.rememberPreference

@Composable
private fun SearchResultSwitch(
    key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    defaultValue: Boolean,
) {
    val (checked, onCheckedChange) = rememberPreference(key, defaultValue)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
fun buildSettingsGroups(
    navController: NavController,
    isAndroid12OrLater: Boolean,
    hasUpdate: Boolean,
    context: Context,
): List<SettingsGroup> {
    val account =
        SettingsItem(
            key = "account",
            icon = painterResource(R.drawable.ic_account),
            title = stringResource(R.string.account),
            subtitle = stringResource(R.string.settings_account_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("account", "profile", "youtube", "sign in", "login", "logout", "token", "hidden", "playlist", "channels", "switch account"),
            onClick = { navController.navigate("settings/account") },
            children = listOf(
                SettingsChild("Account switcher", "account_switcher", listOf("account switcher", "switch account", "multiple accounts", "saved accounts", "account channels")),
                SettingsChild("Hidden playlists", "hidden_playlists", listOf("hidden", "hidden playlists", "hide playlist", "hidden music", "private playlist")),
                SettingsChild("Tap to show tokens", "tap_to_show_tokens", listOf("token", "tokens", "show token", "po token", "innertube", "visitor data", "datasync", "credentials", "advanced login")),
                SettingsChild("Save current account", "save_current_account", listOf("save account", "remember account", "persist account")),
                SettingsChild("Logout", "account_logout", listOf("logout", "log out", "sign out", "disconnect")),
            ),
        )
    val stats =
        SettingsItem(
            key = "stats",
            icon = painterResource(R.drawable.stats),
            title = stringResource(R.string.settings_stats_title),
            subtitle = stringResource(R.string.settings_stats_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("stats", "statistics", "listening", "history", "top", "most played", "time"),
            onClick = { navController.navigate("stats") },
        )
    val listenTogether =
        SettingsItem(
            key = "listen_together",
            icon = painterResource(R.drawable.diversity_listen_together),
            title = stringResource(R.string.listen_together),
            subtitle = stringResource(R.string.setting_listen_together_desc),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("listen together", "room", "shared playback", "sync playback", "listen party", "group listening", "chat", "friends"),
            onClick = { navController.navigate("listen_together") },
        )
    val appearance =
        SettingsItem(
            key = "appearance",
            icon = painterResource(R.drawable.ic_palette),
            title = stringResource(R.string.appearance),
            subtitle = stringResource(R.string.settings_appearance_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("appearance", "theme", "dark", "light", "color", "palette", "style", "design"),
            onClick = { navController.navigate("settings/appearance") },
            children = listOf(
                SettingsChild("Dynamic theme", "dynamic_theme", listOf("dynamic theme", "material you", "dynamic color")) { SearchResultSwitch(DynamicThemeKey, false) },
                SettingsChild("Random theme on startup", "random_theme_on_startup", listOf("random theme", "random color", "shuffle theme")) { SearchResultSwitch(RandomThemeOnStartupKey, false) },
                SettingsChild("Dark theme", "dark_theme", listOf("dark", "dark theme", "night", "amoled")),
                SettingsChild("Pure black", "pure_black", listOf("pure black", "amoled", "oled", "black background")) { SearchResultSwitch(PureBlackKey, false) },
                SettingsChild("Color palette", "color_palette", listOf("color palette", "accent color", "theme color", "color")),
                SettingsChild("Color source", "color_source", listOf("color source", "color", "dynamic color", "material you")),
                SettingsChild("App icon", "app_icon", listOf("icon", "app icon", "icon pack", "launcher icon")),
                SettingsChild("Disable blur", "disable_blur", listOf("blur", "disable blur", "no blur", "performance")) { SearchResultSwitch(DisableBlurKey, false) },
                SettingsChild("Blur intensity", "blur_intensity", listOf("blur intensity", "blur amount", "blur level", "blur radius")),
                SettingsChild("Backdrop blur", "backdrop_blur", listOf("backdrop", "backdrop blur", "background blur", "frosted")) { SearchResultSwitch(BackdropEnabledKey, false) },
                SettingsChild("Font preference", "font_preference", listOf("font", "font style", "typography")),
                SettingsChild("Use system font", "use_system_font", listOf("system font", "default font", "roboto")) { SearchResultSwitch(UseSystemFontKey, false) },
                SettingsChild("Thumbnail corner radius", "thumbnail_corner_radius", listOf("thumbnail corner", "corner radius", "rounded thumbnail", "thumbnail shape")),
                SettingsChild("Crop thumbnail to square", "crop_thumbnail_to_square", listOf("crop thumbnail", "square thumbnail", "thumbnail crop")) { SearchResultSwitch(CropThumbnailToSquareKey, false) },
                SettingsChild("Enable canvas in albums page", "album_canvas_enabled", listOf("album canvas", "canvas in album", "album motion artwork", "album animated cover", "album header video")) { SearchResultSwitch(AlbumCanvasEnabledKey, true) },
                SettingsChild("Interface style", "interface_style", listOf("interface style", "design", "material you", "material 3", "expressive", "apple music", "apple music experience", "ios style", "apple tab bar")),
                SettingsChild("Library style", "library_style", listOf("library style", "apple music library", "library layout")),
                SettingsChild("Player design style", "player_design_style", listOf("player design", "player layout", "player style")),
                SettingsChild("Show lyrics on main player (TikTok)", "tiktok_main_lyrics", listOf("tiktok lyrics", "main player lyrics", "current line lyrics", "karaoke line", "tiktok player")) { SearchResultSwitch(TikTokMainLyricsEnabledKey, false) },
                SettingsChild("Player background style", "player_background_style", listOf("player background", "player bg", "background style")),
                SettingsChild("Lyrics background style", "lyrics_background_style", listOf("lyrics background", "lyrics bg")),
                SettingsChild("Mini player background style", "mini_player_background_style", listOf("mini player", "mini player background")),
                SettingsChild("Mini player layout", "compact_mini_player", listOf("compact mini player", "small mini player", "mini player layout")),
                SettingsChild("Player buttons style", "player_buttons_style", listOf("player buttons", "button style", "controls style")),
                SettingsChild("Player slider style", "player_slider_style", listOf("player slider", "slider style", "progress bar")),
                SettingsChild("Show player volume bar", "show_player_volume_bar", listOf("volume bar", "player volume", "volume slider")) { SearchResultSwitch(ShowPlayerVolumeBarKey, false) },
                SettingsChild("Hide player thumbnail", "hide_player_thumbnail", listOf("hide thumbnail", "player thumbnail", "hide artwork")) { SearchResultSwitch(HidePlayerThumbnailKey, false) },
                SettingsChild("Swipe to song", "swipe_to_song", listOf("swipe to song", "swipe next", "swipe track")) { SearchResultSwitch(SwipeToSongKey, false) },
                SettingsChild("Swipe sensitivity", "swipe_sensitivity", listOf("swipe", "gesture", "sensitivity")),
                SettingsChild("Disable animations", "disable_animations", listOf("animation", "disable animations", "no animations", "performance")) { SearchResultSwitch(DisableAnimationsKey, false) },
                SettingsChild("Splash screen animation", "splash_overlay_enabled", listOf("splash", "splash screen", "opening animation", "launch animation", "startup animation", "particles")) { SearchResultSwitch(SplashOverlayEnabledKey, true) },
                SettingsChild("Hide status bar", "hide_status_bar", listOf("status bar", "hide status", "immersive", "fullscreen", "hide bar")) { SearchResultSwitch(HideStatusBarKey, false) },
                SettingsChild("Force high refresh rate", "force_high_refresh_rate", listOf("refresh rate", "high refresh", "120hz", "90hz", "smooth")) { SearchResultSwitch(ForceHighRefreshRateKey, false) },
                SettingsChild("Navigation bar style", "navigation_bar_style", listOf("navigation bar", "nav bar", "bottom bar")),
                SettingsChild("Frosted navigation bar", "frosted_nav_bar", listOf("frosted nav", "frosted navigation", "frosted blur")) { SearchResultSwitch(NavigationBarFrostedBlurKey, false) },
                SettingsChild("BitChord navigation bar", "navigation_bar_bitchord", listOf("bitchord", "bottom bar", "pill bar", "navigation bar")) { SearchResultSwitch(NavigationBarBitchordKey, false) },
                SettingsChild("Liquid Glass navigation bar", "liquid_glass_nav_bar", listOf("liquid glass", "glass nav", "glass navigation", "liquid nav")) { SearchResultSwitch(LiquidGlassNavBarEnabledKey, false) },
                SettingsChild("Liquid Glass effects", "liquid_glass_effects", listOf("liquid glass", "glass effects", "liquid glass effects", "header glass", "mini player glass")) { SearchResultSwitch(LiquidGlassEnabledKey, false) },
                SettingsChild("Hide labels in navigation bar", "hide_navigation_bar_labels", listOf("hide labels", "navigation labels", "nav labels", "icons only")) { SearchResultSwitch(HideNavigationBarLabelsKey, false) },
                SettingsChild("Hide navigation bar while scrolling", "navigation_bar_hide_on_scroll", listOf("hide navigation bar", "hide nav bar", "scroll to hide", "auto hide bar")) { SearchResultSwitch(NavigationBarHideOnScrollKey, false) },
                SettingsChild("Navigation bar customization", "navigation_bar_settings", listOf("navigation bar", "nav bar dimensions", "nav bar opacity", "nav bar width", "nav bar height", "nav bar corner radius", "nav bar label spacing")),
                SettingsChild("Hide scrollbar", "hide_scrollbar", listOf("scrollbar", "scroll bar", "hide scroll", "no scrollbar")) { SearchResultSwitch(HideScrollbarKey, false) },
                SettingsChild("Default open tab", "default_open_tab", listOf("default tab", "home tab", "start page", "open tab")),
                SettingsChild("Search bar position", "search_bar_position", listOf("search bar", "bottom search", "search at bottom", "search position")),
                SettingsChild("Hide search bar while scrolling", "hide_search_bar_while_scrolling", listOf("hide search bar", "search scrolling", "auto hide search")) { SearchResultSwitch(HideSearchChromeWhileScrollingKey, false) },
                SettingsChild("Grid layout", "grid_layout", listOf("grid", "layout", "list view", "artist grid")),
                SettingsChild("Show home category chips", "show_home_category_chips", listOf("home chips", "category chips", "home category", "chips")) { SearchResultSwitch(ShowHomeCategoryChipsKey, false) },
                SettingsChild("Language", "app_language", listOf("language", "app language", "locale")),
                SettingsChild("UI scale", "ui_scale", listOf("ui scale", "scale", "zoom", "interface size", "display size", "bigger", "smaller")),
                SettingsChild("Custom font", "custom_font", listOf("custom font", "font file", "typeface", "own font")),
                SettingsChild("Backdrop blur amount", "backdrop_blur_amount", listOf("backdrop blur amount", "backdrop intensity", "background blur amount")),
                SettingsChild("Customized background", "customized_background", listOf("customized background", "custom background", "background image", "wallpaper")),
                SettingsChild("Tablet mode", "tablet_mode", listOf("tablet mode", "tablet", "large screen", "landscape layout")),
                SettingsChild("Minimal mode", "minimal_home_mode", listOf("minimal mode", "minimal home", "simple home", "clean home")),
                SettingsChild("Home screen style", "home_screen_style", listOf("home style", "home screen", "youtube home", "home layout")),
                SettingsChild("Change default library chip", "default_lib_chips", listOf("library chip", "default chip", "library filter", "default library tab")),
                SettingsChild("Liquid Glass effects", "liquid_glass_effects", listOf("liquid glass", "glass effects", "header glass", "mini player glass")),
                SettingsChild("Theme creator", "theme_creator", listOf("theme creator", "create theme", "custom theme", "make theme")),
                SettingsChild("Palette picker", "palette_picker", listOf("palette picker", "pick palette", "choose palette", "custom palette")),
                SettingsChild("Extras", "extras", listOf("extras", "appearance extras", "home cards", "hide cards", "more appearance")),
            ),
        )
    // Appearance → Extras sub-page. Hidden from the main list (it is reached through
    // Appearance) but every toggle on it stays searchable.
    val appearanceExtras =
        SettingsItem(
            key = "appearance_extras",
            icon = painterResource(R.drawable.palette),
            title = "Appearance extras",
            subtitle = "Home and library card visibility",
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("extras", "appearance extras", "home cards", "hide cards", "library cards", "quick picks cards"),
            onClick = { navController.navigate("settings/appearance/extras") },
            hidden = true,
            children = listOf(
                SettingsChild("Show home category chips", "show_home_category_chips", listOf("home chips", "category chips", "home category", "chips")) { SearchResultSwitch(ShowHomeCategoryChipsKey, false) },
                SettingsChild("Show tags in library", "show_tags_in_library", listOf("tags", "library tags", "show tags")),
                SettingsChild("Hide Liked songs card", "hide_liked_songs_card", listOf("hide liked songs", "liked songs card", "favourites card", "hide card")),
                SettingsChild("Hide Offline card", "hide_offline_card", listOf("hide offline", "offline card", "downloaded card", "hide card")),
                SettingsChild("Hide Cached card", "hide_cached_card", listOf("hide cached", "cached card", "cache card", "hide card")),
                SettingsChild("Hide Local Files card", "hide_local_files_card", listOf("hide local files", "local files card", "local card", "hide card")),
                SettingsChild("Hide My top 50 card", "hide_top50_card", listOf("hide top 50", "top 50 card", "my top 50", "hide card")),
            ),
        )
    // Appearance → AOD customization. Entirely absent from the search index before,
    // so none of these 17 settings could be found by name.
    val aodCustomization =
        SettingsItem(
            key = "aod",
            icon = painterResource(R.drawable.palette),
            title = "AOD customization",
            subtitle = "Always-on display layout and style",
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("aod", "always on display", "always-on display", "lockscreen", "screensaver", "idle screen", "ambient display"),
            onClick = { navController.navigate("settings/appearance/aod_customized") },
            hidden = true,
            children = listOf(
                SettingsChild("Show thumbnail", "aod_customize_show_thumbnail", listOf("aod thumbnail", "always on display artwork", "aod cover", "aod show thumbnail")),
                SettingsChild("Show artist", "aod_customize_show_artist", listOf("aod artist", "always on display artist", "aod show artist")),
                SettingsChild("Show album", "aod_customize_show_album", listOf("aod album", "always on display album", "aod show album")),
                SettingsChild("Show progress", "aod_customize_show_progress", listOf("aod progress", "aod progress bar", "always on display progress")),
                SettingsChild("Show time labels", "aod_customize_show_time_labels", listOf("aod time", "aod timestamps", "aod time labels", "always on display time")),
                SettingsChild("Show controls", "aod_customize_show_controls", listOf("aod controls", "aod buttons", "always on display controls")),
                SettingsChild("Show exit button", "aod_customize_show_exit_button", listOf("aod exit", "aod close button", "leave aod")),
                SettingsChild("Show lyrics", "aod_customize_show_lyrics", listOf("aod lyrics", "always on display lyrics", "aod show lyrics")),
                SettingsChild("Background style", "aod_customize_background_style", listOf("aod background", "aod background style", "always on display background")),
                SettingsChild("Accent style", "aod_customize_accent_style", listOf("aod accent", "aod accent style", "aod color")),
                SettingsChild("Content position", "aod_customize_content_position", listOf("aod position", "aod content position", "aod layout")),
                SettingsChild("Text alignment", "aod_customize_text_alignment", listOf("aod text alignment", "aod align", "aod centre", "aod center")),
                SettingsChild("Slider style", "aod_customize_slider_style", listOf("aod slider", "aod slider style", "aod progress style")),
                SettingsChild("Artwork glow", "aod_customize_artwork_glow", listOf("aod glow", "artwork glow", "aod artwork glow", "ambient glow")),
                SettingsChild("Control style", "aod_customize_control_style", listOf("aod control style", "aod button style")),
                SettingsChild("Enter AOD when screen dims", "aod_customize_auto_on_screen_dim", listOf("auto aod", "aod on dim", "automatic aod", "screen dim aod")),
            ),
        )
    // Appearance → Navigation bar customization.
    val navigationBar =
        SettingsItem(
            key = "navigation_bar",
            icon = painterResource(R.drawable.palette),
            title = "Navigation bar",
            subtitle = "Navigation bar style and dimensions",
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("navigation bar", "nav bar", "bottom bar", "tab bar", "navbar"),
            onClick = { navController.navigate("settings/appearance/navigation_bar") },
            hidden = true,
            children = listOf(
                SettingsChild("Navigation bar style", "navigation_bar_style", listOf("navigation bar style", "nav bar style", "bottom bar style")),
                SettingsChild("Bottom pills layout", "compact_bottom_pills", listOf("compact pills", "compact navigation", "compact bottom bar", "bottom pills layout")),
                SettingsChild("Frosted navigation bar", "navigation_bar_frosted_blur", listOf("frosted nav", "frosted navigation", "frosted blur")) { SearchResultSwitch(NavigationBarFrostedBlurKey, false) },
                SettingsChild("Tint frosted navigation bar", "navigation_bar_tint_frosted_blur", listOf("tint frosted", "tint nav bar", "frosted tint", "coloured nav bar")),
                SettingsChild("BitChord navigation bar", "navigation_bar_bitchord", listOf("bitchord", "bottom bar", "pill bar", "navigation bar")) { SearchResultSwitch(NavigationBarBitchordKey, false) },
                SettingsChild("Liquid Glass navigation bar", "liquid_glass_nav_bar", listOf("liquid glass nav", "glass navigation", "liquid nav")) { SearchResultSwitch(LiquidGlassNavBarEnabledKey, false) },
                SettingsChild("Hide labels in navigation bar", "hide_navigation_bar_labels", listOf("hide labels", "navigation labels", "nav labels", "icons only")) { SearchResultSwitch(HideNavigationBarLabelsKey, false) },
                SettingsChild("Hide navigation bar while scrolling", "navigation_bar_hide_on_scroll", listOf("hide navigation bar", "hide nav bar", "scroll to hide", "auto hide bar")) { SearchResultSwitch(NavigationBarHideOnScrollKey, false) },
                SettingsChild("Navigation bar dimensions", "navigation_bar_dimensions", listOf("nav bar height", "nav bar width", "nav bar opacity", "nav bar corner radius", "nav bar label spacing", "nav bar size")),
            ),
        )
    // Appearance → Lyrics animations.
    val lyricsAnimations =
        SettingsItem(
            key = "lyrics_animations",
            icon = painterResource(R.drawable.lyrics),
            title = "Lyrics animations",
            subtitle = "Lyrics motion and transitions",
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("lyrics animation", "lyrics animations", "lyrics motion", "lyrics transition", "karaoke animation"),
            onClick = { navController.navigate("settings/appearance/lyrics_animations") },
            hidden = true,
            children = listOf(
                SettingsChild("Lyrics animation style", "lyrics_animation_style", listOf("lyrics animation style", "lyrics motion", "lyrics transition")),
                SettingsChild("Lyrics scale animation", "lyrics_scale_animation", listOf("lyrics scale", "lyrics zoom", "lyrics grow")),
                SettingsChild("Lyrics glow animation", "lyrics_glow_animation", listOf("lyrics glow", "lyrics shine", "lyrics highlight")),
                SettingsChild("Lyrics fade animation", "lyrics_fade_animation", listOf("lyrics fade", "lyrics opacity animation")),
            ),
        )
    val playback =
        SettingsItem(
            key = "playback",
            icon = painterResource(R.drawable.music_note),
            title = stringResource(R.string.settings_playback_title),
            subtitle = stringResource(R.string.settings_playback_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("playback", "player", "audio", "quality", "equalizer", "eq", "volume", "crossfade", "gapless", "flac", "lossless", "hi-res", "sample rate", "bitrate", "video", "music video", "video playback", "pip", "picture in picture", "floating", "minimize"),
            onClick = { navController.navigate("settings/player") },
            children = listOf(
                SettingsChild("Low data mode", "low_data_mode", listOf("data", "data saver", "low quality", "data mode")) { SearchResultSwitch(LowDataModeKey, true) },
                SettingsChild("Enable video playback", "enable_video_playback", listOf("video", "music video", "mv", "video playback", "captions", "subtitles")) { SearchResultSwitch(EnableVideoPlaybackKey, false) },
                SettingsChild("Enable PiP mode", "enable_pip_mode", listOf("pip", "picture in picture", "floating video", "minimize", "pop out", "overlay")) { SearchResultSwitch(EnablePipModeKey, false) },
                SettingsChild("History duration", "history_duration", listOf("history", "duration", "recent", "queue length")),
                SettingsChild("Crossfade", "crossfade", listOf("crossfade", "fade", "transition", "mix", "blend")) { SearchResultSwitch(CrossfadeEnabledKey, false) },
                SettingsChild("Crossfade gapless", "crossfade_gapless", listOf("crossfade gapless", "gapless crossfade", "seamless crossfade")) { SearchResultSwitch(CrossfadeGaplessKey, true) },
                SettingsChild("Skip silence", "skip_silence", listOf("silence", "skip silence", "blank", "quiet")) { SearchResultSwitch(SkipSilenceKey, false) },
                SettingsChild("Audio normalization", "audio_normalization", listOf("normalization", "loudness", "normalize", "volume level")) { SearchResultSwitch(AudioNormalizationKey, true) },
                SettingsChild("Audio offload", "audio_offload", listOf("offload", "audio offload", "hardware decoder")) { SearchResultSwitch(AudioOffload, false) },
                SettingsChild("Seek seconds add-up", "seek_seconds", listOf("seek", "skip", "forward", "rewind", "seconds")) { SearchResultSwitch(SeekExtraSeconds, false) },
                SettingsChild("Pause on device mute", "pause_mute", listOf("mute", "pause mute", "headphone", "silence detect")) { SearchResultSwitch(PauseOnDeviceMuteKey, false) },
                SettingsChild("Device mute recovery volume", "device_mute_recovery_volume", listOf("recovery volume", "mute recovery", "volume restore")),
                SettingsChild("Auto start on Bluetooth", "bluetooth_auto_start", listOf("bluetooth", "auto start", "auto play", "connect")) { SearchResultSwitch(AutoStartOnBluetoothKey, false) },
                SettingsChild("ArchiveTune Canvas", "archive_tune_canvas", listOf("canvas", "animated artwork", "motion artwork", "live artwork")) { SearchResultSwitch(ArchiveTuneCanvasKey, true) },
                SettingsChild("Spotify Canvas", "spotify_canvas", listOf("spotify", "canvas", "spotify canvas", "looping video", "music video", "video artwork")) { SearchResultSwitch(SpotifyCanvasKey, false) },
                SettingsChild("Canvas resolvers", "canvas_resolvers", listOf("canvas resolver", "canvas resolvers", "canvas endpoint", "canvas fallback", "spotify canvas resolver")),
                SettingsChild("Tidal artwork fallback", "tidal_artwork_fallback", listOf("tidal artwork", "artwork fallback", "tidal cover", "hi-res artwork")) { SearchResultSwitch(TidalArtworkFallbackEnabledKey, true) },
                SettingsChild("Persistent queue", "persistent_queue", listOf("queue", "persistent", "save queue", "resume")) { SearchResultSwitch(PersistentQueueKey, true) },
                SettingsChild("Permanent shuffle", "permanent_shuffle", listOf("shuffle", "random", "permanent")) { SearchResultSwitch(PermanentShuffleKey, false) },
                SettingsChild("Auto skip on error", "auto_skip_error", listOf("skip", "error", "auto skip", "failed")) { SearchResultSwitch(AutoSkipNextOnErrorKey, false) },
                SettingsChild("Stop music on task clear", "stop_task_clear", listOf("stop", "task clear", "background", "close app")) { SearchResultSwitch(StopMusicOnTaskClearKey, false) },
                SettingsChild("Wakelock", "wakelock", listOf("wakelock", "wake lock", "keep awake", "cpu")) { SearchResultSwitch(WakelockKey, false) },
                SettingsChild("Artist separators", "artist_separators", listOf("artist", "separator", "split", "featuring")),
                SettingsChild("Manage playlist tags", "manage_playlist_tags", listOf("playlist tags", "tag management", "organize playlists")),
                SettingsChild("Audio quality", "audio_quality", listOf("audio quality", "quality", "bitrate", "sound quality", "streaming quality", "high quality")),
                SettingsChild("Artwork priority", "artwork_priority", listOf("artwork priority", "artwork order", "cover priority", "artwork provider order", "artwork source order")),
                SettingsChild("Preferred sources", "preferred_sources", listOf("preferred sources", "source priority", "source order", "audio source order", "which source first")),
                SettingsChild("Auto choose playback client", "auto_choose_playback_client", listOf("auto choose client", "playback client auto", "automatic client", "client selection")),
                SettingsChild("Playback client", "player_stream_client", listOf("playback client", "stream client", "player client", "innertube client", "android vr", "ios client", "web client")),
                SettingsChild("Skip gapless albums", "crossfade_gapless_title", listOf("skip gapless albums", "gapless album", "gapless")),
                SettingsChild("Progressive seek", "seek_seconds_addup", listOf("progressive seek", "seek add up", "seek accumulate", "double tap seek")),
                SettingsChild("Enable swipe to change song", "enable_swipe_thumbnail", listOf("swipe thumbnail", "swipe to change song", "swipe artwork", "swipe track")),
                SettingsChild("Mini player swipe sensitivity", "swipe_sensitivity", listOf("swipe sensitivity", "mini player swipe", "gesture sensitivity")),
                SettingsChild("Check source", "check_source", listOf("check source", "source health", "test source", "source diagnostics", "verify source", "source status")),
                SettingsChild("Spotify catalog", "spotify_catalog_source", listOf("spotify catalog", "spotify metadata", "spotify source")),
                SettingsChild("Enable Tidal source", "tidal_enable", listOf("tidal", "enable tidal", "tidal source", "lossless", "hifi")),
                SettingsChild("Use my Tidal account first", "tidal_account_first", listOf("tidal account first", "my tidal account", "prefer my account")),
                SettingsChild("Tidal audio quality", "tidal_audio_quality", listOf("tidal quality", "tidal audio quality", "tidal hifi", "tidal max", "mqa")),
                SettingsChild("Tidal animated covers", "tidal_animated_covers", listOf("tidal animated covers", "tidal canvas", "tidal video cover", "animated cover")),
                SettingsChild("Manage Tidal instances", "tidal_manage_instances", listOf("tidal instances", "tidal server", "tidal endpoint", "manage instances")),
                SettingsChild("Enable Qobuz source", "qobuz_enable", listOf("qobuz", "enable qobuz", "qobuz source", "hi-res", "flac")),
                SettingsChild("Qobuz audio quality", "qobuz_audio_quality", listOf("qobuz quality", "qobuz audio quality", "hi-res", "flac", "cd quality", "24 bit")),
                SettingsChild("Enable Qobuz backup server", "qobuz_backup_enable", listOf("qobuz backup", "backup server", "qobuz backup server", "lossless backup", "fallback server", "kouzu")),
                SettingsChild("Manage Qobuz instances", "qobuz_manage_instances", listOf("qobuz instances", "qobuz server", "qobuz endpoint", "manage instances")),
                SettingsChild("Enable Deezer source", "deezer_enable", listOf("deezer", "enable deezer", "deezer source", "flac")),
                SettingsChild("Deezer audio quality", "deezer_audio_quality", listOf("deezer quality", "deezer audio quality", "deezer flac")),
                SettingsChild("Enable JioSaavn source", "jiosaavn_enable", listOf("jiosaavn", "jio saavn", "saavn", "enable jiosaavn", "indian music")),
                SettingsChild("JioSaavn audio quality", "jiosaavn_audio_quality", listOf("jiosaavn quality", "saavn quality", "jiosaavn audio quality")),
                SettingsChild("yt-dlp runtime", "ytdlp", listOf("yt-dlp", "ytdlp", "youtube-dl", "extractor", "downloader runtime", "yt dlp version")),
            ),
        )
    val canvas =
        SettingsItem(
            key = "canvas",
            icon = painterResource(R.drawable.motion_photos_on),
            title = stringResource(R.string.archivetune_canvas),
            subtitle = stringResource(R.string.canvas_settings_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("canvas", "animated artwork", "motion artwork", "live artwork", "video artwork", "wifi", "metered", "cache"),
            onClick = { navController.navigate("settings/canvas") },
        )
    // Sources → JioSaavn sub-page.
    val jioSaavn =
        SettingsItem(
            key = "jiosaavn",
            icon = painterResource(R.drawable.provider_tidal),
            title = "JioSaavn",
            subtitle = "JioSaavn audio source",
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("jiosaavn", "jio saavn", "saavn", "indian music", "bollywood", "vivimusic"),
            onClick = { navController.navigate("settings/jiosaavn") },
            hidden = true,
            children = listOf(
                SettingsChild("Enable JioSaavn source", "jiosaavn_enable", listOf("enable jiosaavn", "jiosaavn source", "turn on jiosaavn")),
                SettingsChild("JioSaavn audio quality", "jiosaavn_audio_quality", listOf("jiosaavn quality", "saavn audio quality", "jiosaavn bitrate")),
                SettingsChild("JioSaavn credit", "jiosaavn_credit", listOf("jiosaavn credit", "vivimusic", "jiosaavn about")),
            ),
        )
    // Sources → Amazon Music sub-page.
    val amazon =
        SettingsItem(
            key = "amazon",
            icon = painterResource(R.drawable.login),
            title = "Amazon Music",
            subtitle = "Amazon Music account and audio source",
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("amazon", "amazon music", "amazon login", "amazon hd", "amazon ultra hd"),
            onClick = { navController.navigate("settings/amazon") },
            hidden = true,
            children = listOf(
                SettingsChild("Amazon instances", "amazon_instances", listOf("amazon instance", "amazon url", "amazon server")),
                SettingsChild("Authorize with Turnstile", "amazon_authorize", listOf("amazon authorize", "amazon turnstile", "amazon login")),
                SettingsChild("Amazon bypass token", "amazon_bypass_token", listOf("amazon bypass", "amazon token")),
                SettingsChild("Amazon audio quality", "amazon_audio_quality", listOf("amazon quality", "amazon hd", "amazon ultra hd")),
            ),
        )
    // Sources → QQ Music sub-page. The screen carries the QR sign-in as well as the source's own
    // switches, so the account row is indexed too: searching for a sign-in should reach it.
    val qqMusic =
        SettingsItem(
            key = "qqmusic",
            icon = painterResource(R.drawable.ic_music),
            title = "QQ Music",
            subtitle = "QQ Music account and audio source",
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("qq", "qq music", "qqmusic", "tencent", "tme", "qr login", "sign in"),
            onClick = { navController.navigate("settings/qqmusic") },
            hidden = true,
            children = listOf(
                SettingsChild("QQ Music account", "qq_music_account", listOf("qq music login", "qq music sign in", "qr code", "scan code")),
                SettingsChild("Enable QQ Music", "qq_music_enabled", listOf("qq music enable", "turn on qq music")),
                SettingsChild("QQ Music quality", "qq_music_quality", listOf("qq quality", "qq lossless", "qq flac")),
            ),
        )
    // Sources → Deezer sub-page.
    val deezer =
        SettingsItem(
            key = "deezer",
            icon = painterResource(R.drawable.provider_tidal),
            title = "Deezer",
            subtitle = "Deezer account and audio source",
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("deezer", "deezer account", "deezer login", "arl", "deezer premium", "flac"),
            onClick = { navController.navigate("settings/deezer") },
            hidden = true,
            children = listOf(
                SettingsChild("Sign in to Deezer", "deezer_login", listOf("deezer login", "deezer sign in", "connect deezer", "deezer arl")),
                SettingsChild("Sign out of Deezer", "deezer_sign_out", listOf("deezer logout", "deezer sign out", "disconnect deezer")),
                SettingsChild("Enable Deezer source", "deezer_enable", listOf("enable deezer", "deezer source", "turn on deezer")),
                SettingsChild("Deezer audio quality", "deezer_audio_quality", listOf("deezer quality", "deezer audio quality", "deezer flac")),
            ),
        )
    val sources =
        SettingsItem(
            key = "sources",
            icon = painterResource(R.drawable.ic_music),
            title = stringResource(R.string.source_settings),
            subtitle = stringResource(R.string.source_settings_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("source", "music source", "youtube music", "spotify", "metadata source", "search source", "tidal", "qobuz", "provider", "streaming", "telegram", "telegram channel", "flac", "lossless", "private channel"),
            onClick = { navController.navigate("settings/sources") },
            // Playback sub-page anchor, kept in the search index so existing search shortcuts
            // still work.
            hidden = true,
            children = listOf(
                SettingsChild("YouTube Music", "youtube_music", listOf("youtube", "youtube music", "yt music")),
                SettingsChild("Default metadata source", "default_metadata_source", listOf("metadata", "spotify", "catalog", "artwork")),
                SettingsChild("Default search source", "default_search_source", listOf("search", "spotify", "youtube", "provider")),
                SettingsChild("Qobuz", "qobuz", listOf("qobuz", "hires", "hi-res", "flac", "lossless", "cd quality")),
                SettingsChild("Tidal", "tidal", listOf("tidal", "lossless", "hifi", "master", "mq")),
            ),
        )
    val lyrics =
        SettingsItem(
            key = "lyrics",
            icon = painterResource(R.drawable.ic_lyrics),
            title = stringResource(R.string.lyrics),
            subtitle = stringResource(R.string.settings_lyrics_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("lyrics", "lyric", "subtitle", "text", "sing along", "lrc", "translation", "romanize", "karaoke"),
            onClick = { navController.navigate("settings/lyrics") },
            // Playback sub-page anchor, kept in the search index so existing search shortcuts
            // still work.
            hidden = true,
            children = listOf(
                SettingsChild("Lyrics provider", "lyrics_provider", listOf("lyrics provider", "source", "lrclib", "kugou", "netease", "musixmatch", "paxsenix", "betterlyrics", "portato", "youlyplus", "unison", "simpmusic", "megalobiz")),
                SettingsChild("Lyrics mode", "lyrics_mode", listOf("lyrics mode", "lyrics style", "lyrics display mode", "karaoke mode")),
                SettingsChild("Show lyrics", "show_lyrics", listOf("show lyrics", "display lyrics", "lyrics toggle", "lyrics show")) { SearchResultSwitch(ShowLyricsKey, false) },
                SettingsChild("Use lyrics V2", "use_lyrics_v2", listOf("lyrics v2", "new lyrics", "lyrics engine")) { SearchResultSwitch(UseLyricsV2Key, true) },
                SettingsChild("Translate lyrics", "translate_lyrics", listOf("translate", "translation", "lyrics translation")) { SearchResultSwitch(TranslateLyricsKey, false) },
                SettingsChild("Enable translator", "enable_translator", listOf("translator", "translation engine", "lyrics translator")) { SearchResultSwitch(EnableTranslatorKey, false) },
                SettingsChild("Lyrics font size", "lyrics_font_size", listOf("font size", "lyrics size", "text size", "lyrics text size")),
                SettingsChild("Lyrics line spacing", "lyrics_line_spacing", listOf("line spacing", "lyrics spacing", "lyrics line gap", "lyrics padding")),
                SettingsChild("Lyrics animations", "lyrics_animations", listOf("animation", "animated lyrics", "lyrics effect")),
                SettingsChild("Lyrics animation style", "lyrics_animation_style", listOf("animation style", "lyrics animation", "lyrics motion", "lyrics transition")),
                SettingsChild("Lyrics line blur", "lyrics_line_blur", listOf("lyrics blur", "line blur", "focus blur")),
                SettingsChild("Lyrics romanize Japanese", "lyrics_romanize_japanese", listOf("romanize", "japanese", "romaji", "furigana")),
                SettingsChild("Lyrics romanize Korean", "lyrics_romanize_korean", listOf("romanize", "korean", "romanization")),
                SettingsChild("Lyrics romanize Chinese", "lyrics_romanize_chinese", listOf("romanize", "chinese", "pinyin")),
                SettingsChild("Lyrics romanize Hindi", "lyrics_romanize_hindi", listOf("romanize", "hindi", "devanagari")),
                SettingsChild("Lyrics romanize other languages", "lyrics_romanize_other", listOf("romanize", "other languages", "arabic", "thai", "cyrillic")),
                SettingsChild("Lyrics click to seek", "lyrics_click", listOf("click lyrics", "tap lyrics", "seek lyrics")) { SearchResultSwitch(LyricsClickKey, false) },
                SettingsChild("Lyrics auto-scroll", "lyrics_scroll", listOf("scroll", "auto scroll", "lyrics scroll")) { SearchResultSwitch(LyricsScrollKey, true) },
                SettingsChild("Preload queue lyrics", "preload_queue_lyrics", listOf("preload", "preload lyrics", "queue lyrics", "preload count", "queue lyrics count", "preload amount", "preload size")),
                SettingsChild("Lyrics background style", "lyrics_background_style", listOf("lyrics background", "lyrics bg")),
                SettingsChild("BetterLyrics", "betterlyrics", listOf("betterlyrics", "better lyrics", "better lyrics provider")),
                SettingsChild("BetterLyrics Portato", "betterlyrics_portato", listOf("portato", "betterlyrics portato", "portato lyrics")),
                SettingsChild("YouLyPlus Lyrics", "youlyplus_lyrics", listOf("youlyplus", "youlyplus lyrics", "youly plus")),
                SettingsChild("LRCLIB", "lrclib", listOf("lrclib", "lrclib lyrics", "lrclib provider")),
                SettingsChild("Kugou Lyrics", "kugou", listOf("kugou", "kugou lyrics", "kugou provider", "kugou music")),
                SettingsChild("Unison Lyrics", "unison_lyrics", listOf("unison", "unison lyrics", "unison provider")),
                SettingsChild("Simpmusic Lyrics", "simpmusic_lyrics", listOf("simpmusic", "simpmusic lyrics", "simpmusic provider")),
                SettingsChild("Megalobiz Lyrics", "megalobiz_lyrics", listOf("megalobiz", "megalobiz lyrics", "megalobiz provider")),
                SettingsChild("Paxsenix Lyrics", "paxsenix_lyrics", listOf("paxsenix", "paxsenix lyrics", "paxsenix provider")),
                SettingsChild("Paxsenix Stats", "paxsenix_stats", listOf("paxsenix stats", "paxsenix statistics", "paxsenix analytics")),
                SettingsChild("First lyrics provider", "first_lyrics_provider", listOf("first lyrics", "lyrics priority", "primary lyrics provider", "lyrics order")),
                SettingsChild("Preferred lyrics provider", "set_first_lyrics_provider", listOf("preferred lyrics provider", "default lyrics provider", "lyrics priority")),
                SettingsChild("Prioritize word synced lyrics", "prioritize_word_synced_lyrics", listOf("word synced", "word by word", "karaoke lyrics", "prioritize word synced")),
                SettingsChild("Providers", "providers", listOf("lyrics providers", "providers", "lyrics sources", "which lyrics provider")),
                SettingsChild("Romanization", "romanization", listOf("romanization", "romanisation", "romanize", "romaji", "transliteration")),
                SettingsChild("Language packs", "language_packs", listOf("language pack", "language packs", "romanization data", "dictionary")),
                SettingsChild("Enable Tidal lyrics", "enable_tidal_lyrics", listOf("tidal lyrics", "enable tidal lyrics", "tidal lyric provider")),
                SettingsChild("Enable Deezer lyrics", "enable_deezer_lyrics", listOf("deezer lyrics", "enable deezer lyrics", "deezer lyric provider")),
                SettingsChild("Musixmatch (experimental)", "enable_musixmatch_experimental", listOf("musixmatch", "musixmatch experimental", "musixmatch lyrics")),
                SettingsChild("Paxsenix API key", "paxsenix_api_key", listOf("paxsenix api key", "paxsenix key", "paxsenix token")),
                SettingsChild("Paxsenix endpoint", "paxsenix_endpoint", listOf("paxsenix endpoint", "paxsenix url", "paxsenix server")),
                SettingsChild("Lyrics text size", "lyrics_text_size", listOf("lyrics text size", "lyrics font size", "lyrics size", "bigger lyrics")),
            ),
        )
    // Lyrics → Providers sub-page.
    val lyricsProviders =
        SettingsItem(
            key = "lyrics_providers",
            icon = painterResource(R.drawable.lyrics),
            title = "Lyrics providers",
            subtitle = "Enable and prioritise lyrics sources",
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("lyrics provider", "lyrics providers", "lyrics source", "lrclib", "kugou", "musixmatch", "betterlyrics", "paxsenix", "youlyplus", "unison", "simpmusic", "megalobiz"),
            onClick = { navController.navigate("settings/lyrics/providers") },
            hidden = true,
            children = listOf(
                SettingsChild("Prioritize word synced lyrics", "prioritize_word_synced_lyrics", listOf("word synced", "word by word", "karaoke lyrics")),
                SettingsChild("Enable BetterLyrics", "enable_betterlyrics", listOf("betterlyrics", "better lyrics", "enable betterlyrics")),
                SettingsChild("Enable BetterLyrics Portato", "enable_betterlyrics_portato", listOf("portato", "betterlyrics portato", "portato lyrics")),
                SettingsChild("Enable YouLyPlus lyrics", "enable_youlyplus_lyrics", listOf("youlyplus", "youly plus", "youlyplus lyrics")),
                SettingsChild("Enable LrcLib lyrics provider", "enable_lrclib", listOf("lrclib", "lrc lib", "lrclib lyrics")),
                SettingsChild("Enable KuGou lyrics provider", "enable_kugou", listOf("kugou", "kugou lyrics", "chinese lyrics")),
                SettingsChild("Enable Unison lyrics", "enable_unison_lyrics", listOf("unison", "unison lyrics")),
                SettingsChild("Enable SimpMusic lyrics", "enable_simpmusic_lyrics", listOf("simpmusic", "simp music", "simpmusic lyrics")),
                SettingsChild("Enable Megalobiz lyrics", "enable_megalobiz_lyrics", listOf("megalobiz", "megalobiz lyrics")),
                SettingsChild("Enable Paxsenix lyrics", "enable_paxsenix_lyrics", listOf("paxsenix", "paxsenix lyrics")),
                SettingsChild("Enable Tidal lyrics", "enable_tidal_lyrics", listOf("tidal lyrics", "enable tidal lyrics")),
                SettingsChild("Enable Deezer lyrics", "enable_deezer_lyrics", listOf("deezer lyrics", "enable deezer lyrics")),
                SettingsChild("Musixmatch (experimental)", "enable_musixmatch_experimental", listOf("musixmatch", "musixmatch experimental")),
                SettingsChild("Paxsenix API stats", "paxsenix_stats", listOf("paxsenix stats", "paxsenix usage", "paxsenix quota")),
                SettingsChild("Paxsenix API key", "paxsenix_api_key", listOf("paxsenix api key", "paxsenix key")),
                SettingsChild("Paxsenix endpoint", "paxsenix_endpoint", listOf("paxsenix endpoint", "paxsenix url")),
                SettingsChild("Check Paxsenix endpoints", "paxsenix_check_endpoints", listOf("check paxsenix", "paxsenix endpoints", "test paxsenix", "paxsenix status", "endpoint check")),
                SettingsChild("Preferred lyrics provider", "set_first_lyrics_provider", listOf("preferred lyrics provider", "first lyrics provider", "lyrics priority")),
            ),
        )
    // Lyrics → Romanisation sub-page.
    val lyricsRomanisation =
        SettingsItem(
            key = "lyrics_romanisation",
            icon = painterResource(R.drawable.translate),
            title = "Lyrics romanization",
            subtitle = "Transliterate non-Latin lyrics",
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("romanization", "romanisation", "romanize", "romaji", "pinyin", "transliteration", "furigana", "hangul"),
            onClick = { navController.navigate("settings/lyrics/romanisation") },
            hidden = true,
            children = listOf(
                SettingsChild("Romanize japanese lyrics", "lyrics_romanize_japanese", listOf("romanize japanese", "romaji", "furigana", "japanese lyrics")),
                SettingsChild("Romanize korean lyrics", "lyrics_romanize_korean", listOf("romanize korean", "hangul", "korean lyrics")),
                SettingsChild("Romanize chinese lyrics", "lyrics_romanize_chinese", listOf("romanize chinese", "pinyin", "chinese lyrics")),
                SettingsChild("Romanize hindi lyrics", "lyrics_romanize_hindi", listOf("romanize hindi", "devanagari", "hindi lyrics")),
                SettingsChild("Romanize other non-latin lyrics", "lyrics_romanize_other_languages", listOf("romanize other", "arabic", "thai", "cyrillic", "other languages")),
            ),
        )
    val content =
        SettingsItem(
            key = "content",
            icon = painterResource(R.drawable.language),
            title = stringResource(R.string.content),
            subtitle = stringResource(R.string.settings_content_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("content", "language", "locale", "country", "region", "app language", "explicit", "age restricted", "age", "mature", "video", "progressive", "quick picks"),
            onClick = { navController.navigate("settings/content") },
            children = listOf(
                SettingsChild("Content language", "content_language", listOf("language", "content language", "locale", "country")),
                SettingsChild("Content country", "content_country", listOf("country", "region", "content country")),
                SettingsChild("Hide explicit", "hide_explicit", listOf("explicit", "age", "mature", "age restricted", "clean")) { SearchResultSwitch(HideExplicitKey, false) },
                SettingsChild("Hide video", "hide_video", listOf("video", "hide video", "music video", "mv")) { SearchResultSwitch(HideVideoKey, false) },
                SettingsChild("Enable video", "enable_video", listOf("video", "music video", "mv", "enable video")),
                SettingsChild("Quick picks", "quick_picks", listOf("quick picks", "quick mix", "smart mix", "recommendations")),
                SettingsChild("Progressive playback", "progressive_playback", listOf("progressive", "gapless", "seamless")),
                SettingsChild("Allow age-restricted content", "allow_age_restricted", listOf("age restricted", "allow age restricted", "mature content", "18+", "restricted")),
                SettingsChild("Playlist recommendation source", "you_might_like_source", listOf("recommendation source", "you might like", "playlist recommendation", "suggestions source")),
                SettingsChild("AI content filter", "ai_content_filter", listOf("ai content filter", "ai filter", "ai generated", "aislist", "filter ai music")),
                SettingsChild("Hide AI-generated content", "ai_content_filter_hide", listOf("hide ai generated", "hide ai music", "ai content hide", "block ai")),
                SettingsChild("Include moderate-confidence channels", "ai_content_filter_moderate", listOf("moderate confidence", "ai filter moderate", "ai channels")),
                SettingsChild("Update channel lists", "ai_content_filter_update", listOf("update channel lists", "refresh ai list", "aislist update")),
                SettingsChild("About AiSList", "ai_content_filter_source", listOf("aislist", "about aislist", "ai list source")),
            ),
        )
    val languagePacks =
        SettingsItem(
            key = "language_packs",
            icon = painterResource(R.drawable.translate),
            title = stringResource(R.string.language_packs),
            subtitle = stringResource(R.string.settings_language_packs_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("language pack", "translation", "translate", "localization", "i18n"),
            onClick = { navController.navigate("settings/language_packs") },
            // Lyrics sub-page anchor, kept in the search index so existing search shortcuts still
            // work.
            hidden = true,
        )
    val behavior =
        SettingsItem(
            key = "behavior",
            icon = painterResource(R.drawable.swipe),
            title = stringResource(R.string.settings_behavior_title),
            subtitle = stringResource(R.string.settings_behavior_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("behavior", "privacy", "swipe", "gesture", "history", "cache", "data", "screenshot", "haptic", "vibrate"),
            onClick = { navController.navigate("settings/privacy") },
            children = listOf(
                SettingsChild("Pause listen history", "pause_listen_history", listOf("pause listen", "stop history", "private listening")) { SearchResultSwitch(PauseListenHistoryKey, false) },
                SettingsChild("Clear listen history", "clear_listen_history", listOf("clear history", "delete history", "reset history")),
                SettingsChild("Pause search history", "pause_search_history", listOf("pause search", "stop search history", "private search")) { SearchResultSwitch(PauseSearchHistoryKey, false) },
                SettingsChild("Clear search history", "clear_search_history", listOf("clear search", "delete search", "reset search")),
                SettingsChild("Sync playback to YouTube history", "sync_yt_history", listOf("youtube history", "sync history", "playback history")) { SearchResultSwitch(SyncPlaybackToYouTubeHistoryKey, false) },
                SettingsChild("Haptics", "haptics", listOf("haptic", "vibration", "haptic feedback", "vibrate")) { SearchResultSwitch(EnableHapticFeedbackKey, true) },
                SettingsChild("Disable screenshot", "disable_screenshot", listOf("screenshot", "screen capture", "privacy", "no screenshot")) { SearchResultSwitch(DisableScreenshotKey, false) },
                SettingsChild("Network metered", "network_metered", listOf("metered", "mobile data", "cellular", "data saver")) { SearchResultSwitch(NetworkMeteredKey, false) },
                SettingsChild("Show tags in library", "show_tags_in_library", listOf("tags", "library tags", "show tags")),
                SettingsChild("Low data mode", "low_data_mode", listOf("low data", "data saver", "save data", "metered", "data mode")) { SearchResultSwitch(LowDataModeKey, true) },
                SettingsChild("Force high refresh rate", "force_high_refresh_rate", listOf("refresh rate", "high refresh", "120hz", "90hz", "smooth")) { SearchResultSwitch(ForceHighRefreshRateKey, false) },
                SettingsChild("Open supported links by default", "open_supported_links", listOf("open links", "supported links", "default links", "deep link", "default browser app")),
            ),
        )
    val integration =
        SettingsItem(
            key = "integration",
            icon = painterResource(R.drawable.auto_awesome),
            title = stringResource(R.string.integration),
            subtitle = stringResource(R.string.settings_integration_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("integration", "lastfm", "last.fm", "libre.fm", "scrobble", "scrobbling", "discord", "listenbrainz", "spotify", "apple music"),
            onClick = { navController.navigate("settings/integration") },
            children = listOf(
                SettingsChild("Apple Music login", "applemusic", listOf("apple music", "applemusic", "itunes", "music kit", "apple login", "apple music login")),
                SettingsChild("Last.fm scrobbling", "lastfm_scrobbling", listOf("lastfm", "last.fm", "libre.fm", "scrobble", "scrobbling", "listens")) { SearchResultSwitch(EnableLastFMScrobblingKey, false) },
                SettingsChild("Last.fm account", "lastfm_account", listOf("lastfm account", "lastfm login", "lastfm session", "lastfm username")),
                SettingsChild("Last.fm options", "lastfm_options", listOf("lastfm options", "lastfm settings", "scrobble toggle", "now playing")),
                SettingsChild("Last.fm scrobbling configuration", "lastfm_scrobbling_config", listOf("scrobble config", "scrobble configuration", "scrobble threshold", "scrobble percentage")),
                SettingsChild("Discord rich presence", "discord_presence", listOf("discord", "rich presence", "rpc", "status", "now playing")) { SearchResultSwitch(EnableDiscordRPCKey, false) },
                SettingsChild("Discord account", "discord_account", listOf("discord account", "discord login", "discord token", "discord authorization")),
                SettingsChild("Discord options", "discord_options", listOf("discord options", "discord refresh", "refresh discord")),
                SettingsChild("Discord connection settings", "discord_connection", listOf("discord connection", "activity status", "platform status", "discord platform")),
                SettingsChild("Discord activity content", "discord_activity", listOf("discord activity", "activity name", "activity details", "activity state", "activity type", "discord show when paused")),
                SettingsChild("Discord image options", "discord_images", listOf("discord image", "large image", "large text", "discord artwork", "discord cover")),
                SettingsChild("ListenBrainz", "listenbrainz", listOf("listenbrainz", "listen brainz", "scrobble")) { SearchResultSwitch(ListenBrainzEnabledKey, false) },
                SettingsChild("ListenBrainz token", "listenbrainz_token", listOf("listenbrainz token", "listenbrainz api key", "listenbrainz credential")),
                SettingsChild("Listen Together", "listen_together", listOf("listen together", "listen together settings", "room", "shared playback", "sync playback", "listen party", "group listening", "chat", "together")) { SearchResultSwitch(ListenTogetherSyncVolumeKey, false) },
                SettingsChild("Listen Together screen", "listen_together_screen", listOf("listen together screen", "create room", "join room", "room code", "share room", "invite friends")),
                SettingsChild("Spotify", "spotify", listOf("spotify", "spotify connect", "spotify playlists")) { SearchResultSwitch(ShowSpotifyPlaylistsKey, false) },
                SettingsChild("Use pool accounts", "use_pool_accounts", listOf("pool", "source pool", "pool accounts", "shared accounts", "community accounts")) { SearchResultSwitch(UsePoolAccountsKey, true) },
                SettingsChild("Tidal", "tidal", listOf("tidal", "hifi", "master", "mqa", "lossless", "flac")) { SearchResultSwitch(TidalEnabledKey, false) },
                SettingsChild("Tidal account", "tidal_account", listOf("tidal account", "tidal login", "tidal token", "tidal session")),
                SettingsChild("Tidal instances", "tidal_instances", listOf("tidal instance", "tidal server", "tidal url", "tidal endpoint")),
                SettingsChild("Qobuz", "qobuz", listOf("qobuz", "hires", "hi-res", "flac", "lossless", "cd quality")),
                SettingsChild("Qobuz account", "qobuz_account", listOf("qobuz account", "qobuz login", "qobuz email", "qobuz session")),
                SettingsChild("Qobuz tokens", "qobuz_tokens", listOf("qobuz token", "qobuz app secret", "qobuz credential")),
                SettingsChild("Qobuz instances", "qobuz_instances", listOf("qobuz instance", "qobuz server", "qobuz url", "qobuz endpoint")),
                SettingsChild("Deezer", "deezer", listOf("deezer", "deezer login", "deezer premium", "deezer account", "deezer session")),
                SettingsChild("Telegram", "telegram", listOf("telegram", "telegram channel", "channel sync", "telegram music", "telegram bot")),
                SettingsChild("Telegram login", "telegram_login", listOf("telegram login", "telegram session", "telegram account", "sign in telegram")),
                SettingsChild("Telegram browse channels", "telegram_browse_channels", listOf("browse channels", "channels", "telegram channels", "music channels")),
                SettingsChild("Telegram lossless only", "telegram_lossless_only", listOf("lossless", "flac", "lossless only", "high quality")) { SearchResultSwitch(TelegramLosslessOnlyKey, false) },
                SettingsChild("Telegram logout", "telegram_logout", listOf("logout", "log out", "sign out", "disconnect telegram")),
                SettingsChild("Import playlist from another service", "cross_service_import", listOf("import", "import playlist", "cross service", "youtube music import", "apple music import", "amazon music import", "tidal import", "deezer import", "playlist url", "import url", "import from url", "playlist from url")),
                SettingsChild("Enable scrobbling", "enable_scrobbling", listOf("enable scrobbling", "scrobble", "scrobbler", "lastfm scrobble")) { SearchResultSwitch(EnableLastFMScrobblingKey, false) },
                SettingsChild("Now playing", "lastfm_now_playing", listOf("now playing", "lastfm now playing", "scrobble now playing", "update now playing")),
                SettingsChild("Prefer YouTube thumbnails", "lastfm_prefer_yt_thumbnails", listOf("prefer youtube thumbnails", "lastfm thumbnails", "scrobble artwork")),
                SettingsChild("Minimum track duration", "scrobble_min_track_duration", listOf("minimum track duration", "scrobble minimum", "min duration", "scrobble threshold")),
                SettingsChild("Scrobble delay percent", "scrobble_delay_percent", listOf("scrobble delay percent", "scrobble percentage", "scrobble after percent")),
                SettingsChild("Scrobble delay (seconds)", "scrobble_delay_minutes", listOf("scrobble delay", "scrobble seconds", "scrobble delay minutes")),
                SettingsChild("Connect Last.fm", "lastfm_connect_button", listOf("connect lastfm", "lastfm login", "lastfm sign in")),
                SettingsChild("Connect Libre.fm", "lastfm_connect_librefm_button", listOf("librefm", "libre.fm", "connect librefm", "librefm login")),
                SettingsChild("Connect custom GNU FM server", "lastfm_connect_custom_button", listOf("custom scrobble server", "gnu fm", "custom lastfm server", "self hosted scrobbler")),
                SettingsChild("Activity status", "activity_status", listOf("discord activity status", "activity status", "online status")),
                SettingsChild("Platform", "platform_status", listOf("discord platform", "platform status", "desktop mobile status")),
                SettingsChild("Activity name", "discord_activity_name", listOf("discord activity name", "activity name", "rpc name")),
                SettingsChild("Activity details", "discord_activity_details", listOf("discord activity details", "activity details", "rpc details")),
                SettingsChild("Activity state", "discord_activity_state", listOf("discord activity state", "activity state", "rpc state")),
                SettingsChild("Activity type", "discord_activity_type", listOf("discord activity type", "activity type", "listening playing")),
                SettingsChild("Show RPC when paused", "discord_show_when_paused", listOf("show when paused", "discord paused", "rpc paused")),
                SettingsChild("Large image", "large_image", listOf("discord large image", "large image", "rpc large image")),
                SettingsChild("Large text", "large_text", listOf("discord large text", "large text", "rpc large text")),
                SettingsChild("Small image", "small_image", listOf("discord small image", "small image", "rpc small image")),
                SettingsChild("Discord experimental options", "discord_experimental", listOf("discord experimental", "discord buttons", "rpc buttons", "discord translator")),
                SettingsChild("Telegram bots", "telegram_bots_title", listOf("telegram bots", "telegram bot", "bot token", "music bot")),
            ),
        )
    // Integration → Discord experimental sub-page.
    val discordExperimental =
        SettingsItem(
            key = "discord_experimental",
            icon = painterResource(R.drawable.auto_awesome),
            title = "Discord experimental",
            subtitle = "Rich presence buttons and translation",
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("discord experimental", "discord buttons", "rich presence buttons", "rpc buttons", "discord translator"),
            onClick = { navController.navigate("settings/discord/experimental") },
            hidden = true,
            children = listOf(
                SettingsChild("Enable translator", "enable_translator", listOf("discord translator", "enable translator", "translate presence")) { SearchResultSwitch(EnableTranslatorKey, false) },
                SettingsChild("Target language", "target_language", listOf("target language", "translation language", "discord language")),
                SettingsChild("Show button 1", "discord_show_button_1", listOf("discord button", "show button", "rpc button 1")),
                SettingsChild("Button 1 URL source", "discord_activity_button_1_url", listOf("button 1 url", "discord button url", "rpc button link")),
                SettingsChild("Show button 2", "discord_show_button_2", listOf("discord button 2", "show second button", "rpc button 2")),
                SettingsChild("Button 2 URL source", "discord_activity_button_2_url", listOf("button 2 url", "discord second button url", "rpc button 2 link")),
            ),
        )
    // Integration → Tidal account + instance management sub-page.
    val tidalDetail =
        SettingsItem(
            key = "tidal",
            icon = painterResource(R.drawable.provider_tidal),
            title = "Tidal",
            subtitle = "Tidal account and instances",
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("tidal", "tidal account", "tidal instances", "hifi", "mqa", "lossless", "flac", "tidal login"),
            onClick = { navController.navigate("settings/tidal") },
            hidden = true,
            children = listOf(
                SettingsChild("Tidal account", "tidal_account_connected", listOf("tidal account", "connected account", "tidal user")),
                SettingsChild("Sign in with Tidal (web)", "tidal_login_web", listOf("tidal login", "tidal sign in", "tidal web login", "connect tidal")),
                SettingsChild("Reconnect Tidal account", "tidal_reconnect", listOf("reconnect tidal", "refresh tidal", "tidal reconnect")),
                SettingsChild("Disconnect Tidal account", "tidal_disconnect", listOf("disconnect tidal", "tidal logout", "tidal sign out")),
                SettingsChild("Manage instances", "source_manage_instances", listOf("manage instances", "tidal instances", "tidal servers")),
                SettingsChild("Add instance", "tidal_add_instance", listOf("add instance", "add tidal instance", "new tidal server")),
                SettingsChild("Add many (paste)", "source_bulk_add", listOf("bulk add", "add many", "paste instances")),
                SettingsChild("Copy online", "source_copy_online", listOf("copy online", "copy working instances", "share instances")),
                SettingsChild("Remove dead", "source_remove_dead", listOf("remove dead", "remove dead instances", "clean instances")),
                SettingsChild("Remove deprecated", "source_remove_deprecated", listOf("remove deprecated", "remove old instances")),
                SettingsChild("Clear all instances", "tidal_reset_instances", listOf("clear instances", "reset instances", "delete all instances")),
            ),
        )
    // Integration → Qobuz account, tokens and instance management sub-page.
    val qobuzDetail =
        SettingsItem(
            key = "qobuz",
            icon = painterResource(R.drawable.provider_tidal),
            title = "Qobuz",
            subtitle = "Qobuz account, tokens and instances",
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("qobuz", "qobuz account", "qobuz tokens", "qobuz instances", "hi-res", "flac", "cd quality", "24 bit", "qobuz login"),
            onClick = { navController.navigate("settings/qobuz") },
            hidden = true,
            children = listOf(
                SettingsChild("Enable Qobuz source", "qobuz_enable", listOf("enable qobuz", "qobuz source", "turn on qobuz")),
                SettingsChild("Qobuz audio quality", "qobuz_audio_quality", listOf("qobuz quality", "hi-res", "flac", "cd quality", "24 bit")),
                SettingsChild("Sign in with Qobuz (web)", "qobuz_login_web", listOf("qobuz login", "qobuz sign in", "qobuz web login", "connect qobuz")),
                SettingsChild("Add tokens (paste)", "qobuz_add_tokens", listOf("add qobuz tokens", "qobuz token", "app secret", "app id", "paste tokens")),
                SettingsChild("Manage accounts", "qobuz_manage_accounts", listOf("manage qobuz accounts", "qobuz accounts", "account pool")),
                SettingsChild("Clear all tokens", "qobuz_reset_tokens", listOf("clear qobuz tokens", "reset tokens", "delete tokens")),
                SettingsChild("Manage instances", "source_manage_instances", listOf("manage instances", "qobuz instances", "qobuz servers")),
                SettingsChild("Add instance", "qobuz_add_instance", listOf("add instance", "add qobuz instance", "new qobuz server")),
                SettingsChild("Add many (paste)", "source_bulk_add", listOf("bulk add", "add many", "paste instances")),
                SettingsChild("Copy online", "source_copy_online", listOf("copy online", "copy working instances")),
                SettingsChild("Remove dead", "source_remove_dead", listOf("remove dead", "remove dead instances")),
                SettingsChild("Remove deprecated", "source_remove_deprecated", listOf("remove deprecated", "remove old instances")),
                SettingsChild("Clear all instances", "qobuz_reset_instances", listOf("clear instances", "reset instances", "delete all instances")),
            ),
        )
    // Integration → Telegram sub-page.
    val telegramDetail =
        SettingsItem(
            key = "telegram",
            icon = painterResource(R.drawable.ic_telegram),
            title = "Telegram",
            subtitle = "Telegram account and channels",
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("telegram", "telegram channel", "telegram login", "telegram music", "telegram bots", "channel sync"),
            onClick = { navController.navigate("settings/telegram") },
            hidden = true,
            children = listOf(
                SettingsChild("Signed in as", "telegram_logged_in_as", listOf("telegram account", "signed in as", "telegram user")),
                SettingsChild("Sign in with Telegram", "telegram_login", listOf("telegram login", "telegram sign in", "connect telegram", "phone code")),
                SettingsChild("Sign out", "telegram_logout", listOf("telegram logout", "telegram sign out", "disconnect telegram")),
                SettingsChild("Browse channels", "telegram_browse_channels", listOf("browse channels", "telegram channels", "music channels", "add channel")),
                SettingsChild("Lossless files only", "telegram_lossless_only", listOf("lossless only", "telegram lossless", "flac only", "high quality only")) { SearchResultSwitch(TelegramLosslessOnlyKey, false) },
                SettingsChild("Telegram bots", "telegram_bots_title", listOf("telegram bots", "bot token", "music bot")),
            ),
        )
    val androidAuto =
        SettingsItem(
            key = "android_auto",
            icon = painterResource(R.drawable.directions_car),
            title = stringResource(R.string.android_auto),
            subtitle = stringResource(R.string.android_auto_settings_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords =
                listOf(
                    "android auto", "car", "driving", "automotive", "projection", "car screen",
                    "metered", "mobile data", "device songs", "voice search", "playback controls",
                ),
            onClick = { navController.navigate("settings/android_auto") },
        )
    val aiIntegration =
        SettingsItem(
            key = "ai_integration",
            icon = painterResource(R.drawable.ai),
            title = stringResource(R.string.ai_integration),
            subtitle = stringResource(R.string.ai_integration_desc),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("ai", "artificial intelligence", "chatgpt", "openai", "gemini", "llm", "ai integration", "mix", "smart mix"),
            onClick = { navController.navigate("settings/ai_integration") },
            // Integration sub-page anchor, kept in the search index so existing search shortcuts
            // still work.
            hidden = true,
            children = listOf(
                SettingsChild("AI provider", "ai_provider", listOf("ai provider", "provider", "openai", "gemini", "claude", "anthropic", "model provider")),
                SettingsChild("Custom endpoint", "ai_custom_endpoint", listOf("custom endpoint", "endpoint", "base url", "api url", "custom api")),
                SettingsChild("AI API key", "ai_api_key", listOf("api key", "key", "secret", "ai key", "token")),
                SettingsChild("AI model", "ai_model", listOf("model", "ai model", "gpt", "gemini model", "claude model")),
                SettingsChild("Test API", "ai_test_api", listOf("test", "test api", "verify", "test connection", "ai test")),
                SettingsChild("Hide AI mix", "hide_ai_mix", listOf("hide ai", "ai mix", "smart mix", "hide mix")) { SearchResultSwitch(HideAiMixKey, false) },
                SettingsChild("Automatic translation", "auto_translate_lyrics", listOf("automatic translation", "auto translate", "auto translate lyrics", "translate automatically")),
                SettingsChild("Don't auto translate these languages", "auto_translate_excluded_languages", listOf("excluded languages", "skip translation", "do not translate", "translation exclusions")),
                SettingsChild("Target language", "translate_language", listOf("target language", "translate to", "translation language")),
                SettingsChild("Translation mode", "translate_mode", listOf("translation mode", "translate mode", "translation style")),
                SettingsChild("DeepL API key", "deepl_api_key", listOf("deepl", "deepl api key", "deepl key", "deepl token")),
                SettingsChild("DeepL formality", "deepl_formality", listOf("deepl formality", "formality", "formal informal")),
                SettingsChild("OpenRouter API key", "openrouter_api_key", listOf("openrouter", "openrouter api key", "openrouter key")),
                SettingsChild("Mistral API key", "mistral_api_key", listOf("mistral", "mistral api key", "mistral key")),
                SettingsChild("Model", "ai_model", listOf("ai model", "model", "gpt", "gemini model", "claude model")),
            ),
        )
    val internet =
        SettingsItem(
            key = "internet",
            icon = painterResource(R.drawable.wifi_proxy),
            title = stringResource(R.string.internet),
            subtitle = stringResource(R.string.settings_internet_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("internet", "proxy", "vpn", "network", "wifi", "connection", "traffic", "tor", "dns", "dns over https", "region", "country", "spoof", "geobypass", "geo bypass"),
            onClick = { navController.navigate("settings/internet") },
            children = listOf(
                SettingsChild("YouTube Music region", "yt_music_region", listOf("region", "country", "gl", "geo", "location", "spoof", "youtube region", "youtube music country", "geobypass", "geo bypass")),
                SettingsChild("Proxy", "proxy_settings", listOf("proxy", "http proxy", "socks", "vpn")) { SearchResultSwitch(ProxyEnabledKey, false) },
                SettingsChild("Proxy host", "proxy_host", listOf("proxy host", "proxy address", "proxy server")),
                SettingsChild("Proxy port", "proxy_port", listOf("proxy port", "port", "proxy port number")),
                SettingsChild("Proxy type", "proxy_type", listOf("proxy type", "socks5", "http proxy type", "proxy protocol")),
                SettingsChild("Proxy username", "proxy_username", listOf("proxy username", "proxy auth", "proxy credentials", "proxy login")),
                SettingsChild("Proxy password", "proxy_password", listOf("proxy password", "proxy auth", "proxy credentials", "proxy secret")),
                SettingsChild("Bypass proxy for streams", "stream_bypass_proxy", listOf("bypass proxy", "stream proxy", "stream bypass", "skip proxy for streams")),
                SettingsChild("Test proxy connection", "test_proxy", listOf("test proxy", "verify proxy", "check proxy", "proxy test")),
                SettingsChild("DNS over HTTPS", "dns_over_https", listOf("dns", "dns over https", "doh", "encrypted dns", "secure dns")),
                SettingsChild("DNS provider", "dns_provider", listOf("dns provider", "dns server", "dns resolver", "dns service")),
                SettingsChild("DNS custom URL", "dns_custom_url", listOf("dns custom url", "custom dns", "dns endpoint", "dns url")),
                SettingsChild("IP rotation", "ip_rotation", listOf("ip rotation", "rotate ip", "ip pool", "ip cycling")),
                SettingsChild("Enable tor", "enable_tor", listOf("tor", "onion", "anonymous", "onion routing", "tor network")),
                SettingsChild("Download speed limit", "download_speed_limit", listOf("speed", "limit", "throttle", "bandwidth", "download speed", "download limit", "speed cap")),
            ),
        )
    val poToken =
        SettingsItem(
            key = "po_token",
            icon = painterResource(R.drawable.token),
            title = stringResource(R.string.po_token_generation),
            subtitle = stringResource(R.string.settings_po_token_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("po token", "potoken", "botguard", "youtube token", "playability"),
            onClick = { navController.navigate(PO_TOKEN_ROUTE) },
            // Accounts sub-page anchor, kept in the search index so existing search shortcuts still
            // work.
            hidden = true,
            children = listOf(
                SettingsChild("Web Client PO Token", "web_client_po_token", listOf("po token", "potoken", "web client po token", "botguard", "playability", "youtube token")),
            ),
        )

    val storage =
        SettingsItem(
            key = "storage",
            icon = painterResource(R.drawable.storage),
            title = stringResource(R.string.storage),
            subtitle = stringResource(R.string.settings_storage_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("storage", "download", "cache", "disk", "space", "memory", "path", "location", "export", "export songs", "local storage", "save songs"),
            onClick = { navController.navigate("settings/storage") },
            children = listOf(
                SettingsChild("Downloaded songs", "downloaded_songs", listOf("downloaded", "offline songs", "saved songs")),
                SettingsChild("Song cache size", "song_cache_size", listOf("cache size", "song cache", "memory", "download cache")),
                SettingsChild("Clear song cache", "clear_song_cache", listOf("clear song cache", "delete song cache", "wipe song cache")),
                SettingsChild("Image cache size", "image_cache_size", listOf("image cache", "thumbnail cache", "artwork cache")),
                SettingsChild("Clear image cache", "clear_image_cache", listOf("clear image cache", "delete image cache", "wipe image cache")),
                SettingsChild("Canvas cache", "canvas_cache", listOf("canvas cache", "motion artwork cache", "animated artwork storage")),
                SettingsChild("Clear canvas cache", "clear_canvas_cache", listOf("clear canvas cache", "delete canvas cache", "wipe canvas cache")),
                SettingsChild("Storage folder", "storage_folder", listOf("storage path", "storage location", "storage directory")),
                SettingsChild("Download location", "download_location", listOf("download path", "location", "folder", "directory", "save to")),
                SettingsChild("Smart trimmer", "smart_trimmer", listOf("smart trimmer", "trim cache", "auto clean cache")) { SearchResultSwitch(SmartTrimmerKey, false) },
                SettingsChild("Max song cache size", "max_song_cache_size", listOf("max song cache", "song cache size", "cache limit", "cache size")),
                SettingsChild("Max image cache size", "max_image_cache_size", listOf("max image cache", "image cache size", "thumbnail cache size")),
                SettingsChild("Max canvas cache size", "max_cache_size", listOf("max canvas cache", "canvas cache size", "motion artwork cache size")),
                SettingsChild("Clear lyrics cache", "clear_lyrics_cache", listOf("clear lyrics cache", "delete lyrics cache", "wipe lyrics cache")),
                SettingsChild("Choose folder", "storage_folder_pick", listOf("choose folder", "pick folder", "storage folder", "select directory")),
                SettingsChild("Storage used", "size_used", listOf("storage used", "space used", "size used", "disk usage")),
            ),
        )
    val downloads =
        SettingsItem(
            key = "downloads",
            icon = painterResource(R.drawable.download),
            title = stringResource(R.string.downloads),
            subtitle = stringResource(R.string.settings_downloads_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("download", "downloader", "external downloader", "download source", "auto download", "export songs", "clear downloads", "offline"),
            onClick = { navController.navigate("settings/downloads") },
            children = listOf(
                SettingsChild("Clear all downloads", "clear_all_downloads", listOf("clear downloads", "delete downloads", "remove downloads")),
                SettingsChild("Export downloaded songs", "export_downloaded_songs", listOf("export", "export songs", "save songs", "local storage", "file")),
                SettingsChild("Auto download on like", "auto_download_like", listOf("auto download", "like", "download liked")) { SearchResultSwitch(AutoDownloadOnLikeKey, false) },
                SettingsChild("Download source", "download_source", listOf("download source", "qobuz download", "tidal download", "youtube music download")),
                SettingsChild("External downloader", "external_downloader", listOf("external downloader", "download app", "custom downloader")) { SearchResultSwitch(ExternalDownloaderEnabledKey, false) },
                SettingsChild("External downloader package", "external_downloader_package", listOf("downloader package", "downloader app name")),
            ),
        )
    val backupRestore =
        SettingsItem(
            key = "backup_restore",
            icon = painterResource(R.drawable.backup),
            title = stringResource(R.string.backup_restore),
            subtitle = stringResource(R.string.settings_backup_restore_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("backup", "restore", "export", "import", "data", "save", "scheduled", "playlist", "csv", "m3u"),
            onClick = { navController.navigate("settings/backup_restore") },
            children = listOf(
                SettingsChild("Scheduled backup", "scheduled_backup", listOf("scheduled backup", "auto backup", "schedule", "automatic backup", "backup schedule", "periodic backup")),
                SettingsChild("Scheduled backup frequency", "scheduled_backup_frequency", listOf("backup frequency", "schedule frequency", "backup interval")),
                SettingsChild("Scheduled backup directory", "scheduled_backup_directory", listOf("backup directory", "backup folder", "backup location")),
                SettingsChild("Scheduled backup overwrite", "scheduled_backup_overwrite", listOf("overwrite backup", "replace backup")),
                SettingsChild("Backup", "backup", listOf("backup", "save data", "export backup")),
                SettingsChild("Restore", "restore", listOf("restore", "import", "recover")),
                SettingsChild("Import online (m3u)", "import_online", listOf("import online", "m3u", "playlist import")),
                SettingsChild("Import CSV", "import_csv", listOf("import csv", "csv", "playlist csv")),
                SettingsChild("Enable scheduled backup", "scheduled_backup_enabled", listOf("enable scheduled backup", "auto backup", "automatic backup", "periodic backup")),
                SettingsChild("Overwrite existing backup", "scheduled_backup_overwrite", listOf("overwrite backup", "replace backup", "overwrite existing")),
                SettingsChild("Enable cloud sync", "google_drive_sync_enabled", listOf("cloud sync", "google drive", "drive sync", "enable cloud sync", "gdrive", "backup to drive")),
                SettingsChild("Drive folder", "google_drive_sync_remote_folder", listOf("drive folder", "google drive folder", "remote folder", "cloud folder")),
                SettingsChild("Clear Drive folder", "google_drive_sync_clear_folder", listOf("clear drive folder", "empty drive folder", "delete drive backups")),
                SettingsChild("Overwrite existing Drive backup", "google_drive_sync_overwrite", listOf("overwrite drive backup", "replace drive backup", "drive overwrite")),
                SettingsChild("Drive sync frequency", "google_drive_sync_frequency", listOf("drive sync frequency", "cloud sync frequency", "sync interval", "sync schedule")),
                SettingsChild("Sync now", "google_drive_sync_run_now", listOf("sync now", "run sync", "backup now", "upload now")),
            ),
        )
    val developerOptions =
        SettingsItem(
            key = "developer_options",
            icon = painterResource(R.drawable.experiment),
            title = stringResource(R.string.settings_developer_options_title),
            subtitle = stringResource(R.string.settings_developer_options_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("developer", "debug", "experimental", "advanced", "logcat", "dev", "changelog", "update"),
            onClick = { navController.navigate("settings/misc") },
            children = listOf(
                SettingsChild("Logcat", "logcat", listOf("logcat", "log", "debug log")),
                SettingsChild("Changelog", "changelog", listOf("changelog", "changes", "release notes", "what's new")),
                SettingsChild("Update channel", "update_channel", listOf("update channel", "canary", "stable", "beta")),
                SettingsChild("Enable update notification", "enable_update_notification", listOf("update notification", "notify update", "update alert")),
                SettingsChild("YTM sync", "ytm_sync", listOf("ytm sync", "youtube music sync", "sync library")),
                SettingsChild("Force sync on account switch", "force_sync_account_switch", listOf("force sync", "account switch sync", "sync on switch")),
                SettingsChild("Show nerd stats", "show_nerd_stats", listOf("nerd stats", "show nerd stats", "debug stats", "playback stats", "technical info")),
                SettingsChild("Display codec on player", "display_codec_on_player", listOf("display codec", "show codec", "codec on player", "bitrate on player")),
                SettingsChild("Show Discord debug UI", "show_discord_debug_ui", listOf("discord debug", "show debug ui", "discord debug ui")),
                SettingsChild("Debug logs", "debug_logs", listOf("debug logs", "verbose logs", "diagnostic logs")),
            ),
        )
    val defaultLinks =
        if (isAndroid12OrLater) {
            SettingsItem(
                key = "default_links",
                icon = painterResource(R.drawable.link),
                title = stringResource(R.string.default_links),
                subtitle = stringResource(R.string.open_supported_links),
                accentColor = MaterialTheme.colorScheme.secondary,
                keywords = listOf("default links", "links", "urls", "deep link", "supported links"),
                onClick = {
                    try {
                        val intent =
                            Intent(
                                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        when (e) {
                            is ActivityNotFoundException,
                            is SecurityException,
                            -> {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.open_app_settings_error,
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }

                            else -> {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.open_app_settings_error,
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    }
                },
                // Behaviour sub-page anchor, kept in the search index so existing search shortcuts
                // still work.
                hidden = true,
            )
        } else {
            null
        }
    val updates =
        if (BuildConfig.UPDATER_AVAILABLE) {
            SettingsItem(
                key = "updates",
                icon = painterResource(R.drawable.ic_refresh),
                title = stringResource(R.string.updates),
                keywords = listOf("update", "upgrade", "version", "new version", "release", "canary", "stable"),
                subtitle =
                    if (hasUpdate) {
                        stringResource(R.string.new_version_available)
                    } else {
                        stringResource(R.string.settings_updates_subtitle)
                    },
                showUpdateIndicator = hasUpdate,
                accentColor =
                    if (hasUpdate) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                badge = if (hasUpdate) "v${BuildConfig.VERSION_NAME}" else BuildConfig.VERSION_NAME,
                onClick = { navController.navigate("settings/update") },
            )
        } else {
            null
        }
    val about =
        SettingsItem(
            key = "about",
            icon = painterResource(R.drawable.ic_about),
            title = stringResource(R.string.about),
            subtitle = stringResource(R.string.settings_about_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("about", "info", "version", "license", "credits", "contributors", "changelog"),
            onClick = { navController.navigate("settings/about") },
            children = listOf(
                SettingsChild("Version", "about_version", listOf("version", "build")),
                SettingsChild("Changelog", "about_changelog", listOf("changelog", "changes", "release notes", "what's new")),
                SettingsChild("License", "about_license", listOf("license", "gpl", "open source")),
            ),
        )

    return listOf(
        SettingsGroup(
            title = stringResource(R.string.settings),
            items = listOf(account, stats, listenTogether),
        ),
        SettingsGroup(
            title = stringResource(R.string.settings_section_player_content),
            items =
                listOf(
                    appearance,
                    appearanceExtras,
                    aodCustomization,
                    navigationBar,
                    lyricsAnimations,
                    playback,
                    canvas,
                    sources,
                    jioSaavn,
                    amazon,
                    qqMusic,
                    deezer,
                    lyrics,
                    lyricsProviders,
                    lyricsRomanisation,
                    languagePacks,
                    content,
                    behavior,
                ),
        ),
        SettingsGroup(
            title = stringResource(R.string.integration),
            // Discord / Last.fm / Tidal / Qobuz / Telegram are intentionally NOT
            // top-level items here — they live as children of `integration` (and
            // also under their respective `sources` / `integration` screens).
            // Separate rows on the main settings page are redundant noise. The
            // `hidden = true` entries below exist purely so their own sub-page
            // settings are searchable.
            items =
                listOf(
                    androidAuto,
                    integration,
                    aiIntegration,
                    discordExperimental,
                    tidalDetail,
                    qobuzDetail,
                    telegramDetail,
                    internet,
                    poToken,
                ),
        ),
        SettingsGroup(
            title = stringResource(R.string.storage),
            items = listOf(storage, downloads, backupRestore),
        ),
        SettingsGroup(
            title = stringResource(R.string.about),
            items =
                buildList {
                    add(developerOptions)
                    defaultLinks?.let(::add)
                    updates?.let(::add)
                    add(about)
                },
        ),
    )
}
