# Settings route and surface inventory

## Stable navigation

Settings search uses parent routes and `?scrollTo=` anchors. Preserve both when changing groups. There is no manual-login gate: every sign-in row on Integration (Tidal, Qobuz, Deezer, Amazon, Apple Music, QQ Music, Telegram) is always visible. The `Use pool accounts` switch at the top of the Music Sources group (default on; rendered only where a pool URL is baked in) decides whether the community Source Pool's shared accounts are consulted at all — off means no pool fetch, and the "Refresh from pool" row is hidden.

Important Integration anchors include `external_sources`, `spotify`, `music_sources`, `use_pool_accounts`, `lastfm_scrobbling`, `lastfm_account`, `listenbrainz`, and `cross_service_import`.

The Player page carries three experimental audio-engine switches after `bit_perfect_usb`: `tryptify_engine` (`TryptifyAudioProcessingKey`), `lastwave_engine` (`LastwaveAudioProcessingKey`) and `float_dsp` (`FloatDspEnabledKey`), all off by default. Tryptify and LastWave exclude each other, turning any of them on turns audio offload off, and all three are disabled while bit-perfect USB output is on (that sink skips every processor). Their search entries navigate only and carry no inline switch, so the exclusivity stays on the settings page. `playback/dsp/AudioEngineHost` reads them; with all three off the per-sink router is inactive and Media3 skips it.

Between `bit_perfect_usb` and the engines sits `usb_exclusive_audio` (`UsbExclusiveAudioKey`, off by default, search entry navigates only). It is sampled when the service starts: with it on, the standard sink gets `playback/dsp/UsbExclusiveAudioOutputProvider`, which opens an exclusive AAudio stream on the routed USB DAC and otherwise defers to a stock `AudioTrackAudioOutputProvider`; with it off the sink is built exactly as before. It and bit-perfect USB output, crossfade, automix and audio offload turn each other off (the provider re-checks the same switches live, so a stale combination still plays normally). The engines and float DSP stay usable: they run in the chain ahead of the output. The switch is disabled when `libarchivetune_dsp` fails to load. While engaged it publishes to `EngineRuntime`/`BitPerfectRuntime`, which shows the media-info pipeline row and the live chain pill's USB route; it never claims verified bit-perfect.

## Spotify integration

The Spotify group contains account login/logout, playlist visibility, playlist reload, and the opt-in `Sync Spotify listening history` switch. The switch exposes Spotify Recently Played in the History screen; it is intentionally passive and does not emulate Spotify playback.

## Apple Music experience status

The experience is chosen with **Appearance → Theme → Interface style** (`interface_style`, legacy
anchor `apple_music_experience`), a two-way selector over `AppleMusicExperienceKey`: Material 3
Expressive (the default, key unset or false) or Apple Music. Material 3 Expressive seeds the palette
from purple instead of the wallpaper when no custom or album-art color applies. Nothing seeds the
Apple Music style on a fresh install any more.

The Search tab's field can sit at the top (default) or float above the bottom bar
(`SearchBarPositionKey`), optionally sliding away while scrolling down
(`HideSearchChromeWhileScrollingKey`). Both styles render it: a 28dp tonal container with the Solar
magnifier for Material, a capsule for Apple Music.

Shipped and driven by the experience switch: the Apple Music player (with its queue sheet, inline
lyrics and mini header), the animated-artwork backdrop, the playlist hero, the sleep-timer sheet, the
sliders, and the menu-header treatment. The switch forces the player style and the tab bar, and it
keeps `LibraryStyleKey` in step — the switch is meant to turn the whole experience on, and it is the
only control that moves more than one surface. The library-style row is the library half on its own:
it writes `LibraryStyleKey` and nothing else, so it can restyle the Library tab without touching the
player, the tab bar or the headers.

Also shipped since this note was written:

- **Library screen** — with the library style on, the Library tab renders an Apple Music root (large
  title, chevron rows, hairline insets to the text column, the four sections opened in place under a
  back row) instead of the fork's chip row.
- **Tab bar** — the Apple Music style is a floating rounded bar, inset, with a solid accent pill
  behind the active tab, its glyph knocked out, and the label in the accent.
  The experience forces `NavigationBarStyle.APPLE_MUSIC` (4nx3b's bar geometry: a capsule at the
  user's width, 80% by default, with a neutral indicator) and locks the style row while it is on;
  `YUMA_PLAYER` stays a separate, user-picked style. Either Apple style, or the experience itself,
  draws the mini player in grayscale.
- **One-row bottom bar** — `NavigationBarCompactBehaviorKey` (Navigation bar → behaviour) folds the
  tab bar into round Library/Home and Search buttons with a slim mini player between them, the
  4nx3b layout. Unset, it follows `NavigationBarHideOnScrollKey`: adaptive (fold while scrolling
  down) when that is on, always expanded otherwise. The Apple Music bar (style or experience) is
  always the one-row layout, so the behaviour row is locked while it is active. The row is liquid
  glass only when the tab bar would be (Liquid Glass on + its navigation-bar toggle); otherwise the
  circles and the mini player capsule are opaque `surfaceContainerHigh` with no glow. The fold is a sheet-bound change of
  `collapsedBound`, not a translation. On History and Local songs the Search button opens the
  page's own search field (`openInPageSearch` on the entry's saved state).
- **Home pages** — the Home tab's pages are a multi-select set (`ActiveHomeSourcesKey`) with a
  single-choice switcher sheet in the top bar when more than one is active; YouTube and Spotify
  today, and the enum/selector take more.
- **Player and lyrics overflow** — the player's more button opens the shared menus, which carry the
  Apple Music sleep-timer sheet and the lyrics menu. The lyrics three-dot (`AnchoredLyricsOverflowMenu`)
  opens the ordinary lyrics sheet unless the experience is on; then it is Apple Music's anchored
  action list on a flat charcoal card. No live backdrop blur: recording one cost a full-screen layer
  every frame on every lyrics surface.
- **Flamingo player** — `PlayerDesignStyle.FLAMINGO` (Appearance → Player), 4nx3b's album / lyrics /
  queue pager with its own floating-light backdrop. Its moving-blur option,
  `flamingo_background_effect`, renders only while it is the selected style. Its volume bar follows
  the shared `show_player_volume_bar` switch like every other layout that has a volume row (the old
  Flamingo-only switch was folded into it; an explicit earlier Flamingo choice is kept).
- **Additional downloads** (`settings/language_packs`, search key `language_packs`) — a visible row
  in the main page's Storage group (after Downloads), also linked from Lyrics settings and found
  by search. It holds the Japanese dictionary (about 13 MB) plus files kept out of the APK in
  `addons/` (today the SpatialFlow typeface, about 4 MB). `AddonPack` fetches them from the last
  commit that touched `addons/` and checks the SHA-256 baked in at build time, so each release
  keeps getting its own bytes; anything that reads an addon must work without it. Resources under
  1 MB belong in the APK instead of this screen; both current packs are well above that.

Not complete, and must not be described as shipped: a non-locking preset that writes those
sub-controls independently, YouTube/Spotify Apple-style *Home variants* (the Home selector switches
sources, it does not restyle them), and Spotify's Recommended for Today. Direct authenticated Apple
playback must remain separate from any future public catalog search/fallback work.

## Settings change checklist

- Preserve existing parent routes and search anchors.
- Add new preference keys with explicit defaults and localized descriptions.
- Keep settings available through both the page and settings search when appropriate.
- Update route inventory and tests for any renamed or moved entry.
- Verify back navigation, deep links, dialogs, toggles, sliders, and provider-specific pages on a Nightly build.

## Navigation bar

`NavigationBarHideOnScrollKey` (off by default, Navigation bar page, anchor
`navigation_bar_hide_on_scroll`) hides the bar on downward user scrolling of a tab and drops the
collapsed mini player into its place; content padding stays static so lists do not jump. The tinted
frosted bar blends the accent into its base and content in both light and dark schemes.

The interface-style experience, library and player preferences change in one
DataStore edit, using the current stored player style rather than a value from a
previous composition. Re-selecting the active style does not overwrite the
saved player choice.

`BackAnimationStyleKey` (Appearance, anchor `back_animation_style`) chooses the back animation:
Predictive (the default; sheets, pages and menus follow the back gesture) or Original. The SukiSU
liquid glass bar is a `NavigationBarStyle` (`SUKISU`) rendered inside the normal nav-bar container,
so compact mode and hide-on-scroll keep working.

## Restart-to-apply settings

UI scale, font and custom font, tablet mode, the Liquid Glass master switch, the liquid-glass nav
bar and the frosted nav bar are read once per process through `utils/StartupSettings`. Changing one
shows `RestartToApplyNotice` instead of recomposing the whole app. "Disable blur effects" is applied
on top through the root `LocalBlurPolicy`, which every blur and glass surface honours.
