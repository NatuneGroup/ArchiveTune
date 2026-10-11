# Settings route and surface inventory

## Stable navigation

Settings search uses parent routes and `?scrollTo=` anchors. Preserve both when changing groups. There is no manual-login gate: every sign-in row on Integration (Tidal, Qobuz, Deezer, Amazon, Apple Music, QQ Music, Telegram) is always visible. The `Use pool accounts` switch at the top of the Music Sources group (default on; rendered only where a pool URL is baked in) decides whether the community Source Pool's shared accounts are consulted at all — off means no pool fetch, and the "Refresh from pool" row is hidden.

Important Integration anchors include `external_sources`, `spotify`, `music_sources`, `use_pool_accounts`, `lastfm_scrobbling`, `lastfm_account`, `listenbrainz`, and `cross_service_import`.

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
  That bar is `NavigationBarStyle.YUMA_PLAYER`; `APPLE_MUSIC` is a separate, user-picked style (4nx3b's
  bar geometry: a capsule at the user's width, 80% by default, with a neutral indicator) that the experience does not force. Either
  Apple style, or the experience itself, draws the mini player in grayscale.
- **One-row bottom bar** — `NavigationBarCompactBehaviorKey` (Navigation bar → behaviour) folds the
  tab bar into round Library/Home and Search buttons with a slim mini player between them, the
  4nx3b layout. Unset, it follows `NavigationBarHideOnScrollKey`: adaptive (fold while scrolling
  down) when that is on, always expanded otherwise. The fold is a sheet-bound change of
  `collapsedBound`, not a translation. On History and Local songs the Search button opens the
  page's own search field (`openInPageSearch` on the entry's saved state).
- **Home pages** — the Home tab's pages are a multi-select set (`ActiveHomeSourcesKey`) with a
  single-choice switcher sheet in the top bar when more than one is active; YouTube and Spotify
  today, and the enum/selector take more.
- **Player and lyrics overflow** — the player's more button opens the shared menus, which carry the
  Apple Music sleep-timer sheet and the lyrics menu.

Not complete, and must not be described as shipped: a non-locking preset that writes those
sub-controls independently, YouTube/Spotify Apple-style *Home variants* (the Home selector switches
sources, it does not restyle them), and Spotify's Recommended for Today. Direct authenticated Apple
playback must remain separate from any future public catalog search/fallback work.

## Settings change checklist

- Preserve existing parent routes and search anchors.
- Add new preference keys with explicit defaults and localized descriptions.
- Keep settings available through both the page and settings search when appropriate.
- Update route inventory and tests for any renamed or moved entry.
- Verify back navigation, deep links, dialogs, toggles, sliders, and provider-specific pages on Canary.

## Navigation bar

`NavigationBarHideOnScrollKey` (off by default, Navigation bar page, anchor
`navigation_bar_hide_on_scroll`) hides the bar on downward user scrolling of a tab and drops the
collapsed mini player into its place; content padding stays static so lists do not jump. The tinted
frosted bar blends the accent into its base and content in both light and dark schemes.

The interface-style experience, library and player preferences change in one
DataStore edit, using the current stored player style rather than a value from a
previous composition. Re-selecting the active style does not overwrite the
saved player choice.
