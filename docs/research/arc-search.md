# Arc Search: a look for ideas

Nov 11, 2026. Arc Search 1.12.11 on the Fold's cover screen, in dark mode, driven over adb. I opened and closed one test search, changed no settings, and dismissed the microphone prompt without answering it. Screenshots are in [arc-search/](arc-search/).

## Heads-up: Arc Search is going away

The Browser Company is sunsetting Arc ([arc.net](https://arc.net)):

- **Nov 23, 2026:** Browse for Me and the other AI features turn off, and sign-in and sync stop.
- **Dec 22, 2026:** Arc Search for Android is pulled from the Play Store **and removed from the devices it's installed on**.

Anything else you want to study in it has to happen before Dec 22. That's why the screenshots are saved here and not in a scratch folder.

## What Arc Search does

**The bar** ([01](<arc-search/01 bar.png>), [02](<arc-search/02 bar hidden.png>)): the same three things as Ensō's B1: the tilted two-card stack, a wide "+" pill, and a chevron. It's a flat dark band with no rounded top corners. It hides completely on scroll, with nothing left at the edge, and the status bar stays showing over a solid band.

- **A quick flick right on the bar jumps to the tab you used before.** A slow drag does nothing. Tabs are ordered by when you last used them, so the tab you jump to becomes the newest, and a flick left doesn't come back. In practice, flicking right flips between your last two tabs.
- **Holding "+" starts voice search** at once, with no sheet first.
- Holding the stack only opens the switcher. Swiping up on the bar does nothing.

**The tab switcher** ([03](<arc-search/03 switcher.png>), [04](<arc-search/04 switcher clear all.png>)):

- Cards sit side by side with a gap and don't stack, unlike Ensō's B2. The focused card is centered at about 68% of the width, and the last-used card peeks in from the left.
- The focused card's favicon and title sit centered above it, in light text. The background is a purple-to-red gradient.
- The bar's row changes to Sync (tabs from Arc desktop), "+" and Settings.
- A fling up closes a card. A slow drag lifts the card with the finger but snaps back.
- Holding a card just opens it; there's no menu.
- "Clear All Tabs", with an archive icon, showed under the card after a close.

**The actions sheet** ([05](<arc-search/05 actions sheet.png>)): from the chevron, the bar's surface growing up over a dimmed page, like Ensō's B3.

- **The URL row:** a pill with back, forward, the domain, copy link and refresh.
- **Four tiles** joined into one rounded group with hairline gaps: Summarize (AI), Pin, Find and Share.
- **Rows:** Translate; Zoom ("100%", with + and −, per site); Desktop Site (a checkbox right in the sheet); Site Settings.
- **The footer:** "Blocked 38 ads and trackers", then "Problems? Disable in site settings" in a lighter gray. It's a quiet trust signal with a way out.

**Site settings** ([06](<arc-search/06 site settings.png>)): a sheet titled "Site Settings", with "www.google.com settings" under it. It has four switches with icons (Block Ads, Block Cookie Banners, Block Trackers and Force Dark Theme) and a "Go to App Settings" pill at the bottom right. The same four switches are global defaults in App Settings ([13](<arc-search/13 global site settings.png>)).

**Search** ([07](<arc-search/07 search.png>), [08](<arc-search/08 search typing.png>)):

- **The sheet:** a fixed-height sheet on the keyboard. The pill field holds a mic and a Browse for Me toggle. It doesn't grow with its rows: the list scrolls behind the keyboard, so only about two rows show.
- **The rows:** first, what you typed, with a "Browse for me" pill. Second, a site suggestion: favicon, the site's name and its domain ("The Weather Channel / weather.com"). Then Google's suggestions, each with its own "Browse for me" pill.
- **Opening a new tab:** it fades in from a blank page.

**The engine toggle** ([09](<arc-search/09 engine toggle.png>)): on a search page, a small floating two-part pill at the bottom right flips the same tab between Google's results and Arc's AI page. It hides with the bar on scroll.

**Link hold** ([10](<arc-search/10 link hold.png>)): a small card over a blurred page, with the URL (3 lines), "Open in new tab" and "Copy link address". It has no preview; Ensō's Glance already goes much further.

**Find** ([11](<arc-search/11 find.png>)): a floating pill on the keyboard with the field, "1/87", up, down and ✕, over an undimmed page. It's essentially Ensō's find bar.

**App Settings** ([12](<arc-search/12 app settings.png>)), in order:

1. Gift Arc Search to a Friend.
2. Sync with Arc Desktop.
3. Global Site Settings.
4. Set as Default Browser.
5. Change App Icon: 9 icons ([14](<arc-search/14 app icons.png>)).
6. Search Engines and Languages.
7. Storage:
   - Archive: a searchable list of auto-archived tabs.
   - Archive Inactive Tabs: 12 hours, 1 day (default), 3 days, 7 days or 30 days.
   - Clear Browsing Data.
   - Downloads.
   - Export Data ("Share your open tab URLs").
8. Options: Auto-Open Keyboard, Always Open App Links and Allow Google Translate.
9. Support.

## Ideas for Ensō

All twelve build steps are done, so each of these would be a spec addition first, agreed before it's built, per CLAUDE.md. They're ranked by payoff for the effort. Anything that could differ per person would sit behind a `Settings.kt` value.

### Small

1. **Flick the bar to flip to your last tab.** A quick sideways flick on the closed bar jumps to the tab you used before; a second flick comes back.
   - Arc's version only goes one way, which felt odd. A two-tab toggle (previous ↔ current) is simpler to learn.
   - **Build:** the bar has only `verticalSwipe` today (`bottombar/BottomBar.kt`). It needs a fling-only detector, so slow drags still do nothing, and a "previous tab" from `TabManager`: Quick Tabs have `lastViewedAt`, and `TabSessionState.lastAccess` covers loaded tabs.
   - **Watch:** the back-gesture zones at both edges. Start the detector more than 60 px in.
2. **Hold "+" for voice search.** `BottomBarActions.voiceSearch()` already exists for the field's mic. A hold on the pill could call it directly, and what's said opens as a Quick Tab.
3. **Copy link in Glance.** Glance has Close, Open as tab and Split (`MainActivity.kt:1140`). A copy-link button (and maybe Share) covers what Arc's whole link menu does.
4. **"Blocked N ads and trackers" at the foot of the actions sheet**, with "Problems? Turn it off in Site Settings", which opens Site Settings.
   - **Build:** uBlock Origin's per-tab count is already merged into `extensionRows`, and Gecko's tracking protection keeps its own count per tab.
   - It shows privacy working without adding chrome.
5. **Desktop site in the actions sheet.** Arc puts it one tap away as a checkbox. Ensō has it inside Site Settings. A row or tile in B3 would save a step, since it's the most-used site setting.
6. **Open search right away on the start screen** (Arc's Auto-Open Keyboard): a `Settings` value that opens bottom search when the start screen appears on the cover. It would be off by default, since the start screen has the fidget.

### Medium

7. **Block cookie banners.** Gecko has Firefox's Cookie Banner Reduction built in. In AC it's an `EngineSettings` cookie-banner mode (reject all), with per-site exceptions kept by the engine.
   - **Where:** a switch in Site Settings beside tracking protection, and a default in All settings.
   - **Or:** turn on uBlock Origin's cookie-notice filter lists.
   - **Check first:** the exact API names in AC 158 before speccing it.
8. **On-device Translate.** Gecko ships Firefox's Translations, which run on the phone, so nothing goes to Google. AC wraps them in `feature-translate`.
   - **Where:** a Translate row in the actions sheet and the ⋮ menu, maybe offered when the page's language differs from yours.
   - **Why:** it's more private than Arc's Google Translate, which fits Ensō.
9. **An archive for expired Quick Tabs.** Today they vanish (history keeps the pages). Arc keeps them in a searchable Archive.
   - **Where:** "Recently cleared" in bottom search before you type, or a list behind the Quick Tabs view, with one tap to bring a tab back.
   - Arc also offers 30 days, beyond Ensō's 12 hours to 1 week.
10. **Site suggestions in bottom search.** Arc's second row is a site: favicon, name and domain, which goes straight there.
    - **Ensō:** the best-matching history domain as a "go to site" row, or Google's own navigational suggestions.
    - **Check first:** Ensō asks with `client=firefox`, which returns plain strings. Whether another client marks navigational results needs checking.
11. **Open links in apps** (Arc's Always Open App Links). Ensō is the default browser, so a YouTube or Maps link opens in Ensō today.
    - **Build:** AC's `feature-app-links` intercepts these. A `Settings` value would pick Ask, Always or Never.

### Larger, or for later

12. **Per-site zoom** (Arc: "100%" with + and − in the actions sheet). GeckoView reads its density only at startup, and `fontSizeFactor` is app-wide (see the code map). Per-site zoom would need a built-in extension applying CSS zoom, the same piece a per-screen zoom needs.
13. **Alternate app icons.** Arc has nine. Ensō could offer a few versions of the mark (ink on paper, white on black, maybe space-tinted) through activity aliases. It's for delight only.
14. **A floating "search elsewhere" pill on search result pages**, borrowing the shape of Arc's AI/Google toggle without the AI: re-run the query in another engine. It's low priority, and listed only because the shape is nice.

### Design notes worth borrowing

- **Joined tiles:** the actions sheet's four tiles read as one rounded group with hairline gaps, not four separate cards.
- **A light footer line** gives a status ("Blocked 38…") and a fix ("Problems? …") without a button.
- **Site settings' big title**, with the host as a quiet subtitle, and one accent pill for the way out.
- **The switcher's title** is centered above the focused card, not pinned to its corner.
- **Thin hold menus:** the link-hold card blurs the page behind it instead of dimming it.

### What not to take

- **Browse for Me, Summarize and Call Arc:** these AI features go dark Nov 23, need Arc's servers, and don't fit Ensō.
- **Force Dark Theme:** Gecko has no built-in version of Chrome's auto-dark. The Dark Reader add-on from addons.mozilla.org already works in Ensō.
- **Export open tab URLs, and Gift:** Zen sync already carries kept tabs, so neither is needed.
- **Arc's flat, side-by-side switcher:** Ensō's B2, with cards sliding over a stack and the zoom from the page, is richer. Keep it.
