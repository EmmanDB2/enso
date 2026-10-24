package app.enso.browser.spaces

import app.enso.browser.TabSwipeScope

// Pure edits on SpacesState. Each returns the state unchanged when the tab or folder isn't found.

fun SpacesState.updateSpace(spaceId: String, transform: (Space) -> Space) =
    copy(spaces = spaces.map { if (it.id == spaceId) transform(it) else it })

/** Rewrites every space's pinned items, folders nested at any depth included, item by item. */
private fun SpacesState.mapPinned(transform: (PinnedItem) -> List<PinnedItem>): SpacesState {
    fun walk(items: List<PinnedItem>): List<PinnedItem> = items.flatMap { item ->
        val inner = if (item is Folder) item.copy(items = walk(item.items)) else item
        transform(inner)
    }
    return copy(spaces = spaces.map { it.copy(pinned = walk(it.pinned)) })
}

private fun SpacesState.updateFolder(folderId: String, transform: (Folder) -> Folder) =
    mapPinned { if (it is Folder && it.id == folderId) listOf(transform(it)) else listOf(it) }

/** Applies [transform] to the tab wherever it lives. */
fun SpacesState.updateTab(tabId: String, transform: (Tab) -> Tab): SpacesState {
    val edit = { tab: Tab -> if (tab.id == tabId) transform(tab) else tab }
    return mapPinned { if (it is PinnedTab) listOf(PinnedTab(edit(it.tab))) else listOf(it) }.let { state ->
        state.copy(
            essentials = state.essentials.map(edit),
            spaces = state.spaces.map { it.copy(tabs = it.tabs.map(edit)) },
        )
    }
}

/** Takes the tab out of wherever it lives. */
fun SpacesState.removeTab(tabId: String): SpacesState =
    mapPinned { if (it is PinnedTab && it.id == tabId) emptyList() else listOf(it) }.let { state ->
        state.copy(
            essentials = state.essentials.filterNot { it.id == tabId },
            spaces = state.spaces.map { space -> space.copy(tabs = space.tabs.filterNot { it.id == tabId }) },
        )
    }

/** Adds [tab] to the current space's open tabs, just below [afterId], or at the top when that isn't an open tab. */
fun SpacesState.addOpenTab(tab: Tab, afterId: String?) = updateSpace(currentSpaceId) { space ->
    val index = space.tabs.indexOfFirst { it.id == afterId } + 1
    space.copy(tabs = space.tabs.toMutableList().apply { add(index, tab) })
}

fun SpacesState.select(tabId: String): SpacesState {
    val spaceId = find(tabId)?.place?.space?.id ?: currentSpaceId
    return updateSpace(spaceId) { it.copy(selectedTabId = tabId) }
}

fun SpacesState.syncContent(tabId: String, url: String, title: String) =
    updateTab(tabId) { it.copy(url = url, title = title) }

/** Back to the pinned URL. The title is only kept if the tab never left that URL. */
fun SpacesState.resetToPinnedUrl(tabId: String) = updateTab(tabId) { tab ->
    val pinnedUrl = tab.pinnedUrl ?: return@updateTab tab
    if (tab.url == pinnedUrl) tab else tab.copy(url = pinnedUrl, title = "")
}

/** Pinning a Quick Tab (or making it an Essential) keeps it, so it stops being quick. */
private fun Tab.asPinned() = copy(pinnedUrl = pinnedUrl ?: url, quick = null)

/** Keeps a Quick Tab: it loses its gold outline and becomes a normal open tab. */
fun SpacesState.keepQuickTab(tabId: String) = updateTab(tabId) { it.copy(quick = null) }

/** Records that a Quick Tab was on screen at [now], restarting its expiry clock. */
fun SpacesState.markViewed(tabId: String, now: Long) =
    updateTab(tabId) { tab -> tab.quick?.let { tab.copy(quick = QuickTab(lastViewedAt = now)) } ?: tab }

/** Quick Tabs not viewed for longer than [lifetimeMs]. */
fun SpacesState.expiredQuickTabs(now: Long, lifetimeMs: Long): List<String> =
    spaces.flatMap { it.tabs }
        .filter { tab -> tab.quick?.let { now - it.lastViewedAt > lifetimeMs } == true }
        .map { it.id }

fun SpacesState.clearSelection() = updateSpace(currentSpaceId) { it.copy(selectedTabId = null) }

fun SpacesState.pin(tabId: String): SpacesState {
    val entry = find(tabId) ?: return this
    val spaceId = entry.place.space?.id ?: currentSpaceId
    return removeTab(tabId).updateSpace(spaceId) { it.copy(pinned = it.pinned + PinnedTab(entry.tab.asPinned())) }
}

/** Moves a pinned tab or Essential to the top of the open tabs. */
fun SpacesState.unpin(tabId: String): SpacesState {
    val entry = find(tabId) ?: return this
    val spaceId = entry.place.space?.id ?: currentSpaceId
    val tab = entry.tab.copy(pinnedUrl = null)
    return removeTab(tabId).updateSpace(spaceId) { it.copy(tabs = listOf(tab) + it.tabs) }
}

fun SpacesState.addToEssentials(tabId: String): SpacesState {
    val entry = find(tabId) ?: return this
    if (entry.place is TabPlace.Essential) return this
    return removeTab(tabId).let { it.copy(essentials = it.essentials + entry.tab.asPinned()) }
}

fun SpacesState.moveToFolder(tabId: String, folderId: String): SpacesState {
    val entry = find(tabId) ?: return this
    return removeTab(tabId).updateFolder(folderId) { it.copy(items = it.items + PinnedTab(entry.tab.asPinned())) }
}

fun SpacesState.moveToNewFolder(tabId: String, folderName: String): SpacesState {
    val entry = find(tabId) ?: return this
    val spaceId = entry.place.space?.id ?: currentSpaceId
    val folder = Folder(id = newId(), name = folderName, expanded = true, items = listOf(PinnedTab(entry.tab.asPinned())))
    return removeTab(tabId).updateSpace(spaceId) { it.copy(pinned = it.pinned + folder) }
}

/** Moves a tab out of its folder to just below the folder, still pinned. */
fun SpacesState.removeFromFolder(tabId: String): SpacesState {
    val entry = find(tabId) ?: return this
    val folder = (entry.place as? TabPlace.Pinned)?.folder ?: return this
    return removeTab(tabId).mapPinned { if (it.id == folder.id) listOf(it, PinnedTab(entry.tab)) else listOf(it) }
}

fun SpacesState.toggleFolder(folderId: String) = updateFolder(folderId) { it.copy(expanded = !it.expanded) }

fun SpacesState.renameFolder(folderId: String, name: String) = updateFolder(folderId) { it.copy(name = name) }

/** Deletes a folder. What was in it stays pinned where the folder was. */
fun SpacesState.deleteFolder(folderId: String) =
    mapPinned { if (it is Folder && it.id == folderId) it.items else listOf(it) }

/** The tab to show after [tabId] closes or unloads: its neighbor among the open tabs, else the first open tab. */
fun SpacesState.nextAfterClosing(tabId: String): String? {
    val open = currentSpace.tabs.filterNot { it.id == tabId }
    val index = currentSpace.tabs.indexOfFirst { it.id == tabId }
    return if (index >= 0) open.getOrNull(index)?.id ?: open.lastOrNull()?.id else open.firstOrNull()?.id
}

/** Tab IDs the address-field swipe moves through, in tab-sheet order. */
fun SpacesState.swipeOrder(scope: TabSwipeScope, loadedTabIds: Set<String>): List<String> {
    val open = currentSpace.tabs.map { it.id }
    return when (scope) {
        TabSwipeScope.OpenTabs -> open
        TabSwipeScope.AllTabs ->
            (essentials + currentSpace.pinned.flatMap { it.tabs }).map { it.id }.filter { it in loadedTabIds } + open
    }
}
