# Ensō — Product & Design Spec

Oct 23, 2026 · @Emmanuel

## Overview

Ensō is an Android browser for the Galaxy Z Fold 8 that syncs Zen Browser's Spaces, folders, pinned tabs and Essentials from desktop. It is a personal companion app, not an official Zen product.

The full visual mockups live on the design canvas: [Zen Companion Cover UI](https://claude.ai/artifact/PtngsmFVkQojEQ5LcfJCcK). Board numbers below refer to it.

Core principles:

- **Content first.** On the cover screen the page gets the whole screen; the only chrome is one bottom bar that hides on scroll.
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
| Favicons | Zen's tab records carry each tab's icon (mostly as `data:` images), so synced tabs show theirs before they load. Other tabs get the page's own icon from the browser-icons module, which also fetches and caches icons for tabs that aren't loaded |

## Cover screen: the bottom bar (boards B1–B4)

The cover screen shows the page and one bar at the bottom, in the style of Arc Search. There's no top bar: the page runs to the top edge, and the URL only shows in the actions sheet. (Boards 1 and 2 show the earlier top bar.)

**The bar (B1):** one full-width surface along the bottom edge, 90 dp tall with the gesture handle's room, with rounded top corners (about 26 dp), the space's tint, a soft upward shadow and a small grab handle at the top. There's no divider line above its row, in any state. The row (about 56 dp) holds:

- **The Quick Tabs launcher:** two small live thumbnails of the current space's Quick Tabs, as in Arc Search. The tab before the current one sits behind, tilted about −9°, and the current one sits on top, tilted about 5°, each with a soft drop shadow. No outline and no count. A card shows its tab's favicon until the page has a thumbnail. Kept tabs never show here. Tap it for the Quick Tabs view (B2). With no Quick Tabs it's dimmed.
- **The "+" pill:** bottom search (B4), for a new Quick Tab.
- **The chevron:** the actions sheet (B3).

Behavior:

- **Load progress:** a 2 px line in the space's accent along the bar's top edge.
- **Scrolling:** the bar slides off the bottom edge when scrolling down, and returns on scroll up, when a page starts loading, or on a tap along the bottom edge. A swipe down on the bar hides it too.
- **Status bar:** hidden while you browse, so the page reaches the top edge. It shows over the tab sheet, the start screen and the bar's panels, and with a swipe down from the top. A setting can instead keep it showing over the page, or put it over a strip of the space's colors that slides away with the bar.
- **Tab sheet:** swipe up on the bar or its handle.

**Panels:** the actions sheet and bottom search are the bar's surface growing upward; there are no separate floating sheets. The page behind dims. Back, a swipe down on the handle, or a tap on the dimmed page closes a panel.

**Quick Tabs (B2):** a full-screen view in the style of Arc Search, with no sheet.

- **Opening:** tapping the launcher zooms out. The current page shrinks into the focused card (about 70% of the screen's width, 10 dp corners) while the background fades to the space's gradient and the neighbouring cards slide in from the sides, over about 300 ms on a spring with no overshoot. The bar dissolves into the floating buttons.
- **Cards:** real page screenshots, plain white, no borders. The focused card is centered. Older Quick Tabs stack behind it to the left, and newer ones lie on top of older ones, so a card coming in slides over the stack. The next one peeks in from the right edge. Each card's favicon sits just above its top-left corner and moves with it, stepping slightly higher the further back it sits; the focused card's title sits beside its favicon. Cards show their own page's thumbnail (the page on screen as the view opens).
- **Gestures:** swipe left or right to move through the tabs as one smooth strip that follows the finger; on release it glides onto a card. A flick moves on at least one card, and only a fast one two. Tap a card to zoom back into it (the opening animation reversed). Swipe a card up to close it, and the next one slides into its place. Back zooms into the focused card.
- **Floating buttons**, left to right:
  - **Clear All** (small and lighter, with an archive icon) closes every Quick Tab in the space, then shows "3 Quick Tabs cleared · Undo" for about 5 seconds. Undo opens their pages again.
  - **[space icon] Save**, the big solid white button in the middle, keeps the centered tab in the current space and zooms into it. Holding it picks another space on the space wave.
  - **The chevron (up)** opens the actions sheet for the centered tab.
- No expiry times show here.

**Actions sheet (B3):**

- The URL row: back and forward on the left, the domain centered, then copy link and reload (stop while loading) on the right. This is the only place the URL appears. Tapping the domain opens bottom search on the URL, which then loads in the current tab.
- Reader, Find, Keep and Share. Keep shows the current space's icon: on a Quick Tab it keeps the tab in that space (holding it picks another space); on a kept tab it shows "Kept", and tapping it makes the tab a Quick Tab again, which takes it out of the space (in Zen too). Pinned tabs and Essentials show "Kept", grayed out. Reader is grayed out on pages that aren't articles (see Reader). Find opens a find bar on the keyboard: the field, which match is showing ("3/12"), previous and next, and ✕, which clears the highlights. The page stays undimmed, so the matches show. Unfolded, Find is the first item in the sidebar's ⋮ menu (on any page), and the same find bar floats along the top of the page card, in the space's colors; Back or ✕ closes it, and so does folding or unfolding.
- Extensions and Site Settings (a standard gear). Site Settings opens the site's settings, or All settings when there's no site on screen.
- The bar's row: the launcher, "+", and the chevron as a dark ↓ that closes the sheet.

**Bottom search (B4)**, in the style of Safari: the sheet sits directly on the keyboard, with no bar row.

- At the top, the search field: a search icon, "Search or enter address", and a mic inside it (Android's voice typing, when a recognizer is installed; what's said is searched). A ✕ button beside it closes search.
- Before typing, "Open tabs" lists the space's open Quick Tabs, latest first, at most 4, each with its favicon, title and "Switch to Tab".
- While typing: matching open tabs (at most 4), then matching history, then Google's search suggestions. Suggestions take the rows history leaves, so they take over as the history thins out. A setting turns the suggestions off, since they send what's typed to Google.
- The Quick Tab is made when the search is submitted, so ✕ leaves nothing behind.

## Cover screen: space menu (board 3)

Spaces switch through an edge wave, in the style of Niagara Launcher. The menu shows spaces only; tabs live in the tab sheet.

1. Hold for about 300 ms in the trigger zone: the right edge, from the bottom bar's top edge up to the status bar (down to the gesture handle while the bar is scrolled away). A haptic tick confirms the menu is open.
2. Every space appears in a vertical list along the right edge, in Zen's order from top to bottom. Each row shows the space's Zen icon at the edge and its name to the left. The current space starts under the thumb.
3. Moving the thumb up and down sends a wave along the list. The goal is a calm, satisfying switcher:
   - The rows stay still, about 72 dp apart. The wave follows the thumb one-to-one, one space per 72 dp of travel.
   - The bulge follows the thumb on a soft, damped spring, with no overshoot.
   - The rows sit on a line held at the top and bottom of the screen and pushed out by the thumb, like a taut string.
   - The space under the thumb bulges farthest from the edge (about 50 dp), and its neighbors bulge less.
   - The bulge is a smooth falloff around the thumb's exact position, rounded at its peak, so it glides between rows instead of stepping from one to the next.
   - A haptic tick marks each change of the hovered space. The hovered space holds on a little past the halfway point between rows, so a resting thumb doesn't flicker between two.
4. The look:
   - **Background:** the page is captured as the wave opens and shown blurred under a light frost. On top goes the hovered space's tint at about 24% opacity: its Zen theme gradient from top to bottom, or its accent as a single color when the theme has none. The tint cross-fades over about 150 ms when the hovered space changes.
   - **Ink stroke:** a tapered ink brush stroke follows the wave. It's about 16 dp wide at the thumb and tapers (Gaussian) to nothing about two rows above and below, in dark ink at about 20% opacity. It moves with the thumb.
   - **Hovered space:** it sits in a frosted white pill (about 78% white, soft shadow, fully rounded). The pill holds the name at about 22 sp semibold and the space's icon, with no ring around it. The pill's colors are theme tokens, so dark mode can change them.
   - **Other spaces:** dark gray text with no pill, at about 16, 13 and 12 sp by distance. The text gets lighter farther from the thumb, and names sit 10 dp from their icons.
   - **Opening:** the labels fade in.
5. Release to switch. To cancel, slide past either end of the list or away from the edge.

Gesture rules:

- The trigger is a hold, then vertical movement, so Android's horizontal back gesture is not triggered in normal use.
- While the menu is open, exclude the list's stretch of the edge from system gestures, then release the exclusion on lift. Android caps edge exclusion at about 200 dp of height per edge.
- The trigger strip can still run the whole edge above the bottom bar. Android only checks the exclusion when a touch starts, and the resting thumb is what stops the back gesture during a hold.
- On the Fold, Samsung's Edge panel handle sits on the upper part of the right edge. Its touches go to the system, so the strip works above and below it but not on it.
- If there are more spaces than fit, the list scrolls along with the wave.
- A left-handed mode setting mirrors the wave and its trigger strip to the left edge.
- Space icons come from Zen's sync data (icon or emoji the user set on desktop).
- The wave also serves as the "Add to another space" picker for Quick Tabs.

## Cover screen: tab sheet (board 4)

The tab sheet opens with a swipe up on the bottom bar, anchored on the current tab, with pinned items above it and open tabs below. It uses the same layout and style as the inner sidebar.

Background: a light frosted blur of the page underneath, tinted with the space color, so it feels like a layer just above the tab you were on. Implementation tip: capture the page with GeckoView's pixel capture when the sheet opens, then blur and tint that bitmap rather than blurring live.

Order, top to bottom:

1. Space icon and name, with the tab count.
2. Essentials as a 2×2 grid of favicon tiles (shared across spaces, like Zen).
3. Pinned tabs and folders. Folders are collapsed by default; tap to expand in place with indented tabs.
4. A thin divider line, shown only when pinned items exist. No "Pinned" or "Open" labels.
5. The current tab as a white card with title and URL.
6. Other open tabs. Quick Tabs live in the bottom bar's stack instead; a setting lists them here too (gold outline).
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
| Keep it | "Keep in \[space\]" in the Quick Tabs view or the actions sheet (hold it for another space), or hold its row in the tab sheet or sidebar for Add to \[current space\] or Add to another space |

Hold menu (board 4b): holding a Quick Tab lifts the row and dims everything else. A white menu shows a header ("Quick Tab · clears in 9 hours"), then:

1. **Add to \[current space\]**, the primary action.
2. **Add to another space**, which opens the space wave as a picker.
3. **Close tab**, in red, below a divider.

Once added to a space, the tab loses its outline, becomes a normal tab and syncs to Zen.

## Start screen and search (boards 9, 9b, 9c)

When no tab is open, or after the app has been away long enough (threshold to decide), Ensō shows a blank page with a faint space tint, like Zen's empty state. Spaces stay one gesture away.

### The ensō fidget (boards F1–F4)

In the middle of the blank page sits the ensō, about 240 dp, something to play with while you think. A setting, "Show ensō on new tabs" (on by default), hides it.

- **The stroke** is generated, not drawn from the SVG, so it can move. In the mark's 512×512 grid, centered on (256, 256), it sweeps from 124° to 432°, in about 140 samples:
  - Radius: 152 + 6t + 3·sin(2.2πt).
  - Width: taper(t) = ((74 − 2.5)·(1 − t)^1.15 + 2.5) × (1 + 0.06·e^(−t/0.15)).
  - The outline runs along the outer and inner edges, with a round cap at the thick start (the head).
  - It matches enso-mark.svg's main stroke. The two thin dry-brush strands near the tail come from the SVG as they are.
  - The stroke and strands are one layer, faded as a whole, so where they overlap the ink doesn't darken.
- **Look (F1):** the ink at about 25% so it doesn't draw the eye, breathing very slowly unless Android's "Remove animations" is on. While touched, about 85%.
- **Touch:** only touches that start on the ensō's circle are its own. A tap anywhere else on the blank page still opens search.
- **Spin (F2):**
  - Dragging around the center turns it by the finger's angle change.
  - Let go, and it keeps spinning and slows smoothly (friction about ×0.975 per frame at 60 fps).
  - Once it's slowed to a slow, even speed (40° a second), it carries on at that speed in the same direction until it's back at rest as the logo, easing in over the last few degrees. A gentle release with no flick goes home the same way, in the direction of the drag, and so does an ensō caught mid-spin when it's let go. A touch stops it on the way.
  - Spinning fast, two faint copies trail it, fading as it slows.
  - A haptic low tick for every 30° turned, never closer than about 25 ms apart.
- **Hold (F3, F4):** a 350 ms press that doesn't move more than about 6 dp.
  - **The head extends:** the thick head keeps sweeping back round (anticlockwise) into the thin tail and on, faster as it goes, laying new ink at the head's own width and radius. The stroke itself never changes or morphs. The gap closes as the head reaches the tail's end, about 1.3 s in. By about 3.5 s the head has gone a full loop, and the ensō reads as a solid circle because it has been painted round over itself.
  - **Glow:** a soft radial glow in the space's colors behind it, rising as the head sweeps, up to about 50% at the full loop. The space's primary color is in the middle, fading outward through its other theme colors (Zen allows up to three); a space with one color glows in that color alone.
  - **Haptics:** a gentle rumble rising as the head sweeps, a click as the gap closes, and a heavier click when the loop is complete.
  - **The loop lets go:** as the loop completes and the heavier click plays, it releases by itself, held or not. The whole ensō bounces, the head unwinds back to the open ensō, and the unwinding sends it spinning on with that momentum, about a turn, to settle at its resting angle.
  - Letting go before the loop completes draws the head back to the open ensō: a quick ease-out, no overshoot.
- **Always spinnable:** a drag spins it at any point: before a hold starts (which then doesn't start), during one, after the loop has let go, or while it's still spinning or on its way home.
  - A hold isn't interrupted by spinning: it keeps closing as the finger turns it, and only lifting the finger stops it. During a hold the turning starts after a slightly bigger move (about 14 dp), so a finger's natural wobble doesn't jiggle it.
  - If the loop completes while the finger is turning it, it still bounces and unwinds, but the turning stays the finger's. On release, it carries on with the throw and settles home.

On the cover, tapping the blank page opens bottom search (board B4). On the inner screen, tapping it or New Tab opens a command palette modeled on Zen's:

- A search field ("Search or type a URL").
- "Search the web" with the default engine.
- Open tabs that match, each with a "Switch to Tab" chip. Quick Tabs show their gold favicon ring.
- History matches with a clock icon.
- A footer line: "New searches open as Quick Tabs."

The palette is centered over the blank page card, with the sidebar showing Essentials, folders and a highlighted New Tab row. (Board 9b shows the cover's earlier palette, which bottom search replaced.)

## Inner screen (boards 5–8)

Unfolded, Ensō copies Zen's desktop sidebar layout, a little denser than the cover: rows, fields and tiles are 40 dp tall. There is no separate top address bar; the URL lives in the sidebar.

- **Page card:** the page sits as a rounded card inside a thin border of the space's colors, 4 dp on every side (between the page and the sidebar too). The status bar stays hidden, as on the cover when the bar slides away, so the border runs to the top.
- **Border toggle:** a setting turns the border off, leaving the page flush beside the sidebar with square corners.

| Mode | What it shows |
| --- | --- |
| Sidebar open (6) | Collapse toggle, back, forward, reload; URL field with copy link and the ⋮ menu (where Zen has its copy link and site settings buttons); Essentials 2×2; space name; pinned tabs and folders; divider; New Tab; current tab as a white pill; open tabs and Quick Tabs; media player card; space icons at the bottom. There's no + for a new space, because the phone can't create spaces yet |
| Collapsed rail (5, default) | 64 px rail: expand toggle, a search button that opens the URL field, Essentials, divider, pinned and folder icons, divider, tab favicons (current one as a white tile), space icon at the bottom |
| Split view (7) | Two page cards side by side. Drag a tab to the right half to split. The focused pane has an accent outline; the rail shows the pair as one stacked tile |
| Glance (8) | Long-press a link to open a floating preview over a blurred page, with Close, Open as tab and Split buttons beside it |

Inner-screen gestures:

- Swipe sideways on the sidebar to switch spaces, matching Zen's trackpad swipe on desktop. Space icons at the bottom also switch.
- A sideways swipe on a tab row still closes or unloads that tab, as in the tab sheet. Swipes anywhere else on the sidebar, and anywhere on the rail, switch spaces.
- Tap the rail's toggle to expand or collapse the sidebar. The sidebar stays open or closed the way it was left.
- Opening and closing animate the sidebar's edge, with the rail and sidebar cross-fading.
- Drag the sidebar's right edge to resize it (220–480 dp, at most 60% of the window). The width is remembered.
- The rail's search button opens the sidebar on its URL field, and the sidebar closes again when the edit ends. Tapping a folder in the rail opens the sidebar with that folder expanded. The rail's space icon opens the sidebar.
- New Tab shows the start screen inside the page card with the search palette centered on it (board 9c). New Tab is the highlighted row meanwhile.
- The cover's edge wave is a cover gesture; on the inner screen the sidebar switches spaces.
- Media card: the tab playing media, or the one paused last, with previous, play or pause, next and mute. Tapping its favicon opens that tab.
  - Previous and next skip tracks when the page offers them, become rewind and fast-forward when it only allows seeking, and are grayed out otherwise.

Fold continuity: the current tab, space and scroll position carry over when folding or unfolding. The cover tab sheet and the inner sidebar read the same data, so nothing reorders. Flex mode is out of scope.

Split view (board 7), as in Zen:

- A split is a group of a space's tabs. Its tabs stay where they live (pinned, in a folder or open).
- Selecting a tab in a split shows the whole split, with that page focused. Tapping another page focuses it. Selecting a tab outside the split shows that tab alone, and the split comes back the next time one of its tabs is selected.
- To split, hold a tab in the sidebar or rail, then drag it onto the right half of the page. The menu the hold opened gives way to the drag. Two open tabs, or two pinned tabs, end up side by side in the list; a pinned tab and an open one each stay where they are. Essentials can't be split.
- In the sidebar a split is one row with each tab in its own part, and in the rail its tabs are stacked on one tile. Each part selects, holds and swipes like a row of its own.
- The hold menu's Unsplit tabs ends a split, and so does closing either tab of a two-tab split.
- Splits from Zen keep Zen's layout: side by side, stacked, or a grid (two columns, the first two pages on the left), up to four pages.
- Drag the gap between the pages to resize them (each at least 240 dp); hold it to make them equal again. The pages resize when the drag stops. Stacked pages resize up and down, and a grid's pages stay equal. Each split remembers its sizes on the phone; they don't sync.
- Pages in a split ask for mobile sites, since each is about as narrow as the cover. A setting turns that off.
- Folding shows the focused page on the cover, where the tab sheet's current-tab card shows the whole split. Unfolding brings the split back.
- Splits sync with Zen both ways: made or ended on either side, they show on the other. A split with a Quick Tab in it, or with a pinned tab and an open one (which Zen's split view can't hold), stays on the phone. Where both sides change a split at once, Zen's wins.

Glance (board 8), on both screens:

- Holding a link (or a linked image) opens a floating preview of it over the page, which blurs and dims behind it. The card shows the site's domain above the page.
- ✕, Back, or a tap around the card closes it. Open as tab makes it a tab just below the one it came from (a Quick Tab when that one is). Split, unfolded beside a single page, puts it on the right as a split.
- A preview stays out of your tabs and sync until it's opened as a tab or split.
- Unfolded, the card sits over the page card with its buttons in a column beside it, and the sidebar stays as it is. On the cover, the card fills most of the screen with the buttons in a row below it, within reach of the thumb, and there's no Split.
- Previews ask for mobile sites, since the card is narrower than the page.
- Folding or unfolding keeps it open, fitted to the new screen.

Desktop sites: while the sidebar layout shows, tabs ask for desktop sites. A setting turns this off. Folding or unfolding doesn't reload anything; each page switches the next time it loads, and new tabs start in the right mode.

Page zoom: pages open at 80% by default on both screens, so more fits. It's a setting. GeckoView only offers it engine-wide and reads it at startup, so it can't differ between the screens, and a change applies after a restart.

Unfolded portrait: a setting chooses the layout while the unfolded screen is held in portrait. The default is the cover's top-bar layout; the other choice is the sidebar and page card, as in landscape. Rotating keeps the page, which resizes to fit.

## Visual style

One light, frosted style runs across both screens, tinted by the current space. These are the mockup values; treat them as a starting palette.

| Token | Value | Used for |
| --- | --- | --- |
| Surface base | #ECE7EA | Sidebar, tab sheet, bottom bar |
| Space tint | Space accent at 16% over the base; from step 7, the space's Zen theme gradient when it has one | Same surfaces, so each space reads as its own color |
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
- Icons: thin line icons (about 1.8 px stroke). Real favicons replace the letter placeholders, drawn on their own with no circle behind them; only an icon that would vanish gets a circle (a mostly white one in light mode, a mostly black one in dark). Letters remain as the fallback.
- The space menu (board 3) is a frosted overlay: the blurred page under a light frost and the space's tint.
- **Motion:** rows fade in and out and slide into place as tabs open, close or move, and as folders open and close; cards (the sync sheet, prompts, the Extensions card) fade in with a slight scale; the start screen and pages cross-fade; a favicon fades in over its letter once it loads.
- **App icon:** an adaptive icon from docs/brand/enso-icon.svg: the ensō mark in #1C1B1F on a solid #F1EEE6 ground, sized as in the brand icon and inside the safe zone, so circle and squircle masks never cut it. A monochrome layer of the mark makes Android's themed icons work. All of it is vector drawables.
- **Dark mode** follows Android's dark theme, with a setting to override it (system, light or dark). Every token above gets a dark value, and web pages are asked for their dark version too. Dark surfaces use Zen's own dark base (rgb 23, 23, 26) under the space colors, so a space looks as it does in Zen's dark mode.

## Extensions

Ensō supports WebExtensions through GeckoView, starting with uBlock Origin.

- **Bundled:** uBlock Origin ships in the app as Mozilla's signed package from addons.mozilla.org (with its GPL-3.0 license in the app's licenses) and installs on first launch, so it works offline. After that it's an ordinary extension: it updates from addons.mozilla.org, and if the user turns it off or removes it, it stays that way.
- **From the add-on store:** the Extensions list ends with a "Find more add-ons here" link, which opens addons.mozilla.org's Android site in a Quick Tab. There's no list of recommended extensions in the app. The site always gets its mobile version, even unfolded: its desktop version lists Firefox's desktop add-ons, many of which don't work on Android.
- **Permission prompt:** installing from the site asks first, in an Ensō-style card over the screen: the extension's icon and name, what it will be able to do (in Firefox's wording), and what its developer says it collects, with Cancel and Add. A short notice confirms the install, and a failed one says why. Optional permissions an extension asks for later get the same card (Don't allow / Allow).
- **Updates:** each extension is checked for updates once a day. An update that wants more permissions asks first (Not now / Update), and the current version stays until the user agrees.
- **UI:** Ensō-style screens on Android Components' extension support and add-ons module. Each extension shows its icon, name, toolbar badge (such as uBlock Origin's count) and an on/off switch. Tapping one opens its popup; holding it offers Settings and Remove.
- **Popups and settings pages** open in a sheet: on the cover, the bottom bar's surface grows with the page in it; unfolded, a card over the page, like Glance's. Back or a tap outside closes it. An extension whose manifest asks for its settings in a tab (uBlock Origin's dashboard does) gets a Quick Tab instead.
- **Tabs an extension opens** (uBlock Origin's dashboard, say) are Quick Tabs: extension pages mean nothing on Zen's desktop.
- **Placement:** extensions live behind Extensions in the bottom bar's actions sheet (and the inner sidebar's ⋮ menu), and an extension's popup opens as a sheet. No extension icons in the bar.

## Settings

No board covers these screens, so their layout and wording get agreed before they're built.

- **Site settings come first.** Settings (in the bar's actions sheet on the cover, and the sidebar's ⋮ menu unfolded) opens the settings of the page on screen: on the cover the bar's panel grows into them, and unfolded they're a card over the page.
  - **Connection security:** whether the connection is secure, and who verified the site.
  - **Desktop site:** which version the site gets now. Changing it reloads the page and is remembered for that site. Sites the user hasn't set follow the screen.
  - **Tracking protection:** Gecko's Enhanced Tracking Protection (Standard by default), with an on/off switch per site.
  - **Clear cookies and site data** for this site, after a confirmation.
  - **All settings**, a button at the bottom.
- **All settings** holds every preference, grouped: appearance, layout, tabs, the space menu, sync (with Sync with Zen at the top) and extensions. With no page on screen (the start screen), Settings opens it directly.
- **Permissions** (step 11): a group in site settings with Camera, Microphone, Location and Notifications (and Protected content once the site has asked), each showing Ask, Allowed or Blocked; tapping one picks another. Clearing the site's data forgets them too.

## Reader (polish pass)

- **On an article,** the actions sheet's Reader tile lights up (it's grayed out elsewhere). Tapping it shows the page as an article, through Android Components' reader view (Firefox's own extractor): the article's text, headings, photos and figures, without menus, ads or sidebars. A site's root page (its home page) never counts as an article.
- **While reading,** the tile is outlined, and tapping it opens the Reader panel in the sheet: Sans or Serif, smaller or larger text, Light, Sepia or Dark, and Exit Reader. Going back a page also leaves Reader.
- **Colors** follow Ensō's light or dark theme until the user picks one in the panel; after that Reader keeps the user's choice, as it keeps the font and size.
- **Unfolded,** the ⋮ menu gets Reader on articles: it shows the page as one, or while reading opens the same controls in a card.

## Downloads (step 12, and the polish pass)

- **The prompt:** when a site starts a download, a card above the bar shows the file's name and size, with Cancel and Download. (Its look gets a later pass.)
- **The chevron:** for about 2 seconds after a download starts, the bar's ^ button turns into a download button in the space's accent; tapping it then opens the Downloads page. After that, while the download runs, a ring around the ^ fills as it goes (an arc goes round when the size is unknown).
- **The actions sheet:** a small Downloads button to the left of Extensions, shown only while a download runs or for a day after one (`recentDownloadsHours`). Unfolded, the ⋮ menu gets Downloads under the same rule, and it opens as a card over the page.
- **The Downloads page:** every download, newest first, with its type, size and day, or how it's going. Tapping a finished one opens it in another app; a failed or cancelled one starts again. A swipe takes one off the list and keeps the file. "Open Downloads folder" ends it.
- Files save to the phone's Downloads folder, Android's notification shows progress too, and the first download asks once for the notification permission.

## Site permissions (step 11)

No board covers these; the user approved a sketch.

- **The prompt:** when a site asks for the camera, microphone, location or notifications, or to play protected (DRM) content, an Ensō card shows over the page, above the cover's bar: the permission's icon, "[site] wants to use your camera" (camera and microphone together get one card), "Your choice is remembered for this site. Change it in Site Settings.", and Don't allow / Allow.
- **Remembered:** every answer is kept for the site, which is then answered without asking. A blocked site is refused silently. Back or a tap outside refuses that once, without remembering.
- **Android's permission:** allowing a site asks Android for its own permission when Ensō lacks it (camera, microphone, location, notifications). If Android says no, the site is refused.
- **Site notifications** show as Android notifications, while the site is open in a tab; tapping one opens its tab. Notifications from closed sites would need a push service and aren't supported.
- **Without asking:** autoplay with sound waits for a tap, muted video plays, and persistent storage and cross-site storage access are refused, as in Firefox.

## Build order

Build the browser core first, then sync, then the signature interactions. Each step should run on the Fold before the next starts.

1. **Project setup:** Android Studio project in Kotlin with Gradle, GeckoView and Android Components from Mozilla's Maven repository. Builds and installs on the Fold over wireless debugging.
2. **Core browsing:** one GeckoView session, the tinted top bar, address entry, back and forward, hide-on-scroll.
3. **Tabs and local spaces:** a local data model for spaces, Essentials, pinned tabs, folders and tabs; the cover tab sheet; swipe-to-close.
4. **Quick Tabs:** default for new searches, gold outline, expiry, the hold menu, the start screen and search palette.
5. **Mozilla account and Zen sync:** sign-in, then read and write Zen's Spaces, folders and tabs. Study how Dejavu does this and check its license before reusing code.
6. **Space menu:** the edge wave, haptics, page tint and gesture exclusion.
7. **Inner screen:** collapsed rail, full sidebar, sidebar swipe for spaces, fold continuity. Also space colors, which the sidebar needs: Zen's theme gradient on the tinted surfaces on both screens (top bar, tab sheet, start screen, sidebar), with the accent kept for small marks.
8. **Split view and Glance.**
9. **Extensions:** bundled uBlock Origin and the add-ons UI.
10. **Polish:** real favicons with letter fallbacks, blur snapshot for the tab sheet, animations, dark mode, and the settings screens (site settings, then All settings).
11. **Site permissions:** prompts when a site asks for the camera, microphone, location or notifications, Android's own permission when it's needed, and each site's choices in its site settings. Until then, sites' requests are refused without a prompt.
12. **Downloads:** when a site starts a download, an Ensō-style prompt with the file's name and size and Download or Cancel. Files save to the phone's Downloads folder through Android Components' downloads feature, with Android's notification for progress (tapping it opens the file), and the notification permission asked for once. Until then, downloads are dropped without a word.

## Open questions and naming

- [x] Quick Tab expiry: 12 hours, 24 hours, or user-set? User-set in All settings (12 hours, 1 day, 3 days or 1 week), 1 day by default, counted since the tab was last viewed.
- [x] How long away before the app reopens on the start screen instead of the last tab? User-set in All settings (15 or 30 minutes, 1 hour, or never), 30 minutes by default.
- [x] Exact format of Zen's synced Spaces data (to confirm while building sync). Confirmed in step 5: Zen's `spaces` collection, version 3, with space, folder, tab, split, container and layout records.
- [x] Does dark mode follow the system, or a per-space setting? It follows the system, with a setting to override it, and web pages are asked for their dark version too.
- [ ] Check the Play Store for the name "Ensō" before publishing.
- [x] Downloads: a list of past downloads in Ensō (in the actions sheet or All settings), or only Android's notification and the Files app? A Downloads page in Ensō, reached from the chevron as a download starts and from a small button in the actions sheet (the ⋮ menu unfolded) while there's a recent download (see Downloads).

Naming and trademarks: Zen's code is MPL 2.0, which allows forking but grants no rights to the Zen name or logo. The same applies to "Firefox." Ensō should not use either name or logo, and can say it "syncs with Zen Browser" in its description. Ask the Zen team before using their name in any other way. This is not legal advice.
