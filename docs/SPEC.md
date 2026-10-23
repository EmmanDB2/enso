# Ensō — Product & Design Spec

Oct 23, 2026 · @Emmanuel

## Overview

Ensō is an Android browser for the Galaxy Z Fold 8 that syncs Zen Browser's Spaces, folders, pinned tabs and Essentials from desktop. It is a personal companion app, not an official Zen product.

The full visual mockups live on the design canvas: [Zen Companion Cover UI](https://claude.ai/artifact/PtngsmFVkQojEQ5LcfJCcK). Board numbers below refer to it.

Core principles:

- **Content first.** On the cover screen the page gets the whole screen; the only chrome is one top bar that hides on scroll.
- **In and out.** Phone browsing is mostly quick lookups, so new searches open as temporary Quick Tabs instead of cluttering Spaces.
- **Zen-native on the big screen.** When unfolded, the layout copies Zen's desktop sidebar, resized for touch.
- **One system.** Both screens use the same data, the same space tint and the same row style, so folding never makes anything jump.

## Platform and architecture

Ensō is a standard Android app in Kotlin built on Mozilla's published libraries, so there is no need to compile Gecko. It builds on a Mac with Android Studio and Gradle.

&#91;embedded content: Ensō architecture · sync, local store, two UIs, one engine\]

Synced Spaces flow from Zen through the Mozilla account into a local store that both screens read; Quick Tabs never leave the phone.

| Piece | Role |
| --- | --- |
| GeckoView | Page rendering and WebExtensions support |
| Android Components | Tabs, Mozilla account sign-in, sync, favicons (browser-icons module), add-ons UI |
| Zen Spaces sync | Zen syncs Spaces, folders and tabs through the Mozilla account when "Sync your Spaces across devices" is on in Settings > Sync ([Zen release notes](https://www.zen-browser.app/release-notes)). The data format is not documented, so it must be worked out while building |
| Reference app | [Dejavu](https://alternativeto.net/software/dejavu/about/), an Android Gecko browser that already syncs Zen workspaces, containers and pinned tabs. Check its license before reusing code |
| Favicons | Synced tab records include icon URLs; the browser-icons module fetches and caches them |

## Cover screen: browsing (boards 1–2)

The cover screen shows one top bar and the page, nothing else. There is no bottom bar, tab counter, home button or assistant button.

- **Top bar:** full width, filled with the current space's tint (the same frosted color as the inner sidebar). It holds a translucent address field (lock icon, domain, reload) and one overflow menu button.
- **Load progress:** a 2 px line in the space's accent color along the bar's bottom edge.
- **Scrolling:** the bar slides up and out when scrolling down, and returns on scroll up or a tap on the top edge.
- **Tabs:** swipe sideways on the address field to move between tabs in the current space.
- **Tab sheet:** swipe down from the address field to open the tab sheet (see below). The bar animates into the sheet's current-tab row.

## Cover screen: space menu (board 3)

Spaces switch through a swipe-up arc menu that fans out from the thumb. The menu shows spaces only; tabs live in the tab sheet.

1. Hold for about 300–500 ms in the trigger zone (lower part of the right edge, or the address field when visible). A haptic tick confirms the menu is open.
2. The arc appears with the thumb as its pivot near the bottom right. Each space shows its Zen icon and name, with the name inside the curve so it never clips at the left edge.
3. Sliding up and down moves the selection along the arc, with a haptic tick per space. The selected space gets a white ring and bold name, and a dotted trail runs from the thumb to it.
4. The whole page tints to the selected space's color while hovering.
5. Release to switch. Slide off the arc to cancel.

Gesture rules:

- The trigger is a hold, then vertical movement, so Android's horizontal back gesture is not triggered in normal use.
- While the menu is open, exclude the arc area from system gestures, then release the exclusion on lift. Android caps edge exclusion at about 200 dp of height per edge, so keep the trigger strip in the lower part of the screen.
- Space icons come from Zen's sync data (icon or emoji the user set on desktop).
- The arc also serves as the "Add to another space" picker for Quick Tabs.

## Cover screen: tab sheet (board 4)

The tab sheet opens anchored on the current tab, with pinned items above it and open tabs below. It uses the same layout and style as the inner sidebar.

Background: a light frosted blur of the page underneath, tinted with the space color, so it feels like a layer just above the tab you were on. Implementation tip: capture the page with GeckoView's pixel capture when the sheet opens, then blur and tint that bitmap rather than blurring live.

Order, top to bottom:

1. Space icon and name, with the tab count.
2. Essentials as a 2×2 grid of favicon tiles (shared across spaces, like Zen).
3. Pinned tabs and folders. Folders are collapsed by default; tap to expand in place with indented tabs.
4. A thin divider line, shown only when pinned items exist. No "Pinned" or "Open" labels.
5. The current tab as a white card with title and URL.
6. Other open tabs, including Quick Tabs (gold outline).
7. A search field fixed at the bottom for thumb reach.

Interactions:

- Swipe a row sideways to close it (red close area revealed behind the row).
- Tap a folder to expand or collapse it.
- Hold a Quick Tab to open its menu (see Quick Tabs).
- If the current tab is pinned or in a folder, it still becomes the divider row and shows a pin or breadcrumb (for example "School › Canvas").

## Quick Tabs (boards 4, 4b, 5, 6)

Every new search or typed URL opens as a Quick Tab: a temporary tab that clears itself unless you keep it. This keeps quick lookups out of your synced Spaces.

| Rule | Behavior |
| --- | --- |
| Created by | New searches and URLs typed from the start screen or search field |
| Placement | Mixed in with open tabs in the current space, below the current tab |
| Look | 2 px gold outline (#F2C94C on dark surfaces, #E0A800 on light ones); a gold ring on its favicon in search results |
| Sync | Never synced to Zen desktop while it is a Quick Tab |
| Expiry | Clears automatically after a set time (default to decide, 12 or 24 hours) |
| Keep it | Hold the row, then Add to \[current space\] or Add to another space |

Hold menu (board 4b): holding a Quick Tab lifts the row and dims everything else. A white menu shows a header ("Quick Tab · clears in 9 hours"), then:

1. **Add to \[current space\]**, the primary action.
2. **Add to another space**, which opens the arc space menu as a picker.
3. **Close tab**, in red, below a divider.

Once added to a space, the tab loses its outline, becomes a normal tab and syncs to Zen.

## Start screen and search (boards 9, 9b, 9c)

When no tab is open, or after the app has been away long enough (threshold to decide), Ensō shows a blank page with a faint space tint, like Zen's empty state. Spaces stay one gesture away.

Tapping the blank page, or tapping New Tab, opens a command palette modeled on Zen's:

- A search field ("Search or type a URL").
- "Search the web" with the default engine.
- Open tabs that match, each with a "Switch to Tab" chip. Quick Tabs show their gold favicon ring.
- History matches with a clock icon.
- A footer line: "New searches open as Quick Tabs."

On the cover screen the palette sits in the upper half so the keyboard fits below it. On the inner screen it is centered over the blank page card, with the sidebar showing Essentials, folders and a highlighted New Tab row.

## Inner screen (boards 5–8)

Unfolded, Ensō copies Zen's desktop sidebar layout with rows at least 44 px tall for touch. There is no separate top address bar; the URL lives in the sidebar. The page sits as a rounded card inset from the tinted background.

| Mode | What it shows |
| --- | --- |
| Sidebar open (6) | Collapse toggle, back, forward, reload; URL field; Essentials 2×2; space name; pinned tabs and folders; divider; New Tab; current tab as a white pill; open tabs and Quick Tabs; media player card; space icons and + at the bottom |
| Collapsed rail (5, default) | 64 px rail: expand toggle, a search button that opens the URL field, Essentials, divider, pinned and folder icons, divider, tab favicons (current one as a white tile), space icon at the bottom |
| Split view (7) | Two page cards side by side. Drag a tab to the right half to split. The focused pane has an accent outline; the rail shows the pair as one stacked tile |
| Glance (8) | Long-press a link to open a floating preview over a blurred page, with Close, Open as tab and Split buttons beside it |

Inner-screen gestures:

- Swipe sideways on the sidebar to switch spaces, matching Zen's trackpad swipe on desktop. Space icons at the bottom also switch.
- Tap the rail's toggle to expand or collapse the sidebar.

Fold continuity: the current tab, space and scroll position carry over when folding or unfolding. The cover tab sheet and the inner sidebar read the same data, so nothing reorders. Flex mode is out of scope.

## Visual style

One light, frosted style runs across both screens, tinted by the current space. These are the mockup values; treat them as a starting palette.

| Token | Value | Used for |
| --- | --- | --- |
| Surface base | #ECE7EA | Sidebar, tab sheet, top bar |
| Space tint | Space accent at 16% over the base | Same surfaces, so each space reads as its own color |
| Page | #FAF9F7 | Page area and page cards |
| Text primary | #26232B | Names, titles |
| Text secondary | #5F5B66 | Counts, URLs, muted rows |
| Field fill | #26232B at 7% | Address field, Essentials tiles, search |
| Current tab | #FFFFFF with a soft shadow | Current-tab card or pill |
| Quick Tab outline | #F2C94C (dark) / #E0A800 (light) | Quick Tab rows and rings |
| Close action | #D6404A (swipe) / #C4323C (menu text) | Closing tabs |
| Space accents (examples) | #4F7CFF, #9B7BFF, #2BB5A0, #F0A43A, #FF7A6B | Folder icons, progress line, tints |

Shapes and type:

- Rows 44–48 px tall with 12 px corners; current-tab card 14 px corners; menus and the palette 16–18 px corners.
- Font in mockups: Inter, 15–16 px for rows, 22–24 px for the space name.
- Icons: thin line icons (about 1.8 px stroke). Real favicons replace the letter placeholders, inside a small neutral circle so white icons stay visible; letters remain as the fallback.
- The space menu (board 3) is the one dark overlay: a dark scrim with the space tint.

## Extensions

Ensō supports WebExtensions through GeckoView, starting with uBlock Origin.

- **Bundled:** ship uBlock Origin with the app and install it on first launch through GeckoView's built-in extension install path.
- **From the add-on store:** allow installing other Mozilla-signed extensions by URL from addons.mozilla.org.
- **UI:** use the Android Components add-ons module for the extension list, popups and settings pages.
- **Placement:** extensions live under the overflow menu, and an extension's popup opens as a sheet. No extension icons in the top bar.

## Build order

Build the browser core first, then sync, then the signature interactions. Each step should run on the Fold before the next starts.

1. **Project setup:** Android Studio project in Kotlin with Gradle, GeckoView and Android Components from Mozilla's Maven repository. Builds and installs on the Fold over wireless debugging.
2. **Core browsing:** one GeckoView session, the tinted top bar, address entry, back and forward, hide-on-scroll.
3. **Tabs and local spaces:** a local data model for spaces, Essentials, pinned tabs, folders and tabs; the cover tab sheet; swipe-to-close.
4. **Quick Tabs:** default for new searches, gold outline, expiry, the hold menu, the start screen and search palette.
5. **Mozilla account and Zen sync:** sign-in, then read and write Zen's Spaces, folders and tabs. Study how Dejavu does this and check its license before reusing code.
6. **Space menu:** the swipe-up arc, haptics, page tint and gesture exclusion.
7. **Inner screen:** collapsed rail, full sidebar, sidebar swipe for spaces, fold continuity.
8. **Split view and Glance.**
9. **Extensions:** bundled uBlock Origin and the add-ons UI.
10. **Polish:** real favicons with letter fallbacks, blur snapshot for the tab sheet, animations, dark mode.

## Open questions and naming

- [ ] Quick Tab expiry: 12 hours, 24 hours, or user-set?
- [ ] How long away before the app reopens on the start screen instead of the last tab?
- [ ] Exact format of Zen's synced Spaces data (to confirm while building sync).
- [ ] Does dark mode follow the system, or a per-space setting?
- [ ] Check the Play Store for the name "Ensō" before publishing.

Naming and trademarks: Zen's code is MPL 2.0, which allows forking but grants no rights to the Zen name or logo. The same applies to "Firefox." Ensō should not use either name or logo, and can say it "syncs with Zen Browser" in its description. Ask the Zen team before using their name in any other way. This is not legal advice.
