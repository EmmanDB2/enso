# Zen sync review

11 November 2026. Read-only: no code was changed, nothing was run on the phone, and nothing was sent to the sync servers.

## Since the review

All 13 findings have been fixed, each with unit tests in `app/src/test/…/sync/`. The text below is the review as written, so its line numbers and "suggested fix" notes describe the code of 11 November.

| Finding | Fixed | How it differs from the suggested fix |
| --- | --- | --- |
| 1, 4, 3, 2, 10 | 12 Nov | As suggested. A randomized test (`SyncFuzzTest`) found one more gap in finding 1's stamp, which is closed. |
| 5, 6 | 13 Nov | As suggested. |
| 7 | 13 Nov | A restore is refused for a snapshot of another storage user, or one the account's keys can't read; a changed sync id alone only gets a warning, since the records still read. Dated snapshots are kept before a restore, before 5 or more deletions arrive or go, and before the collection is reset. |
| 8, 9, 12, 13 | 13 Nov | As suggested. For 12 the phone keeps a split it made unsyncable, judged by what the split holds, not by which upload deleted it. |
| 11 | 13 Nov | As suggested, for `children`, the Essentials and a split's `tabs`. Unreadable records are remembered and asked for again, and the phone's changes to them wait. |

Still as described under "Smaller notes": none of those was changed. One case found while testing stays open, at low severity: the phone sends again a tab or folder of its own that Zen deleted within seconds of the phone making it, before the phone had taken a sync in.

## What was read

- All of `sync/`, all of `spaces/` (the model, the edits, the store and `TabManager`), and the places `MainActivity.kt` calls sync: the Keep tile's unkeep, the timers and the debug intents. Commit 081291c (the 30-second checks and the device record) is included.
- Zen's own sync source, to check what the phone's code assumes about it: `src/zen/sync/ZenSpacesSync.sys.mjs`, `ZenSpacesSyncModel.sys.mjs`, `ZenSpacesSyncApplier.sys.mjs` and `src/zen/folders/ZenFolder.mjs`, from zen-browser/desktop's `dev` branch on 11 November 2026 (the sync folder's latest commit is d5bac6be6, of 4 November).
- Firefox's `services/sync/modules/engines/clients.sys.mjs` and `browser/base/content/browser-sync.js`, for the device list.
- One line of your Zen profile's `prefs.js`, read-only: `zen.spaces-sync.normal-tabs` is `true`.

Paths below are under `app/src/main/java/app/enso/browser/`. "Confirmed by tracing" means I followed the code line by line. None of it was run.

## Summary

- **One serious bug (finding 1).** A sync can save Zen's new state to the mirror without the phone's model taking it in. The next sync, 3 seconds later, then uploads the phone's old state over Zen's: it deletes what Zen added and re-creates what Zen deleted. Opening a folder in the tab sheet while the start-up sync is running is enough to cause it.
- **Two rarer ways one phone action can delete tabs in Zen:** Delete folder when part of the edit is held back (finding 2), and a synced tab that sits on a blank page (finding 3).
- **The 10-deletion guard** stops tombstones but still uploads the rest of the change, and says nothing (finding 4).
- **Three ways the phone drops its own tabs** (findings 5, 6 and 10). The first doesn't affect you today, because your Zen has normal-tab sync on.
- The transport, the encryption, the checks before any write, the conflict loop and the batched uploads look sound. See "What looks sound".

**Before you sign Zen back in on the Mac:** Zen will upload a large batch when it does. Finding 1 is triggered by an edit on the phone while the phone downloads Zen's changes. Until it's fixed, keep Ensō closed while Zen signs in and syncs. Then open Ensō and leave the tab sheet alone until Sync with Zen shows it has synced.

## What Zen does with the phone's uploads

Several findings rest on these facts from Zen's source.

1. **Each record places itself; `children` only orders.** A tab's space, folder, pinned state and Essential state come from its own record (`ZenSpacesSyncApplier.sys.mjs:558-598`, `:752-795`). A space's or folder's `children` is only used to put the items it names in order (`:907-928`). The phone's mapper works the other way round: it shows only what `children` lists (`sync/ZenSpacesMapper.kt:74-100`).
2. **A folder's tombstone deletes the tabs still inside it.** `#deleteFolders` calls `folder.delete()` (`ZenSpacesSyncApplier.sys.mjs:524-536`), which removes the whole tab group (`ZenFolder.mjs:174-183`).
3. **Zen applies every incoming record, then uploads its own state wherever it still differs.** It also drops its own pending change to a record it just received (`ZenSpacesSync.sys.mjs:158-174`, `ZenSpacesSyncModel.sys.mjs:769-803`, `:864-877`).
4. **Normal-tab sync is off by default in Zen** (`ZenSpacesSyncModel.sys.mjs:25-30`). With it off, Zen skips every record that says `pinned: false` (`ZenSpacesSyncApplier.sys.mjs:100-104`) and leaves normal tabs out of `children` (`ZenSpacesSyncModel.sys.mjs:308-313`, `:367-378`).
5. **Zen can list a child that has no record.** A syncable tab with no page yet is left out of the tab records but still listed in `children` (`ZenSpacesSyncModel.sys.mjs:339-341`, `:367-378`).
6. **Zen tombstones anything it applied but can't show**, such as a tab record with a blank URL (`ZenSpacesSyncApplier.sys.mjs:543`, `ZenSpacesSyncModel.sys.mjs:784-792`).

## Findings

### 1. A sync can save Zen's new state without the phone taking it in, and the next sync undoes Zen's changes

**Severity:** critical.

**Where:**
- `sync/ZenSync.kt:240` and `:242` skip `applySpaces`: after an edit during the sync, and in a dry run with unsent changes.
- `sync/ZenSpacesEngine.kt:222` has already saved the mirror by then, downloaded records included.
- `sync/ZenSync.kt:225-233` and `sync/ZenSpacesEngine.kt:165-166`: the next sync compares the phone's model with that mirror.
- `sync/ZenSpacesProjector.kt:162-167` and `:171-179`: deletions are "what the mirror would show, minus what the model has". Anything Zen added that the model never took in counts as removed by you.
- `sync/ZenSpacesProjector.kt:55-65`: a tab the model still has, but the mirror no longer does, is uploaded as a new record.

**Scenario:**
1. The phone and Zen are in sync. On the desktop you make a folder with two new pinned tabs and close an old pinned tab. Zen uploads.
2. You open Ensō. The start-up sync begins (`MainActivity.kt:996`). While it runs, you pull down the tab sheet and tap a folder to open it. That tap counts as an edit (`tabsheet/TabSheet.kt:665`, `MainActivity.kt:1537-1540`).
3. The sync downloads Zen's changes and saves the mirror. Because an edit happened, it doesn't apply them to the model. It schedules another sync in 3 seconds.
4. That sync compares the model (no new folder, old tab still there) with the mirror (new folder and tabs, old tab gone). It uploads tombstones for the folder and its two tabs, the space's `children` without them, and the closed tab as a new record with an empty icon.
5. Zen applies that. The new folder and its tabs are deleted on the desktop, and the closed tab comes back.

Fields the phone owns are reverted the same way: a tab Zen pinned, moved or put in a folder goes back, and a renamed folder gets its old name.

**Other ways into the same state** (a mirror newer than the model):
- **Dry run.** With unsent test edits, every sync advances the mirror and applies nothing. When dry run is turned off, everything Zen changed in between is reverted. The dry-run log does list these as "Would send".
- **The app is killed between the two saves.** The mirror is saved first, and the model 500 ms after it's applied (`spaces/SpacesStore.kt:41`). Swiping Ensō away just after an edit can land in that gap, because the sync 3 seconds after an edit runs in the background.
- **Restoring `spaces.json` from a backup** as CLAUDE.md describes. The mirror stays current, so the first sync uploads the backup's state over Zen's.
- **An unreadable `spaces.json`.** The store starts fresh (`spaces/SpacesStore.kt:55-57`), and the empty model is compared with a full mirror. Only the 10-deletion guard stands in the way.

**What limits the damage:** the guard holds back the tombstones when there are more than 10 in one sync. It doesn't hold back the re-created records or the reverted fields.

**Confidence:** high. Confirmed by tracing, step by step.

**To see whether it has happened:** in logcat (tag `ZenSync`), look for a `Synced: … N received` line with N above 0, followed about 3 seconds later by `Sending deletion of …`, or by `Sending new tab <id>` where the id starts with an old timestamp.

**Suggested fix:**
- Save the mirror only together with applying it. Let the engine return the new mirror. In `ZenSync`, apply it to the model, flush `spaces.json`, then save the mirror.
- When the apply is skipped, keep the old mirror plus only the records this sync uploaded, with the old `lastModified`. The next sync then downloads Zen's changes again and treats them as incoming. So the phone's own uploads aren't mistaken for Zen's changes, add a downloaded record to `incoming` only when it differs from the mirror's copy.
- Save in `spaces.json` which mirror the model reflects (the mirror's `lastModified` and `syncId`). If the two disagree when a sync starts, drop the mirror and download everything, uploading nothing.
- Don't call `localEdited()` for opening or closing a folder. It isn't synced, and it's the most likely trigger.
- In CLAUDE.md's restore steps, also remove `files/zen-spaces-mirror.json`.

### 2. Where Zen wins one record of an edit, the rest of the edit still uploads; with Delete folder that can delete tabs in Zen

**Severity:** high when it happens, and unlikely.

**Where:**
- `sync/ZenSpacesEngine.kt:194-195` holds back single records, not whole edits.
- `spaces/SpacesEdits.kt:143-144`: Delete folder keeps the folder's tabs (`tabsheet/TabMenu.kt:226-228`).
- Zen: fact 2 above.

**Scenario A, Delete folder:**
1. Folder F holds pinned tabs A and B, all in sync. On the desktop you change B (rename it, say), and Zen uploads.
2. Before the phone has downloaded that, you hold F on the phone and choose Delete folder. The edit is: A and B get no folder, the space's `children` changes, and F is tombstoned.
3. The sync downloads B, so B's change is held back. It uploads A's move, the space and F's tombstone.
4. Zen applies them. A moves out. B is still in F, so it's deleted with the folder. Zen then tombstones B, and the phone drops it too.

The same happens with no held record if Zen has a tab in F that it hasn't uploaded yet (Zen was offline or asleep). The phone never knew about that tab.

**Scenario B, milder, same cause:**
1. You pin a phone-only tab, or move a tab into a new folder, while Zen has changed the space's record. With normal-tab sync on, Zen changes a space's record every time a tab opens or closes in it.
2. The tab and folder records upload. The space's `children` is held back.
3. The mapper only shows what `children` lists, so the pinned tab or the new folder vanishes from the phone, and its page closes.
4. The follow-up sync 3 seconds later uploads the tab as outside the folder again. Zen ends up with an empty folder. A vanished pinned tab comes back only when Zen next uploads the space.

**Confidence:** the partial upload is confirmed by tracing. Zen's side is read from its source. I didn't read Zen's patched `removeTabGroup`; Firefox's closes the group's tabs. Medium to high.

**Suggested fix:**
- Never tombstone a folder in the sync that empties it. First upload the moves and the folder with empty `children`. Tombstone it in a later sync, and only if the server's copy is still empty and none of its tabs' records were held. If Zen put something in it meanwhile, Zen's version comes back and the folder stays.
- Make the mapper place items as Zen's applier does: by each record's own `workspaceUuid`, `folderId`, `parentFolderId`, `pinned` and `essential`, with `children` used only for order. A half-uploaded edit then reads the same on both sides, nothing vanishes from the phone, and the follow-up sync completes the edit instead of reversing it. This also fixes finding 5 and helps 6 and 11.

### 3. A synced tab sitting on a blank page is deleted from Zen

**Severity:** medium. It's destructive, and rare.

**Where:** `sync/ZenSpacesProjector.kt:54`.

**Scenario:**
1. T is a pinned tab or an Essential, in sync, with its page loaded on the phone.
2. The page sends itself to `about:blank`, as some sign-in and redirect pages do, or the model's URL for it is empty for any reason.
3. A sync runs. `putTab` returns before it looks at the pinned URL or the server's copy, so T isn't projected. The mirror shows T, so T is tombstoned and taken out of `children` or `layout.essentials`.
4. Zen closes the pinned tab. The next apply removes it from the phone.

**Confidence:** the projector's behavior is confirmed by tracing. How often a synced tab is blank at the moment of a sync is the uncertain part. Android Components ignores a new session's first `about:blank`, so it takes a page that goes there later. Plausible and rare.

**Suggested fix:** skip a blank tab only when it has no record in `base`. When it has one, project it from that record, so it comes out unchanged. A pinned tab's URL is `pinnedUrl ?: baseUrl` anyway.

### 4. The 10-deletion guard drops the tombstones, uploads everything else, and then forgets the deletions

**Severity:** medium.

**Where:** `sync/ZenSpacesEngine.kt:196-200`.

**Scenario A, a real choice undone:**
1. You have 12 synced open tabs you no longer want. You swipe them away in the tab sheet, each within 3 seconds of the last.
2. One sync has 12 deletions. The guard removes them from the upload, but the space's `children` without the 12 still uploads.
3. The mirror now has that space record. The 12 tab records remain, but nothing lists them, so they no longer count as shown, and no later sync tries to delete them.
4. In Zen the tabs never closed (fact 1). Zen uploads the space with them listed again, and all 12 come back on the phone. Only the log says why.

**Scenario B, the case it's for:**
1. The model loses tabs through a bug or an unreadable `spaces.json`.
2. With 11 or more lost, no tombstones go. The parents that no longer list them do go. Zen puts them back within about 10 minutes, but only because it ignores `children` for placement, and only if Zen is running.
3. With 10 or fewer lost, they're deleted from Zen.

**Confidence:** confirmed by tracing, on both sides.

**Suggested fix:**
- When the guard trips, upload nothing from that sync and leave the mirror as it was.
- Show it in Sync with Zen ("Ensō wants to remove 12 tabs from Zen") with a way to confirm, so a real clean-up can go through.
- Add a second check that doesn't depend on a count: when the model has none of the mirror's spaces, `zenChanges` should return nothing, as it does when nothing has been downloaded yet (`sync/ZenSpacesProjector.kt:36`).

### 5. With Zen's normal-tab sync off, tabs you keep on the phone disappear from the phone

**Severity:** medium for anyone on Zen's defaults. It doesn't affect you today.

**Where:**
- `sync/ZenSpacesMapper.kt:159-174`: line 165 drops a synced open tab that Zen's `children` doesn't list.
- `Settings.kt:143-145`: `syncOpenTabs` is on by default.
- Zen: fact 4 above.

**Scenario:**
1. Zen has `zen.spaces-sync.normal-tabs` off, its default. You keep a Quick Tab T on the phone.
2. The phone uploads T's record (`pinned: false`) and the space's `children` with T in it. On the next apply T is marked synced.
3. Zen skips T's record. It applies the space, sees that its own `children` (without T) differs, and uploads the space again.
4. The phone downloads that. T is synced and no longer listed, so the mapper drops it and its page closes. T's record stays on the server, listed nowhere.

Your Zen has the option on, so this isn't happening to you. It would start if the option were turned off, or if another Zen without it joined the account.

**Confidence:** confirmed by tracing, on both sides.

**Suggested fix:** keep a synced open tab while its record is still in the mirror as an unpinned tab of that space. Drop it only when the record is gone. That's the mapper change from finding 2.

### 6. Pinned tabs, folders and Essentials that exist only on the phone are dropped when Zen's state is applied

**Severity:** medium. The loss is on the phone only.

**Where:**
- `sync/ZenSpacesMapper.kt:116`: pinned items come from Zen only.
- `sync/ZenSpacesMapper.kt:129`: from a space Zen doesn't have, only open tabs are carried over.
- `sync/ZenSpacesMapper.kt:141-145`: Essentials come from Zen only.

**Scenario A, a new phone:**
1. On a fresh install, before signing in, you pin tabs in "Home", make a folder and add an Essential.
2. You sign in. The mirror is empty, so the first sync uploads nothing (`sync/ZenSpacesProjector.kt:36`). It downloads Zen's spaces and applies them.
3. Home's open tabs move to the first Zen space. Its pinned tabs, folders and Essentials are gone, and their pages close.

**Scenario B:** the same after signing out, which clears the mirror (`sync/ZenSpacesEngine.kt:82-86`). Anything pinned while signed out is dropped at the next sign-in. It also applies after any mirror reset (`sync/ZenSpacesEngine.kt:296`, `:310-313`, `:322-325`, or an unreadable mirror file at `sync/ZenMirror.kt:64-67`), to items pinned since the last upload.

**Confidence:** confirmed by tracing.

**Suggested fix:** in `withZenSpaces`, carry phone-only pinned tabs (`synced == false`), phone-only folders and phone-only Essentials over, as open tabs already are, so the next sync uploads them. Folders need a `synced` flag of their own for that.

### 7. The snapshot isn't tied to an account or its keys, and restoring it deletes everything that isn't in it

**Severity:** medium. It needs a debug build and a command typed by hand, but it's the safety net.

**Where:**
- `sync/ZenSpacesEngine.kt:118-135`: the restore.
- `sync/ZenSpacesEngine.kt:539-540`: `saveIfMissing` only checks that the file exists.
- `sync/ZenSpacesEngine.kt:81-86`: the snapshot stays after signing out.

**Scenario:**
1. The snapshot was taken under one account and one set of keys.
2. Later the account's password is reset, which makes new sync keys, or you sign in to another account.
3. You run the restore. It uploads the snapshot's records, still encrypted with the old keys, and tombstones every current record that isn't in the snapshot.
4. Zen can't read the old records and applies the tombstones.

Two smaller points. The snapshot is taken once, so a restore long afterwards deletes everything made since. And a second account never gets a snapshot of its own.

**Confidence:** the phone's side is confirmed by tracing. What Zen does with records it can't decrypt is from how Firefox Sync treats them in general. I didn't check it in source.

**Suggested fix:**
- Save `storageUid`, `syncId` and `keysModified` in the snapshot. Refuse to restore unless they match the account and one record decrypts with the current key. Take a new snapshot when they change.
- Keep a few dated snapshots, for example one before any sync that deletes several records. The phone has no guard on deletions that arrive from Zen, and Zen has had bugs that wiped spaces on other devices (its commit 031b5d8d0, fixed in September).

### 8. `moveToFolder` removes the tab when the folder is gone

**Severity:** low.

**Where:** `spaces/SpacesEdits.kt:119-122`.

**Scenario:**
1. The hold menu is open on pinned tab T and lists folder F.
2. A sync applies Zen's state, in which F was deleted. You tap F.
3. `removeTab(T)` runs. `updateFolder(F)` finds nothing, so T is in no list.
4. If T wasn't loaded, the next sync tombstones it in Zen. If it was loaded, `TabManager` adds it back as a plain open tab (`spaces/TabManager.kt:202-210`), and Zen sees the pinned tab unpinned.

**Confidence:** the edit is confirmed by tracing. The timing is only plausible: the menu probably redraws when the model changes, which leaves a very short gap.

**Suggested fix:** return the state unchanged when the folder isn't found, as the file's opening comment promises.

### 9. The device record is uploaded between the spaces upload and saving the mirror

**Severity:** low. From commit 081291c.

**Where:** `sync/ZenSpacesEngine.kt:220-222` and `:241-281`. Only `SyncServerException` is caught (`:277`).

**Scenario:**
1. The spaces upload succeeds. The device record is due: on the first sync, every 6 days, or when the name or version changes.
2. Its request fails with a network error or a 401. The exception leaves `run` before `store.save`. The sync shows as failed and the mirror is still the old one.
3. The next sync downloads the phone's own records as though they were Zen's. That's harmless by itself.
4. If you edited one of those records again in between, that newer edit is held back and then overwritten by the downloaded version.

**Confidence:** confirmed by tracing.

**Suggested fix:** save the mirror first. Then try the device record, catch every exception from it, and save again.

### 10. A phone-only tab whose id Zen has somewhere else stays as a second copy

**Severity:** low.

**Where:**
- `sync/ZenSpacesMapper.kt:159-174` only checks the space's synced open tabs.
- `sync/ZenSpacesMapper.kt:141-145`: Essentials aren't checked against `seen`.
- `spaces/SpacesEdits.kt:32-38`: `removeTab` removes every copy.
- `sync/ZenSpacesProjector.kt:139-149`.

**Scenario:**
1. T is a kept, synced tab. On the phone you tap Kept, so T becomes a Quick Tab again. Before that reaches Zen (the phone is offline, say), you pin T on the desktop.
2. At the next sync Zen's pin wins and the phone's deletion is held back. After the apply, T is a pinned tab from Zen and also still a Quick Tab among the open tabs.
3. A day later the Quick Tab expires, or you close it. `removeTab` takes out both copies, and the next sync tombstones the pinned tab in Zen.

If the second copy isn't a Quick Tab, the projector writes T twice into the space's `children` and rewrites its record as unpinned.

**Confidence:** each step is confirmed by tracing. The coincidence it starts from is unlikely.

**Suggested fix:** in the mapper, drop a local phone-only tab when its id appears anywhere in Zen's state (any space, pinned, or Essentials). Add the Essentials' ids to `seen`.

### 11. Ids the phone can't show are written out of `children` and `layout.essentials`

**Severity:** low. No data is lost.

**Where:**
- `sync/ZenSpacesMapper.kt:79` skips a child with no record.
- `sync/ZenSpacesProjector.kt:132`, `:148-149` and `:156-158` rebuild the lists from the model alone.
- `sync/ZenSpacesEngine.kt:340-347`: a record that can't be read is skipped, and `lastModified` moves past it.

**Scenario:**
1. Zen lists a tab that has no record (fact 5), or the phone couldn't read a record.
2. The phone's model has no such tab. Its next sync sees the space as changed and uploads `children` without that id, with no edit from you.
3. Zen applies it, sees a difference from its own state, and uploads its version again.
4. The two trade the space record every round for as long as the blank tab exists.

**Confidence:** confirmed by tracing. I haven't seen a child without a record in real data.

**Suggested fix:** when writing `children` and each Essentials group, keep the ids from the server's copy that have no record in the mirror, in their place. Don't move `lastModified` past a record that failed to read, or remember its id and fetch it again.

### 12. A Zen split that stops being syncable is ended on the phone too

**Severity:** low.

**Where:** `sync/ZenSpacesMapper.kt:122`, `sync/ZenSpacesProjector.kt:84`, `MainActivity.kt:1645-1651`.

**Scenario:**
1. A and B are open tabs in a split that syncs with Zen.
2. You tap Kept on A, or pin A. The split can't go to Zen any more, so its record is tombstoned.
3. On the next apply the phone's split is still marked `synced` and Zen no longer has it. It's dropped as "ended on the desktop".

The spec says a split with a Quick Tab, or with a pinned tab and an open one, stays on the phone.

**Confidence:** confirmed by tracing.

**Suggested fix:** clear `synced` on a split when the phone's own upload deletes its record.

### 13. The 30-second check becomes a full sync every 30 seconds when there's no collection time to compare

**Severity:** low. It costs battery and data. From commit 081291c.

**Where:** `sync/ZenSpacesEngine.kt:110-113`, `sync/ZenSync.kt:190-209`.

**Scenario:**
1. Zen's spaces sync is off for the account, or the collection is empty, or every sync fails before the mirror is saved.
2. The mirror has no `lastModified`, so `serverChanged` answers true without asking the server.
3. `syncNow()` runs every 30 seconds while the app is open: a token, `info/collections` and `meta/global` each time.

**Confidence:** confirmed by tracing.

**Suggested fix:** skip the check in the `ZenSyncOff` and `Failed` states, as it's skipped for `NeedsSignIn`.

## Smaller notes

- **Unkeep is a one-tap delete on the desktop.** Tapping Kept turns the tab back into a Quick Tab, and 3 seconds later the sync closes it in Zen (`MainActivity.kt:1645-1651`). That's as designed. It's also the only way to remove a desktop tab from the phone with one tap and no confirmation. A toast with Undo would cover a mis-tap.
- **"Zen wins" depends on who uploads first.** It holds when Zen's change is on the server before the phone syncs. Zen applies every incoming record and drops its own pending change to it (fact 3), so when the phone uploads first, the phone wins on the desktop. The phone syncs 3 seconds after an edit, so it usually does.
- **Ending a split on the phone beats a change Zen made to it at the same time.** `endedSplits` survives the hold, and the follow-up sync deletes Zen's newer version. The spec says Zen's wins.
- **Essentials are filed under different containers.** The phone files a new Essential under its space's container (`spaces/SpacesEdits.kt:112-117`). Zen files it under the tab's own container (`ZenSpacesSyncModel.sys.mjs:685-697`). Where they differ, Zen uploads `layout` again and the Essential changes group on the phone. That's one round trip with no loss.
- **With `syncOpenTabs` off,** closing one of Zen's open tabs on the phone takes it out of `children` without deleting it (`sync/ZenSpacesProjector.kt:139-149`, `:163-165`). Zen lists it again and it comes back.
- **The device list's 7-day rule is narrower than CLAUDE.md says.** Firefox hides a device record after 7 days only when another record has the same name (`clients.sys.mjs:498-513`). The 6-day refresh is harmless, and it isn't what keeps the phone counted.

## What looks sound

- **Transport.** Hawk signing, the clock offset, token renewal with one retry, paging with a conflict check between pages, and timestamp comparison (`sync/SyncClient.kt`).
- **Encryption.** The HMAC is checked before decrypting, in constant time. The collection key falls back to the default key. Records and tombstones have the shape Zen's engine writes (`sync/SyncCrypto.kt`, `sync/ZenSpacesEngine.kt:384-402`).
- **Checks before any write.** `meta/global`'s storage version, engine version and sync id are checked first. "Not set up", "not turned on" and "needs update" all stop before reading or writing records. A changed storage user, sync id or key set starts a fresh mirror.
- **An empty mirror uploads nothing** (`sync/ZenSpacesProjector.kt:36`), and a download with no spaces never empties the phone (`sync/ZenSpacesMapper.kt:30`). The first sync on a new phone, a missing or unreadable mirror file, and the sync after signing in again are therefore safe for Zen. Finding 6 is the phone-side cost.
- **The conflict guard and the re-sync loop.** Uploads carry `X-If-Unmodified-Since` with the time of this sync's download. A 412 leaves the mirror unsaved, and the next sync downloads first. The loop ends once no one else is writing.
- **Batched uploads.** In batch mode the server applies all of them or none. A failure partway leaves the server untouched and the mirror unsaved. If the commit went through but its answer was lost, the next sync reads the phone's own records back and settles.
- **The projector.** It starts from the server's copy and changes only the fields the phone owns. An untouched record comes out identical and isn't sent. Containers, icons, themes and live folders' children pass through. A tab from Zen keeps Zen's URL unless `syncTabNavigations` is on.
- **Splits.** `withValidSplits` keeps a tab in one split at most. A split's id replaces its tabs in `children` only when they all sit under the same parent. A split with a Quick Tab, or with pinned and open tabs, isn't sent. Zen's own splits pass through. `endedSplits` is cleared once Zen has dropped the record. The split record's fields match what Zen writes.
- **Duplicate children.** The mapper's `seen` set, `distinctBy` on the open tabs, and the way `place` swaps a split for its tabs keep an id from being listed twice in normal use. Finding 10 is the one gap. Zen's current applier uses `children` only for order, so a duplicate there wouldn't make a second tab.
- **Essentials per container.** Groups are read and written per container, in Zen's order. Groups for containers the phone doesn't show pass through. The setting only changes what's displayed.
- **Edits during a sync.** They're counted, the model captured at the start is the one that's compared, and a sync that was skipped because another was running is asked for again. The problem in finding 1 is what happens to the mirror, not the counting.
- **Signing out.** The mirror is cleared, nothing uploads until everything is downloaded again, and Zen's state wins.
- **The first snapshot.** It's taken after the download and before the first upload. If it can't be taken, the upload doesn't happen.
- **The 30-second check.** It's one request. The phone's own uploads don't trigger it, and running beside a sync is harmless.
- **The device record.** It uses the right key, has the fields Firefox reads, and is hidden once the phone signs out. It doesn't make the phone a Send Tab target (Firefox lists only devices with the Send Tab command).
- **The unkeep path.** It can only be reached for an open, unpinned tab of the current space (`bottombar/BottomBar.kt:408-411`). Its deletion goes through the same guarded upload. If Zen changed the tab at the same time, Zen's version comes back as a kept tab.
- **`TabManager.applySpaces`.** Pages of removed tabs are closed without being added back as new tabs. Ensō doesn't use Android Components' session restore, so nothing else can bring old tabs back.

## The three fixes I'd make first

1. **Tie the mirror to the model (finding 1).** Save the mirror only when the model has applied it, and record in `spaces.json` which mirror it reflects. Stop counting folder taps as edits. Add the mirror file to CLAUDE.md's restore steps. This removes the one bug that can undo Zen's changes in everyday use.
2. **Make the deletion guard a real stop (finding 4).** When it trips, upload nothing and say so in Sync with Zen. Return no changes when the model shares no space with the mirror. This is the backstop for every bug not found here.
3. **Close the two paths where one phone action deletes Zen tabs it shouldn't.** Skip blank tabs only when they have no server record (finding 3, one line). Delete a folder in two steps (finding 2). The larger follow-up is the mapper change from finding 2: place items by their own records, as Zen does.
