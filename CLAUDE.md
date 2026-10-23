# Ensō

Android browser for the Galaxy Z Fold 8, built on GeckoView and Mozilla Android Components. It syncs Zen Browser's Spaces.

- **Spec:** [docs/SPEC.md](docs/SPEC.md). It is the source of truth for behavior, layout and the build order.
- **Mockups:** [docs/mockups/](docs/mockups/). Filenames start with the spec's board numbers (for example `4 · Tab sheet.png` is board 4). Look at the relevant boards before building any UI.

## How we work

- Build **one step of the spec's build order at a time**. Each step must build, install and run on the Fold before the next one starts.
- Don't build UI or features from later steps early, even as stubs.
- If the spec is unclear or silent, ask instead of guessing. The spec lists its own open questions at the end.
- Current step: **2, core browsing**. One tab, the tinted top bar, address entry, back/forward and hide-on-scroll on the cover screen. The inner screen shows the same layout stretched until step 7.

## Project

- Kotlin, Gradle (Kotlin DSL) with the wrapper, and a version catalog in `gradle/libs.versions.toml`. Application ID and package are `app.enso.browser`.
- Mozilla libraries come from `https://maven.mozilla.org/maven2/`. GeckoView comes in through `browser-engine-gecko`, so bumping `mozilla-components` bumps both together.
- Android Components keeps many modules as internal dependencies. If the compiler says it "cannot access" an AC class, declare that module (for example `concept-engine`) in the catalog yourself.
- AC 158 requires `compileSdk` 37.2, which is why `app/build.gradle.kts` uses the minor-version `compileSdk { }` block.
- The build is limited to `arm64-v8a`, the Fold's chip type.

## Code map

- `Components.kt`: the app-wide `GeckoEngine`, `BrowserStore` and `SessionUseCases`, created lazily by `EnsoApplication`. Only one `GeckoRuntime` may exist per process, and it lives here.
- `MainActivity.kt`: a `CoordinatorLayout` holding the engine view and the top bar.
  - Hide-on-scroll uses AC's `EngineViewScrollingDataBehavior` and `EngineViewClippingBehavior` together with GeckoView's dynamic toolbar height.
  - Don't switch to `EngineViewScrollingGesturesBehavior`. GeckoView blocks parent touch interception for gestures that start mid-page, so that behavior can hide the bar but never bring it back.
  - The back gesture goes back a page, or cancels an edit.
- `toolbar/`:
  - `TopBarView` hosts the Compose bar as a `ScrollableToolbar`.
  - `TopBar.kt` is the bar's UI.
  - `AddressInput` decides between URL and search (Google) and formats the domain.
- `ui/Theme.kt`: color tokens from the spec's Visual style table, and the Inter font (bundled in `res/font`; its license is in `assets/licenses`).
- `Settings.kt`: SharedPreferences.
  - `hideStatusBarWithTopBar` (default on) hides the status bar along with the bar. It is meant to become a user setting later.
  - `lastUrl` reopens the last page until the step 4 start screen exists.
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
