# Ensō

Android browser for the Galaxy Z Fold 8, built on GeckoView and Mozilla Android Components. It syncs Zen Browser's Spaces.

- **Spec:** [docs/SPEC.md](docs/SPEC.md). It is the source of truth for behavior, layout and the build order.
- **Mockups:** [docs/mockups/](docs/mockups/). Filenames start with the spec's board numbers (for example `4 · Tab sheet.png` is board 4). Look at the relevant boards before building any UI.

## How we work

- Build **one step of the spec's build order at a time**. Each step must build, install and run on the Fold before the next one starts.
- Don't build UI or features from later steps early, even as stubs.
- If the spec is unclear or silent, ask instead of guessing. The spec lists its own open questions at the end.
- Current step: **5, Mozilla account and Zen sync**, in two passes.
  - **5a (now): read-only, Zen → phone.**
  - **5b (next): phone → Zen**, with a snapshot of the server collection saved before the first write.
  - Until the arc menu (step 6), you switch spaces by swiping the tab sheet's header.
  - The inner screen shows the cover layout stretched until step 7.
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
    - On a Quick Tab it shows the spec's menu: Add to [space] and Close tab. "Add to another space" waits for the arc menu (step 6).
    - On other tabs and folders it shows local pin, unpin, folder and Essentials actions, which stand in until sync.
- `sync/`: Zen sync over the user's Mozilla account.
  - **Format:** Zen's `spaces` collection (version 3). Records decrypt to `{id, kind, data}`; the kinds are space, folder, tab, split, container and layout.
  - **What Zen sends:** by default only pinned tabs and Essentials. Normal tabs also need `zen.spaces-sync.normal-tabs` turned on in Zen's about:config.
  - **Account:** `MozillaAccount` asks for only the Sync scope, with no `SyncConfig`, so Firefox's history, bookmark and password engines never sync.
  - **Reader:** `ZenSpacesReader` checks `meta/global` and `crypto/keys`, then downloads changed records into `ZenMirror` (`files/zen-spaces-mirror.json`, whole records kept for the write pass).
  - **Mapper:** `ZenSpacesMapper` applies the mirror through `TabManager.applySpaces`. Zen owns spaces, pinned tabs, folders (which can nest) and Essentials. The phone keeps its open tabs and Quick Tabs (`Tab.synced` tells them apart).
  - **Space look:** the accent comes from the theme's primary gradient color. Icons go through `ZenIcons`.
  - **Essentials are per container**, as in Zen.
    - `layout.essentials` maps a container guid ("default" for none) to tab ids.
    - Each space has a `container` and shows `essentialsFor(space)`.
    - A setting (`essentialsPerContainer`) turns this off to show every Essential everywhere, matching Zen's own option.
  - **`ZenSync`:** runs the account and status, syncs on start and every 5 minutes. `ZenSyncSheet` (from ⋮ › Sync with Zen) shows the status.
  - **Sign-in:** the page opens in a Quick Tab, and `FxaWebChannelFeature` completes it.
  - **While signed in (read-only):** the hold menu hides pin, folder and Essentials actions.
- `start/StartScreen.kt`: start screen (board 9) and search palette (board 9b).
  - The start screen shows when no tab is selected, when the selected tab belongs to another space, or when the app was away for `startScreenAfterMinutes`.
  - Tap it for the palette; swipe down for the tab sheet.
  - Back returns to the last tab.
- `MainActivity.kt`: a `CoordinatorLayout` holding the engine view, the top bar, and one overlay `ComposeView` for the start screen and tab sheet.
  - Hide-on-scroll uses AC's `EngineViewScrollingDataBehavior` and `EngineViewClippingBehavior` together with GeckoView's dynamic toolbar height.
  - Don't switch to `EngineViewScrollingGesturesBehavior`. GeckoView blocks parent touch interception for gestures that start mid-page, so that behavior can hide the bar but never bring it back.
  - The back gesture goes back a page, or cancels an edit.
- `toolbar/`:
  - `TopBarView` hosts the Compose bar as a `ScrollableToolbar`.
  - `TopBar.kt` is the bar's UI. On the address field, a tap edits, a swipe down opens the tab sheet, and a sideways swipe switches tabs.
  - `AddressInput` decides between URL and search (Google) and formats the domain.
- `ui/`:
  - `Theme.kt` holds color tokens from the spec's Visual style table and the Inter font (bundled in `res/font`; its license is in `assets/licenses`).
  - `Favicons.kt` has the letter favicon placeholders and the space icons.
- `Settings.kt`: SharedPreferences values, not yet in any UI, meant to become user settings later.
  - `hideStatusBarWithTopBar` (default on) hides the status bar along with the bar.
  - `tabSwipeScope` (default `OpenTabs`) sets which tabs the address-field swipe moves through.
  - `quickTabLifetimeHours` (default 24, counted since last viewed) sets when Quick Tabs clear.
  - `startScreenAfterMinutes` (default 30) sets how long the app must be away before it reopens on the start screen.
  - `switchSpacesFromSheetHeader` (default on) lets a sideways swipe on the sheet header switch spaces.
  - `essentialsPerContainer` (default on) shows each space only the Essentials of its Zen container.
  - `lastStoppedAt` is app state, not a preference.
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
- **Scripted input:** `adb shell input -d <logical display> tap|swipe|text …`. The cover screen is logical display 0, and coordinates are in full-resolution pixels (1248×1972 on the cover). To place a tap on a popup, read its frame from `adb shell dumpsys window windows`.
  - Long press: `swipe x y x y 900`.
  - Hold a swipe partway: `motionevent DOWN|MOVE|UP x y`.
- **Test pages from the Mac:** serve them on `127.0.0.1:<port>` and run `adb reverse tcp:<port> tcp:<port>`. The Fold can then open `http://localhost:<port>/…`.

## Testing on the Fold

The user browses with this build on the Fold, so its tabs are real.

- **Back up before testing:** save `adb shell run-as app.enso.browser cat files/spaces.json` (and `shared_prefs/enso_settings.xml`) first.
  - Put backups in `device-backups/<date-time>/` in the project. That folder is git-ignored because it holds personal data.
  - Never put them in the session scratchpad, which is cleared between sessions; a backup was lost that way once.
- **Use your own test tabs:** create them, close only those, and check the user's tabs are intact afterwards.
- **Never write to the user's Zen data without a snapshot.** The spaces collection is their real desktop setup.
- **Restore if needed:** stop the app with `am force-stop`, then run `adb exec-in run-as app.enso.browser sh -c 'cat > files/spaces.json' < backup.json`.
- **Edge swipes:** a swipe that starts within about 60 px of the left or right edge is Android's back gesture. Start sideways swipes further in.
- **Faking time:**
  - Away time: edit `last_stopped_at` in the prefs file while the app is stopped.
  - Quick Tab expiry: edit a test tab's `quick.lastViewedAt` in `spaces.json`.
