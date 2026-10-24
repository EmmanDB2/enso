# Ensō

Android browser for the Galaxy Z Fold 8, built on GeckoView and Mozilla Android Components. It syncs Zen Browser's Spaces.

- **Spec:** [docs/SPEC.md](docs/SPEC.md). It is the source of truth for behavior, layout and the build order.
- **Mockups:** [docs/mockups/](docs/mockups/). Filenames start with the spec's board numbers (for example `4 · Tab sheet.png` is board 4). Look at the relevant boards before building any UI.
- **Logo:** [docs/logo.png](docs/logo.png), an ensō brush circle.

## How we work

- Build **one step of the spec's build order at a time**. Each step must build, install and run on the Fold before the next one starts.
- Don't build UI or features from later steps early, even as stubs.
- If the spec is unclear or silent, ask instead of guessing. The spec lists its own open questions at the end.
- Current step: **7, the inner screen**: the rail, the full sidebar, the sidebar's space swipe, fold continuity and space colors. Step 6 (the edge wave) is done.
  - Space colors are part of step 7 because the sidebar needs them. Every tinted surface on both screens uses the space's Zen theme gradient at Zen's own opacity, and the accent stays for small marks.
- When a behavior could reasonably differ per person, the user wants it built behind a value in `Settings.kt` so it can become a setting later.

## Project

- Kotlin, Gradle (Kotlin DSL) with the wrapper, and a version catalog in `gradle/libs.versions.toml`. Application ID and package are `app.enso.browser`.
- Mozilla libraries come from `https://maven.mozilla.org/maven2/`. GeckoView comes in through `browser-engine-gecko`, so bumping `mozilla-components` bumps both together.
- Android Components keeps many modules as internal dependencies. If the compiler says it "cannot access" an AC class, declare that module (for example `concept-engine`) in the catalog yourself.
- AC 158 requires `compileSdk` 37.2, which is why `app/build.gradle.kts` uses the minor-version `compileSdk { }` block.
- **Sign-in client ID:** `sync/MozillaAccount.kt` signs in with **Firefox for Android's OAuth client ID** (`a2270f727f45f648`), as Fenix forks like Dejavu do, because Mozilla only gives Sync access to its own apps. That's agreed for personal use and testing only. **Before publishing, get Ensō its own client ID from Mozilla and replace it.**
- **Reused code:** `sync/SyncClient.kt`, `sync/SyncCrypto.kt` and the emoji table in `ui/ZenIcons.kt` are adapted from Dejavu ([by-architect/Dejavu](https://github.com/by-architect/Dejavu), MPL 2.0). They keep their MPL headers and must stay MPL. Zen's record format comes from Zen's `src/zen/sync/ZenSpacesSync*.sys.mjs` (also MPL 2.0).
- **App-services setup:** `EnsoApplication` (main process only) runs `AppServicesInitializer.init` and `RustHttpConfig.setClient` before any Places or account code runs.
- `app/build.gradle.kts` has a capability rule that picks GeckoView's copy of `glean-native`. GeckoView's omni build bundles it, and application-services (Places) asks for the standalone one. Keep the rule while both are in the build.
- The build is limited to `arm64-v8a`, the Fold's chip type.

## Code map

- `Components.kt`: app-wide singletons, created lazily by `EnsoApplication`: `GeckoEngine`, `BrowserStore`, the use cases, `SpacesStore`, `TabManager` and `PlacesHistoryStorage`. Only one `GeckoRuntime` may exist per process, and it lives here.
  - History goes into Firefox's Places database through `HistoryDelegate`; the palette reads it, and step 5 syncs it.
- `spaces/`: the local spaces model, shaped after Zen so step 5 can sync it.
  - Structure: `SpacesState` holds the spaces and the shared Essentials. A `Space` holds its pinned items (tabs and folders, in order) and its open tabs.
  - Edits are pure functions in `SpacesEdits.kt`.
  - `SpacesStore` saves the model to `files/spaces.json` 500 ms after a change, and right away in `onStop`.
- `TabManager`: the only place tabs are opened, selected, closed or unloaded. Don't add or remove tabs in the `BrowserStore` directly.
  - The model and the `BrowserStore` share tab IDs. A tab exists in the BrowserStore only while it's loaded, so pinned tabs and Essentials can sit unloaded.
  - It copies URL and title changes, page-opened tabs (`WindowFeature`) and selection back into the model.
  - Any open tab that leaves the BrowserStore is removed from the model. A bug that drops BrowserStore tabs therefore deletes them from the user's saved tabs.
  - No selected tab is a real state: the start screen shows.
- Quick Tabs: `Tab.quick` holds `lastViewedAt`, and sync must skip these tabs.
  - New searches from the palette and the tab sheet's search field create them, and so do pages a Quick Tab opens. The top bar loads in the current tab.
  - Expiry runs in `onStart` and every minute while the app is open. The tab on screen is never cleared while you're viewing it.
- `tabsheet/`:
  - `TabSheet.kt` is the cover tab sheet (board 4). Quick Tabs get a gold outline.
  - Rows that can swap which tab they show (like the current-tab card) must key their swipe state by tab ID, or a dismissed state carries over and closes the next tab.
  - `TabMenu.kt` is the hold menu (board 4b).
    - On a Quick Tab it shows the spec's menu: Add to [space], Add to another space (the wave as a picker; hidden with one space) and Close tab.
    - On other tabs and folders it shows pin, unpin, folder and Essentials actions. While signed in, these sync to Zen.
    - Folder names confirm with the keyboard's Done key too, since a floating overlay can cover the dialog's button.
- `sync/`: Zen sync over the user's Mozilla account.
  - **Format:** Zen's `spaces` collection (version 3). Records decrypt to `{id, kind, data}`; the kinds are space, folder, tab, split, container and layout.
  - **What Zen sends:** by default only pinned tabs and Essentials. Normal tabs also need `zen.spaces-sync.normal-tabs` turned on in Zen's about:config.
  - **Account:** `MozillaAccount` asks for only the Sync scope, with no `SyncConfig`, so Firefox's history, bookmark and password engines never sync.
  - **Engine:** `ZenSpacesEngine` checks `meta/global` and `crypto/keys`, then syncs three ways against `ZenMirror` (`files/zen-spaces-mirror.json`), the last server state it knows, kept as whole records.
    - It works out the phone's changes against the mirror first, then downloads what changed on the server.
    - Where both sides changed the same record, Zen wins. The rest uploads with `X-If-Unmodified-Since`, in batches when it doesn't fit one request. A conflict makes `ZenSync` sync again.
    - Before its first write it saves the whole encrypted collection once, to `files/zen-spaces-snapshot.json` (`ZenSnapshotStore`).
    - It holds back deletions when a sync would delete more than 10 records, since that looks like lost data.
  - **Projector:** `ZenSpacesProjector.zenChanges` turns the phone's model into records.
    - Each record starts from the server's copy, and only fields the phone owns change: pinned, Essential, space, folder, children, folder names, and new tabs' pages. An untouched record comes out identical and isn't sent.
    - Containers, icons, themes, split layouts and live folders' children pass through. Split views are rebuilt while their tabs still sit together.
    - It only deletes items the phone showed, so a record the phone never displayed isn't removed for being missing.
    - A tab from Zen keeps Zen's URL unless `syncTabNavigations` is on.
    - New IDs follow Zen's formats: tabs `<time>-<uuid>` and folders `<time>-<0..99>`. Spaces are `{uuid}`, and the phone can't create them.
  - **Mapper:** `ZenSpacesMapper` applies the mirror through `TabManager.applySpaces`. Zen owns spaces, pinned tabs, folders (which can nest) and Essentials. The phone keeps its Quick Tabs and its own open tabs (`Tab.synced` tells them apart).
    - Open tabs follow Zen's order, and each phone-only tab stays after the tab it followed.
    - A tab ID must never be listed twice, or Zen gets duplicate children.
  - **Space look:** the accent comes from the theme's primary gradient color, and `Space.gradient` keeps all of its colors (Zen allows up to three). Icons go through `ZenIcons`.
  - **Essentials are per container**, as in Zen.
    - `layout.essentials` maps a container guid ("default" for none) to tab ids.
    - Each space has a `container` and shows `essentialsFor(space)`.
    - A setting (`essentialsPerContainer`) turns this off to show every Essential everywhere, matching Zen's own option.
  - **`ZenSync`:** runs the account and status. `ZenSyncSheet` (from ⋮ › Sync with Zen) shows the status.
    - It syncs on start, every 5 minutes, and 3 seconds after a tab sheet edit (`localEdited`).
    - It never applies Zen's state over an edit made during a sync; it syncs again instead.
    - In dry-run it doesn't apply Zen's state while the phone has unsent changes, so the test edits survive.
  - **Sign-in:** the page opens in a Quick Tab, and `FxaWebChannelFeature` completes it.
- `spacemenu/`: the space menu (board 3).
  - **Opening:** `SpaceMenuGesture` watches touches from `MainActivity.dispatchTouchEvent`, because GeckoView blocks parent interception.
    - A 300 ms hold (`spaceMenuHoldMs`) in a trigger zone opens it, then the views get an `ACTION_CANCEL` and the rest of the touch drives the menu. Taps and scrolls in the zones pass through as before.
    - The zones (`SpaceMenuZones`): the right edge (the left in left-handed mode), about 28 dp wide, from the bottom up to the top bar's bottom edge (`spaceMenuStripHeight`), and the address field while the bar shows.
    - The strip can be taller than Android's 200 dp exclusion cap, because the hold, not the exclusion, stops the back gesture.
    - Samsung's Edge panel handle sits on the cover's right edge at about 112–251 dp. It's a system window, so holds there never reach the app.
    - The Fold's long-press delay is 500 ms, so the hold beats a row's own long press in the edge strip.
  - **Blocking:** it doesn't open while editing, over the sync sheet, or over anything that calls `BlockSpaceMenu()` (the hold menu, the palette). Dialogs are their own windows, so touches there never reach it.
  - **Tap detectors:** a hand-written tap detector must treat a consumed release as a cancel, or a hold the menu took over also counts as a tap.
  - **The wave:** `SpaceWave` draws in its own `ComposeView`, added beside the root with `addContentView` so the tint covers the system bars.
    - The spaces are a list along the edge in Zen's order, top to bottom, 72 dp apart. The current space starts under the thumb.
    - **Calm:** the rows stay still, and the wave follows the thumb one-to-one (a row per 72 dp of travel), so nothing slides against the thumb.
      - Don't let rows spread apart around the thumb. It made them move against it at about 70% of its speed, which felt jumpy.
      - The drawn bulge eases after the thumb on a soft, critically damped spring (stiffness 400).
      - The hovered space holds on 0.12 rows past halfway, so the tick doesn't flutter.
    - **Travel:** the user calls a slower, calmer wave "lower travel". Each time they asked for less travel to calm it, more thumb travel per space was what they meant (52 → 60 → 72 dp).
    - `SpaceWaveState.position` is the wave's centre as a row index with fractions. `WaveShape` turns it into each row's place, so the bulge glides between rows.
    - **The string:** rows sit on a line held at the top and bottom of the window and pushed out 50 dp at the thumb. The line itself isn't drawn.
      - 22% of the push is the string's lean (`lean()`, near-straight lines to both ends, rounded at the tip). The rest is the bump (`falloff()`, rounded at its peak, half at one row, about a sixth at two). A pointed peak looked wrong.
    - **The look:**
      - **Backdrop:** the page is captured with `EngineView.captureThumbnail` as the wave opens and shown blurred (`Modifier.blur`, Android 12+) under a frost. There's no capture over the start screen or the tab sheet.
      - **Tint:** each space's Zen gradient (`Space.gradient`) runs top to bottom at 24%, or its accent when the theme has none. It cross-fades between spaces.
      - **Ink stroke:** a tapered stroke along the wave, 16 dp at the thumb, with a Gaussian taper over about two rows.
      - **Hovered space:** a frosted pill with a 22 sp name and its icon (no ring). The other names are 16, 13 and 12 sp and lighten with distance.
      - **Colors:** `EnsoColors.WaveFrost`, `WavePill`, `WaveInk` and `WaveText`, so dark mode can swap them.
    - **Tab sheet:** it doesn't blur yet; the spec puts its blurred snapshot in step 10.
    - When there isn't room to reach every space, a row takes less travel (down to 22 dp), and the list scrolls so the hovered row stays under the thumb.
    - Past either end, or 160 dp sideways, nothing is hovered, the bulge eases back, and letting go does nothing.
  - **Picker:** "Add to another space" opens the wave without the tab's space, resting a little below the middle of the screen. Touch or drag on the list and lift on a space: the tab becomes a normal tab there (`keepQuickTabIn`), and Ensō follows it. A touch away from the list, or Back, cancels.
  - **System gestures:** while it's open, the wave's stretch of the edges is excluded from them. Android only checks exclusions at touch-down, so during the hold it's the resting finger that cancels the back gesture.
- `start/StartScreen.kt`: start screen (board 9) and search palette (board 9b).
  - The start screen shows when no tab is selected, when the selected tab belongs to another space, or when the app was away for `startScreenAfterMinutes`.
  - Tap it for the palette; swipe down for the tab sheet.
  - Back returns to the last tab.
- `MainActivity.kt`: a `CoordinatorLayout` holding the engine view, the top bar, and one overlay `ComposeView` for everything drawn over the page.
  - **Cover or inner:** a window at least 600 dp wide at its narrowest counts as the inner screen. A rotated cover doesn't count. In portrait, the unfolded screen uses the cover's layout when `unfoldedPortraitLayout` is `Mobile`.
    - The activity handles folding itself (`configChanges`), so the page, tab, space and scroll position carry over with no extra work. `onConfigurationChanged` swaps the layout and closes the tab sheet and any edit.
  - **The inner layout** (`layoutInner`): the top bar is hidden, and the engine view becomes the page card beside the sidebar or rail.
    - It resets the cover's hide-on-scroll leftovers: container translation, dynamic toolbar height and vertical clipping. Without that, the page sat a bar's height too low in the card.
    - It runs again whenever the root view changes size. Rotating doesn't change the layout mode, and the insets arrive before the new window size, so without this the card kept its old size and the page didn't reflow.
    - Root padding is only the keyboard; the frame and sidebar handle the system bars themselves.
  - **The overlay on the inner screen:** `InnerFrame`, then the sidebar or rail, then the start screen clipped into the card, then the hold menu.
    - The content is wrapped in a full-size `Box`, because the ComposeView's root passes exact window constraints, which stretched the 64 dp rail across the screen.
  - Hide-on-scroll uses AC's `EngineViewScrollingDataBehavior` and `EngineViewClippingBehavior` together with GeckoView's dynamic toolbar height.
  - **Bottom edge:** with gesture navigation (a navigation bar of 32 dp or less), the cover's page runs under the handle; only the keyboard or a cutout pads the root.
    - The tab sheet keeps its search field above the handle (`gestureHandleInset`), and the edge wave's strip stops above it, so a hold doesn't fight the home gesture.
    - A navigation bar of buttons keeps its own space.
  - Don't switch to `EngineViewScrollingGesturesBehavior`. GeckoView blocks parent touch interception for gestures that start mid-page, so that behavior can hide the bar but never bring it back.
  - The back gesture goes back a page, or cancels an edit.
- `toolbar/`:
  - `TopBarView` hosts the Compose bar as a `ScrollableToolbar`.
  - `TopBar.kt` is the bar's UI. On the address field, a tap edits, a swipe down opens the tab sheet, and a sideways swipe switches tabs.
  - `AddressInput` decides between URL and search (Google) and formats the domain.
- `sidebar/`: the inner screen (boards 5, 6 and 9c).
  - `Sidebar` (its width set by dragging) and `Rail` (52 dp) reuse the tab sheet's rows (`TabRow`, `FolderRow`, `EssentialsGrid`), so holds, swipes and folders behave the same. The current tab is highlighted in place (`RowHighlight.Current`).
    - The sidebar's rows are 40 dp, through the rows' `height` parameters. The cover sheet keeps 48 dp.
  - **Moving the sidebar's edge:** opening, closing and dragging all draw the sidebar at its full width, revealed by the moving edge. The page is pinned under it (`pinnedPageDp`) and resizes once when the edge stops, because resizing GeckoView every frame would make the page reflow every frame.
    - `sidebarWidthDp` is the width the user set. `sidebarFullDp` is that width fitted to the window. Only the fitted width is ever clamped: on unfold, the layout can run once while the window still has the cover's width, and clamping the stored width then shrank it for good.
    - `SidebarResizeHandle` reaches 10 dp into the sidebar's edge and covers the border beside it.
  - **Border:** 4 dp on every side (`BORDER_DP`), or none with `innerPageBorder` off (square corners then).
    - Every gap in the sidebar is the same 4 dp (`EdgePadding`): the screen edge, between Essentials tiles, and the page side.
    - With the border on, the sidebar has no end padding, because the border is that gap. Adding padding there made the right side 8 dp. On the inner screen the status bar hides with `hideStatusBarWithTopBar`. The bottom inset only counts a navigation bar of buttons (taller than 32 dp). Gesture navigation's handle sits over the page; Samsung reports it as a tappable inset, which made the bottom border thicker than the others.
  - A sideways swipe switches spaces wherever a row doesn't take it: rows close on a swipe and consume it first. The space change slides (`AnimatedContent`).
  - The URL field edits the current tab through the activity's `editing`. Over the start screen, typing opens a Quick Tab instead.
  - The media card reads `TabSessionState.mediaSessionState` (the engine fills it in) and drives its `MediaSession.Controller`.
    - Previous and next follow the session's `features`: they skip tracks (`NEXT_TRACK`, `PREVIOUS_TRACK`), seek (`SEEK_FORWARD`, `SEEK_BACKWARD`), or are grayed out.
    - Mute state: AC's Gecko engine never calls `onMediaMuteChanged`, so `MediaSessionState.muted` stays false. The activity keeps its own record (`mutedMedia`), keyed to the session's controller, so a new session starts unmuted.
  - `InnerFrame` paints the space colors with a rounded hole for the page card. GeckoView's surface ignores view clipping, so the card's rounded corners are this paint. It has no touch handling, so the page gets the card's touches.
- `ui/`:
  - `Theme.kt` holds color tokens from the spec's Visual style table and the Inter font (bundled in `res/font`; its license is in `assets/licenses`).
  - `Favicons.kt` has the letter favicon placeholders and the space icons.
  - `SpaceColors.kt`: a space's chrome colors.
    - `Space.colors()` lays each gradient color over the base at the theme's opacity (`Space.themeOpacity`). A space without colors gets its accent at 16%.
    - `Modifier.spaceSurface` paints one gradient down the whole window, lined up by each surface's window position. The top bar, sheet, rows and sidebar therefore read as one, and rows that swipe over it still look seamless.
    - `Space.pageColors()` is the start screen's faint 10% version.
- `Settings.kt`: SharedPreferences values, not yet in any UI, meant to become user settings later.
  - `hideStatusBarWithTopBar` (default on) hides the status bar along with the bar, and on the inner screen.
  - `tabSwipeScope` (default `OpenTabs`) sets which tabs the address-field swipe moves through.
  - `quickTabLifetimeHours` (default 24, counted since last viewed) sets when Quick Tabs clear.
  - `startScreenAfterMinutes` (default 30) sets how long the app must be away before it reopens on the start screen.
  - `switchSpacesFromSheetHeader` (default off) lets a sideways swipe on the sheet header switch spaces too. It was the way to switch before the space menu.
  - `spaceMenuHoldMs` (default 300) sets how long a hold takes to open the space menu.
  - `spaceMenuLeftHanded` (default off) moves the space menu's wave and trigger strip to the left edge.
  - `unfoldedPortraitLayout` (default `Mobile`, the cover's top bar) chooses the unfolded screen's layout in portrait. `Desktop` keeps the sidebar and page card there too.
  - `innerPageBorder` (default on) draws the thin border around the inner page card. Off, the page sits flush beside the sidebar.
  - `innerSidebarWidthDp` (default 240, the width the user picked) is the sidebar's dragged width.
  - `spaceMenuStripHeight` (default 1, the whole edge) sets how much of the screen's height the edge strip covers, from the bottom up. It never reaches into the top bar.
  - `essentialsPerContainer` (default on) shows each space only the Essentials of its Zen container.
  - `syncOpenTabs` (default on) syncs normal open tabs both ways. Quick Tabs never sync.
  - `syncTabNavigations` (default off) lets navigating a tab from Zen move it on the desktop too. It's off because phone pages often redirect, to a login page for example.
  - `zenSyncDryRun` (default off) logs what sync would upload without uploading. It's for testing.
  - `lastStoppedAt` and `innerSidebarExpanded` (whether the inner screen shows the full sidebar) are app state, not preferences.
- Colors and icons: use the `EnsoColors` tokens rather than raw colors. Icons are thin-line vector drawables (1.8 stroke) in `res/drawable`.

## Toolchain (macOS)

`~/.zshrc` sets these:

- `JAVA_HOME`: Homebrew `openjdk@21`
- `ANDROID_HOME`: `~/Library/Android/sdk`
- PATH: the SDK's `cmdline-tools/latest/bin` and `platform-tools`

## Build and run on the Fold

```bash
./gradlew assembleDebug                               # build
./gradlew installDebug                                # install on the connected Fold
adb shell am start -n app.enso.browser/.MainActivity  # launch
adb logcat --pid=$(adb shell pidof -s app.enso.browser)  # app logs
```

The Fold connects over wireless debugging. Wireless debugging must be on in Developer options, with the Fold and the Mac on the same Wi-Fi.

- **First time:** tap "Pair device with pairing code", then run `adb pair <ip>:<pairing-port>` and type the code.
- **Every session:** adb usually reconnects by itself over mDNS. Check with `adb devices`. If the Fold isn't listed, run `adb connect <ip>:<port>`, using the port on the main Wireless debugging screen (not the pairing port). That port changes each time wireless debugging is turned on.
- **Screenshots:** the Fold has two displays, so `screencap` needs a display ID. List them with `adb shell dumpsys SurfaceFlinger --display-id`, then run `adb shell screencap -d <id> -p /sdcard/x.png`.
- **Scripted input:** `adb shell input -d <logical display> tap|swipe|text …`. Logical display 0 is whichever screen is on, and coordinates are in full-resolution pixels: 1248×1972 on the cover, 2448×1848 on the inner screen. The inner screen's SurfaceFlinger ID for screenshots is `4630947004648141459`, and the cover's is `4630947123231501204`. Unfolding needs the user. To place a tap on a popup, read its frame from `adb shell dumpsys window windows`.
  - Long press: `swipe x y x y 900`.
  - Hold a swipe partway: `motionevent DOWN|MOVE|UP x y`. The space menu tests this way: `DOWN 1235 1650` on the right edge, wait, `MOVE` up or down about 189 px (72 dp) per space, then `UP`. The user often uses the Fold while you test, so check the state between gestures rather than assuming your touches were the only ones.
- **Test pages from the Mac:** serve them on `127.0.0.1:<port>` and run `adb reverse tcp:<port> tcp:<port>`. The Fold can then open `http://localhost:<port>/…`.

## Testing on the Fold

The user browses with this build on the Fold, so its tabs are real.

- **Back up before testing:** save `adb shell run-as app.enso.browser cat files/spaces.json` (and `shared_prefs/enso_settings.xml`) first.
  - Put backups in `device-backups/<date-time>/` in the project. That folder is git-ignored because it holds personal data.
  - Never put them in the session scratchpad, which is cleared between sessions; a backup was lost that way once.
- **Use your own test tabs:** create them, close only those, and check the user's tabs are intact afterwards.
- **Never write to the user's Zen data without a snapshot.** The spaces collection is their real desktop setup.
  - The app saves a snapshot before its first write. Copy it into the backup folder with `adb shell run-as app.enso.browser cat files/zen-spaces-snapshot.json`.
  - To put the collection back (debug builds only): `adb shell am start -n app.enso.browser/.MainActivity --ez enso_restore_zen_snapshot true`.
  - Try risky changes in dry-run first: set `zen_sync_dry_run` to true in the prefs file while the app is stopped. The log (tag `ZenSync`) lists each record it would send and which fields changed.
  - Test with your own tabs and folders in one space, then remove them, so the deletions get tested too.
- **Restore if needed:** stop the app with `am force-stop`, then run `adb exec-in run-as app.enso.browser sh -c 'cat > files/spaces.json' < backup.json`.
- **Edge swipes:** a swipe that starts within about 60 px of the left or right edge is Android's back gesture. Start sideways swipes further in.
- **Faking time:**
  - Away time: edit `last_stopped_at` in the prefs file while the app is stopped.
  - Quick Tab expiry: edit a test tab's `quick.lastViewedAt` in `spaces.json`.
