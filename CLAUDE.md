# Ensō

Android browser for the Galaxy Z Fold 8, built on GeckoView and Mozilla Android Components. It syncs Zen Browser's Spaces.

- **Spec:** [docs/SPEC.md](docs/SPEC.md). It is the source of truth for behavior, layout and the build order.
- **Mockups:** [docs/mockups/](docs/mockups/). Filenames start with the spec's board numbers (for example `4 · Tab sheet.png` is board 4). Look at the relevant boards before building any UI.

## How we work

- Build **one step of the spec's build order at a time**. Each step must build, install and run on the Fold before the next one starts.
- Don't build UI or features from later steps early, even as stubs.
- If the spec is unclear or silent, ask instead of guessing. The spec lists its own open questions at the end.
- Current step: **3, tabs and local spaces**. Local spaces model, cover tab sheet (board 4), swipe-to-close, address-field swipes. There's one space ("Home") until sync (step 5) and the arc menu (step 6). The inner screen shows the cover layout stretched until step 7.

## Project

- Kotlin, Gradle (Kotlin DSL) with the wrapper, and a version catalog in `gradle/libs.versions.toml`. Application ID and package are `app.enso.browser`.
- Mozilla libraries come from `https://maven.mozilla.org/maven2/`. GeckoView comes in through `browser-engine-gecko`, so bumping `mozilla-components` bumps both together.
- Android Components keeps many modules as internal dependencies. If the compiler says it "cannot access" an AC class, declare that module (for example `concept-engine`) in the catalog yourself.
- AC 158 requires `compileSdk` 37.2, which is why `app/build.gradle.kts` uses the minor-version `compileSdk { }` block.
- The build is limited to `arm64-v8a`, the Fold's chip type.

## Code map

- `Components.kt`: app-wide singletons, created lazily by `EnsoApplication`: `GeckoEngine`, `BrowserStore`, the use cases, `SpacesStore` and `TabManager`. Only one `GeckoRuntime` may exist per process, and it lives here.
- `spaces/`: the local spaces model, shaped after Zen so step 5 can sync it.
  - Structure: `SpacesState` holds the spaces and the shared Essentials. A `Space` holds its pinned items (tabs and folders, in order) and its open tabs.
  - Edits are pure functions in `SpacesEdits.kt`.
  - `SpacesStore` saves the model to `files/spaces.json` 500 ms after a change, and right away in `onStop`.
- `TabManager`: the only place tabs are opened, selected, closed or unloaded. Don't add or remove tabs in the `BrowserStore` directly.
  - The model and the `BrowserStore` share tab IDs. A tab exists in the BrowserStore only while it's loaded, so pinned tabs and Essentials can sit unloaded.
  - It copies URL and title changes, page-opened tabs (`WindowFeature`) and selection back into the model.
- `tabsheet/`:
  - `TabSheet.kt` is the cover tab sheet (board 4), drawn over the page in its own `ComposeView`.
  - `TabMenu.kt` is the hold menu (board 4b style) for pin, unpin, folders and Essentials. These actions stand in for organizing tabs on the phone until sync.
- `MainActivity.kt`: a `CoordinatorLayout` holding the engine view, the top bar and the tab sheet.
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
- `Settings.kt`: SharedPreferences switches, not yet in any UI, meant to become user settings later.
  - `hideStatusBarWithTopBar` (default on) hides the status bar along with the bar.
  - `tabSwipeScope` (default `OpenTabs`) sets which tabs the address-field swipe moves through.
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
