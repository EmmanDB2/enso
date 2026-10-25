package app.enso.browser.spaces

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

/**
 * Keeps a Quick Tab as a normal tab of another space, below that space's current tab. If it was its own
 * space's current tab, the tab after it takes over there.
 */
fun SpacesState.keepQuickTabIn(tabId: String, spaceId: String): SpacesState {
    val entry = find(tabId) ?: return this
    val from = entry.place.space ?: return this
    if (from.id == spaceId) return keepQuickTab(tabId)
    if (spaces.none { it.id == spaceId }) return this
    val index = from.tabs.indexOfFirst { it.id == tabId }
    val next = from.tabs.getOrNull(index + 1) ?: from.tabs.getOrNull(index - 1)
    return removeTab(tabId)
        .updateSpace(from.id) { if (it.selectedTabId == tabId) it.copy(selectedTabId = next?.id) else it }
        .updateSpace(spaceId) { space ->
            val at = space.tabs.indexOfFirst { it.id == space.selectedTabId } + 1
            space.copy(tabs = space.tabs.toMutableList().apply { add(at, entry.tab.copy(quick = null)) })
        }
}

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

/** Makes a tab an Essential of its space's container, which is where Zen puts it too. */
fun SpacesState.addToEssentials(tabId: String): SpacesState {
    val entry = find(tabId) ?: return this
    if (entry.place is TabPlace.Essential) return this
    val container = (entry.place.space ?: currentSpace).container
    return removeTab(tabId).let { it.copy(essentials = it.essentials + entry.tab.asPinned().copy(container = container)) }
}

fun SpacesState.moveToFolder(tabId: String, folderId: String): SpacesState {
    val entry = find(tabId) ?: return this
    return removeTab(tabId).updateFolder(folderId) { it.copy(items = it.items + PinnedTab(entry.tab.asPinned())) }
}

fun SpacesState.moveToNewFolder(tabId: String, folderName: String): SpacesState {
    val entry = find(tabId) ?: return this
    val spaceId = entry.place.space?.id ?: currentSpaceId
    val folder = Folder(id = newFolderId(), name = folderName, expanded = true, items = listOf(PinnedTab(entry.tab.asPinned())))
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

/**
 * Splits [tabId], the page on screen, with [withTabId], which goes on its right (board 7). When both are open
 * tabs, or both pinned, [withTabId] moves to just after [tabId] (into its folder too), since Zen keeps a split's
 * tabs together. A pinned tab and an open one each stay where they are. A tab already in a split leaves it.
 */
fun SpacesState.splitWith(tabId: String, withTabId: String): SpacesState {
    val left = find(tabId) ?: return this
    val right = find(withTabId) ?: return this
    val space = left.place.space ?: return this
    if (tabId == withTabId || right.place.space?.id != space.id) return this
    val moved = when {
        left.place is TabPlace.Open && right.place is TabPlace.Open -> removeTab(withTabId).updateSpace(space.id) {
            val at = it.tabs.indexOfFirst { tab -> tab.id == tabId } + 1
            it.copy(tabs = it.tabs.toMutableList().apply { add(at, right.tab) })
        }
        left.place is TabPlace.Pinned && right.place is TabPlace.Pinned ->
            removeTab(withTabId).mapPinned { if (it.id == tabId) listOf(it, PinnedTab(right.tab)) else listOf(it) }
        else -> this
    }
    return moved.updateSpace(space.id) {
        val others = it.splits.map { split -> split.copy(tabIds = split.tabIds - tabId - withTabId) }
        it.copy(splits = others + Split(newSplitId(), listOf(tabId, withTabId)))
    }.withValidSplits()
}

fun SpacesState.unsplit(splitId: String) =
    copy(spaces = spaces.map { space -> space.copy(splits = space.splits.filterNot { it.id == splitId }) })

/** Sets each page's share of the split's width, left to right; empty makes them equal. */
fun SpacesState.resizeSplit(splitId: String, sizes: List<Float>) = copy(
    spaces = spaces.map { space ->
        space.copy(splits = space.splits.map { if (it.id == splitId) it.copy(sizes = sizes) else it })
    },
)

/**
 * Drops splits left with fewer than two of their space's tabs: after a close, or a move to another space or into
 * the Essentials. A tab is in one split at most. When a split loses a tab, its pages go back to equal sizes.
 */
fun SpacesState.withValidSplits(): SpacesState {
    if (spaces.all { it.splits.isEmpty() }) return this
    return copy(
        spaces = spaces.map { space ->
            if (space.splits.isEmpty()) return@map space
            val ids = space.allTabs.mapTo(HashSet()) { it.id }
            val taken = HashSet<String>()
            val kept = space.splits.mapNotNull { split ->
                val tabIds = split.tabIds.filter { it in ids && it !in taken }.distinct()
                if (tabIds.size < 2) return@mapNotNull null
                taken += tabIds
                val sizesFit = split.sizes.isEmpty() || split.sizes.size == tabIds.size
                if (tabIds == split.tabIds && sizesFit) split else split.copy(tabIds = tabIds, sizes = emptyList())
            }
            if (kept == space.splits) space else space.copy(splits = kept)
        },
    )
}

/** The tab to show after [tabId] closes or unloads: its neighbor among the open tabs, else the first open tab. */
fun SpacesState.nextAfterClosing(tabId: String): String? {
    val open = currentSpace.tabs.filterNot { it.id == tabId }
    val index = currentSpace.tabs.indexOfFirst { it.id == tabId }
    return if (index >= 0) open.getOrNull(index)?.id ?: open.lastOrNull()?.id else open.firstOrNull()?.id
}
