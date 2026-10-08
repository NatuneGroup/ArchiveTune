# BitChord Navigation Bar Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port 4nx3b/dev's BitChord bottom navigation bar (`3febe77f8`, plus its pure-bugfix follow-ups) into the fork behind an opt-in toggle.

**Architecture:** New `BitChordNavBar.kt` adapted to fork APIs (frosted path only — fork has no `ThrottledLayerBackdrop`); boolean preference key; settings switch with mutual exclusion; MainActivity hosts it in the existing slide/hide box.

**Tech Stack:** Kotlin, Jetpack Compose Material3, DataStore preferences.

**Spec:** Upstream commits `3febe77f8` (port), `43f36ed74` + `ca211bf07` (bugfix deltas: `LaunchedEffect` selected-index sync, `tabStepPx`-based pill target, drag-offset reset). Liquid-glass param from those commits is EXCLUDED (fork lacks the type). User ask: bottom-nav port from 4nx3b dev + fork invariants (MD3 default, Apple Music behind toggle, no upstream settings-redesign import).

## Global Constraints

- GPL header banner on new .kt files, matching neighbours, plus `Portions © 4nx3b` attribution (the file is their re-implementation of BitChord's bar).
- No user-facing strings inline — all in `app/src/main/res/values/archivetune_strings.xml` (fork convention; upstream `strings.xml` is legacy).
- Comments only for non-obvious *why*; no TODO/FIXME/banners.
- Apple Music Experience / APPLE_MUSIC style behaviour unchanged; BitChord is opt-in, default off.
- BitChord ON must turn frosted / tinted-frosted / liquid-glass nav OFF and vice versa (upstream `3febe77f8` semantics).
- Do NOT import upstream's settings redesign; wire into existing `NavigationBarSettings.kt` + `SettingsDataBuilders.kt` anchors.
- Mini-player proximity: BitChord pill never docks — proximity must read `0f` while active (same as FLOATING).

## Review Focus

- BitChord enabled on pre-Android-12 device: frosted backdrop is null there — bar must still render (solid surface fallback), same as FLOATING does.
- Rapid tab switching + drag gesture racing: `selectedIndex` sync via `LaunchedEffect` (not `remember(key)`) so an in-flight drag doesn't snap back.
- Apple Music Experience ON + BitChord ON: Apple Music style wins in `navigationBarStyle` resolution — verify the bar shown is deterministic (BitChord flag is independent of style; document which takes precedence in the chosen host condition).
- Settings search for "bitchord"/"bottom bar" finds the new switch and toggles the same key.
- Hide-on-scroll + BitChord: bar hides in the same slide box (no separate hide path).

---

### Task 1: `BitChordNavBar.kt` component (adapted port)

**Files:**
- Create: `app/src/main/kotlin/moe/rukamori/archivetune/ui/component/BitChordNavBar.kt`
- Reference: `/tmp/BitChordNavBar-upstream.kt` (current 4nx3b/dev version, 438 lines)

**Interfaces:**
- Consumes: fork `NavigationBarBackdrop` (`layer: GraphicsLayer`, `contentOffsetInRoot: Offset` — same members upstream uses).
- Produces: `@Composable BitChordNavBar(barHeight: Dp, selectedRoute: String?, onRouteSelected: (String) -> Unit, itemCount: Int, itemRoute: (Int) -> String, itemLabel: @Composable (Int) -> String, itemIcon: @Composable (Int) -> ImageVector, modifier: Modifier = Modifier, frostedBackdrop: NavigationBarBackdrop? = null, frostedBlurRadiusPx: Float = 60f, frostedOverlayAlpha: Float = 0.30f)` + `val BitChordHomeIcon / BitChordLibraryIcon / BitChordSearchIcon: ImageVector`.

- [ ] **Step 1: Write the file** — copy upstream, then DELETE the `liquidGlassBackdrop: ThrottledLayerBackdrop?` param, `canLiquidGlass`, the `Modifier.liquidGlass` branch (keep `.clip(pillShape)`), and the `liquidGlassBackdrop` import; replace `frostedBackdrop != null && !canLiquidGlass` with `frostedBackdrop != null`; keep the `LaunchedEffect` index sync, `tabStepPx` pill target, and drag-end `dragOffset.floatValue = 0f` fixes; keep the three icon `ImageVector`s and all dimensions (16dp gutter, 6dp inset, 6dp spacing, 25dp icons, 0.5dp edge, 0.72/320 springs, 1.08 bounce, 200ms crossfade, 0.35 stride threshold).
- [ ] **Step 2: Verify no references** to `ThrottledLayerBackdrop`, `liquidGlass(`, `canLiquidGlass` remain: `grep -n 'Throttled\|canLiquidGlass\|liquidGlass(' <file>` must print nothing.
- [ ] **Step 3: Commit** — `git add <file>` + `git commit -m "feat(navbar): port 4nx3b BitChord navigation bar (frosted path only)"`.

### Task 2: Preference key + settings switch + mutual exclusion + strings + search anchor

**Files:**
- Modify: `.../constants/PreferenceKeys.kt` (add `val NavigationBarBitchordKey = booleanPreferencesKey("navigationBarBitchord")` next to the frosted keys)
- Modify: `.../ui/screens/settings/NavigationBarSettings.kt` (switch + extend `onFrostedBlurChange`/`onTintFrostedBlurChange`/liquid-glass change to also clear BitChord; add `onBitchordChange` clearing the other three — mirror upstream `3febe77f8` NavigationBarSettings diff)
- Modify: `app/src/main/res/values/archivetune_strings.xml` (append near nav strings: name + desc)
- Modify: `.../ui/screens/settings/SettingsDataBuilders.kt` (add `SettingsChild("Enable BitChord navigation bar", "navigation_bar_bitchord", listOf("bitchord", "bottom bar", "navigation bar")) { SearchResultSwitch(NavigationBarBitchordKey, false) }` next to the other nav entries, in both appearance + nav groups if both list nav bars)

**Interfaces:**
- Consumes: `NavigationBarBitchordKey` from Task 1's key addition.
- Produces: settings toggle bound to the key; search anchor id `navigation_bar_bitchord`.

- [ ] **Step 1: Add the key** in PreferenceKeys.kt.
- [ ] **Step 2: Add switch + mutual exclusion** in NavigationBarSettings.kt (check existing `onFrostedBlurChange` shape first — fork already excludes tint; extend to BitChord + liquid glass; verify liquid-glass row's current `onCheckedChange` and wrap it).
- [ ] **Step 3: Add strings + search anchor(s)**; confirm `SearchResultSwitch(Key, false)` matches neighbouring call shape.
- [ ] **Step 4: Commit** — `git commit -m "feat(navbar): Enable BitChord navigation bar option with mutual exclusion"`.

### Task 3: MainActivity hosting

**Files:**
- Modify: `app/src/main/kotlin/moe/rukamori/archivetune/MainActivity.kt`

**Interfaces:**
- Consumes: `BitChordNavBar` (Task 1), `NavigationBarBitchordKey` (Task 2).
- Produces: BitChord bar rendered in the existing nav slide/hide `Box` when enabled.

- [ ] **Step 1: Read** the host region (~lines 3020–3160 worktree) and the `navigationBarBitchord` preference read pattern; add `val navigationBarBitchord by rememberPreference(NavigationBarBitchordKey, false)` next to the other nav prefs (~line 991).
- [ ] **Step 2: Host the bar** — inside the slide/hide `Box`, `if (navigationBarBitchord) BitChordNavBar(barHeight = navVisibleHeight, selectedRoute = navBackStackEntry?.destination?.route, onRouteSelected = { route -> navigationItems.firstOrNull { it.route == route }?.let { screen -> handlePrimaryNavigationClick(screen, navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true) } }, itemCount = navigationItems.size, itemRoute = { navigationItems[it].route }, itemLabel = { stringResource(navigationItems[it].titleId) }, itemIcon = { index -> when (navigationItems[index].route) { "home" -> BitChordHomeIcon; "search" -> BitChordSearchIcon; else -> BitChordLibraryIcon } }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + floatingBarsBottomPadding), frostedBackdrop = navBarFrostedBackdrop) else <existing FloatingNavigationToolbar call untouched>`. Condition must ALSO require `!useRail` context already given (host Box is skipped when useRail) and decide vs APPLE_MUSIC style: BitChord wins only when `navigationBarStyle != APPLE_MUSIC` OR unconditionally? — rule: `navigationBarBitchord && navigationBarStyle != NavigationBarStyle.APPLE_MUSIC`, so the coordinated Apple experience is never半截 replaced. Record choice in ledger.
- [ ] **Step 3: Frosted consumer + proximity** — fork allocates `navBarFrostedBackdrop` unconditionally on S+ (no `anyFrostedConsumerActive` gate — verify while editing; if a gate exists, add the flag); add `|| navigationBarBitchord` to the proximity-zero condition next to `isFloatingNavBar`.
- [ ] **Step 4: Commit** — `git commit -m "feat(navbar): host BitChord bar in nav slide box"`.

### Task 4: Triage + port small safe good-logic items from 4nx3b/dev

**Files:** TBD by triage (strict allowlist below).

- [ ] **Step 1: Triage** — `git log --oneline 8346b1750e29..4nx3b/dev` (review snapshot → tip), list candidates that are (a) ≤ ~100 lines, (b) pure bugfix/perf with no UI-redesign dependency, (c) fork-invariant-safe. Reject: search-tab rewrite (`682ee5461`), home feed redesign (`4cbf7eff6`), Flamingo/canvas batches, settings redesign, anything touching `applicationId`/signing/submodule pointers.
- [ ] **Step 2: Implement at most 2** highest-value items from triage, one commit each, same header/string/comment rules.
- [ ] **Step 3: If nothing qualifies, skip with a ledger note** — do NOT pad.

### Task 5: Verify, push, PR

- [ ] **Step 1: Build** — `bash scripts/setup_android.sh` (already running; check `/tmp/sdk-setup.log`), then `./gradlew assembleGmsMobileUniversalDebug` in `/tmp/at-bitchordnav`. Expected: exit 0.
- [ ] **Step 2: Unit tests** — `./gradlew :app:testGmsMobileUniversalDebugUnitTest`. Expected: all pass (note count).
- [ ] **Step 3: Push + PR** — `git push -u origin feat/bitchord-navbar`, `gh pr create --base canary` with attribution to 4nx3b/BitChord. Then report.
