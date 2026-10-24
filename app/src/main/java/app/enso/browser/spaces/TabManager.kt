package app.enso.browser.spaces

import app.enso.browser.TabSwipeScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flow

/**
 * The one place tabs are opened, switched, closed and unloaded.
 *
 * The spaces model says which tabs exist and where they live; the [BrowserStore] holds the live
 * pages. Both use the same tab IDs. A tab only has a page in the BrowserStore once it's been
 * opened, so pinned tabs and Essentials can sit unloaded.
 */
class TabManager(
    private val spaces: SpacesStore,
    private val store: BrowserStore,
    private val tabsUseCases: TabsUseCases,
) {
    private val scope = MainScope()

    /** Tabs that had a page in the BrowserStore at the last sync. */
    private var loadedTabIds = emptySet<String>()

    /** Tabs we closed whose removal the sync may not have seen yet, so they aren't re-added as new. */
    private val closingTabIds = mutableSetOf<String>()

    fun start() {
        scope.launch {
            store.flow()
                .map { state ->
                    EngineSnapshot(
                        tabs = state.tabs.map { EngineTab(it.id, it.content.url, it.content.title, it.parentId) },
                        selectedTabId = state.selectedTabId,
                    )
                }
                .distinctUntilChanged()
                .collect(::syncFromEngine)
        }
    }

    /** Loads the current space's open tabs and selects its current tab. False when there's nothing to show. */
    fun restore(): Boolean {
        val state = spaces.state.value
        val space = state.currentSpace
        val selected = space.selectedTabId?.takeIf { state.find(it) != null }
            ?: space.tabs.firstOrNull()?.id
            ?: return false
        val tabs = (space.tabs + listOfNotNull(state.find(selected)?.tab))
            .distinctBy { it.id }
            .filter { store.state.findTab(it.id) == null }
            .map { createTab(it.url, id = it.id, title = it.title) }
        store.dispatch(TabListAction.AddMultipleTabsAction(tabs))
        tabsUseCases.selectTab(selected)
        return true
    }

    fun select(tabId: String) {
        if (store.state.findTab(tabId) == null) {
            val tab = spaces.state.value.find(tabId)?.tab ?: return
            store.dispatch(TabListAction.AddTabAction(createTab(tab.url, id = tab.id, title = tab.title)))
        }
        tabsUseCases.selectTab(tabId)
    }

    /** Opens [url] in a new tab just below the current one. New searches pass [quick] to make a Quick Tab. */
    fun openNewTab(url: String, quick: Boolean = false) {
        val tab = Tab(id = newId(), url = url, quick = if (quick) QuickTab(lastViewedAt = now()) else null)
        spaces.update { it.addOpenTab(tab, afterId = store.state.selectedTabId) }
        store.dispatch(TabListAction.AddTabAction(createTab(url, id = tab.id), select = true))
    }

    /** Closes an open tab. Returns true when no tab is left to show. */
    fun close(tabId: String): Boolean {
        val nothingLeft = moveSelectionOff(tabId)
        closingTabIds += tabId
        spaces.update { it.removeTab(tabId) }
        tabsUseCases.removeTab(tabId)
        return nothingLeft
    }

    /** Unloads a pinned tab or Essential: the page goes away, the row stays, back at its pinned URL. */
    fun unload(tabId: String): Boolean {
        val nothingLeft = moveSelectionOff(tabId)
        spaces.update { it.resetToPinnedUrl(tabId) }
        if (store.state.findTab(tabId) != null) tabsUseCases.removeTab(tabId)
        return nothingLeft
    }

    /**
     * Replaces the spaces model in one go (a sync), closing the pages of tabs that no longer exist and moving off
     * the shown tab if it's gone.
     */
    fun applySpaces(transform: (SpacesState) -> SpacesState) {
        val after = transform(spaces.state.value)
        val kept = (after.essentials + after.spaces.flatMap { it.allTabs }).mapTo(mutableSetOf()) { it.id }
        val gone = store.state.tabs.map { it.id }.filterNot { it in kept }
        closingTabIds += gone
        spaces.update(transform)
        val selected = store.state.selectedTabId
        if (selected in gone) {
            val state = spaces.state.value
            val next = state.currentSpace.selectedTabId?.takeIf { it in kept && it != selected }
                ?: state.currentSpace.tabs.firstOrNull()?.id
            next?.let(::select)
        }
        gone.forEach { tabsUseCases.removeTab(it) }
    }

    /** Moves to the next (+1) or previous (-1) space, showing its last tab. False when there's none. */
    fun switchSpace(step: Int): Boolean {
        val state = spaces.state.value
        val index = state.spaces.indexOfFirst { it.id == state.currentSpaceId }
        val target = state.spaces.getOrNull(index + step) ?: return false
        spaces.update { it.copy(currentSpaceId = target.id) }
        target.selectedTabId?.takeIf { state.find(it) != null }?.let(::select)
        return true
    }

    /** Restarts the expiry clock of the Quick Tab on screen, if it is one. */
    fun markSelectedViewed() {
        val selected = store.state.selectedTabId ?: return
        spaces.update { it.markViewed(selected, now()) }
    }

    /** Closes Quick Tabs unviewed for longer than [lifetimeMs], except [keep]. True when no tab is left to show. */
    fun clearExpiredQuickTabs(lifetimeMs: Long, keep: String?): Boolean {
        val expired = spaces.state.value.expiredQuickTabs(now(), lifetimeMs) - setOfNotNull(keep)
        return expired.map { close(it) }.lastOrNull() ?: false
    }

    /** Moves to the next (+1) or previous (-1) tab in swipe order. False when there's none. */
    fun switchBy(step: Int, scope: TabSwipeScope): Boolean {
        val order = spaces.state.value.swipeOrder(scope, loadedTabIds)
        val index = order.indexOf(store.state.selectedTabId)
        val target = when {
            index >= 0 -> order.getOrNull(index + step)
            step > 0 -> order.firstOrNull()
            else -> order.lastOrNull()
        } ?: return false
        select(target)
        return true
    }

    /** If [tabId] is showing, shows its neighbor instead. True when there's none, leaving no tab selected. */
    private fun moveSelectionOff(tabId: String): Boolean {
        if (store.state.selectedTabId != tabId) return store.state.selectedTabId == null
        val next = spaces.state.value.nextAfterClosing(tabId) ?: return true
        select(next)
        return false
    }

    private fun now() = System.currentTimeMillis()

    /** Copies what pages did on their own (new windows, closing, navigating, switching) into the model. */
    private fun syncFromEngine(snapshot: EngineSnapshot) {
        val ids = snapshot.tabs.mapTo(mutableSetOf()) { it.id }
        val closedByEngine = loadedTabIds - ids
        closingTabIds.retainAll(ids)
        val now = now()
        spaces.update { initial ->
            var state = initial
            for (tab in snapshot.tabs) {
                if (tab.id in closingTabIds) continue
                state = if (state.find(tab.id) == null) {
                    // Opened by a page (target=_blank, window.open): goes below the tab that opened it,
                    // and is a Quick Tab when that one is.
                    val parent = tab.parentId?.let { state.find(it)?.tab }
                    val quick = parent?.quick?.let { QuickTab(lastViewedAt = now) }
                    state.addOpenTab(
                        Tab(tab.id, tab.url, tab.title, quick = quick),
                        afterId = tab.parentId ?: state.currentSpace.selectedTabId,
                    )
                } else {
                    state.syncContent(tab.id, tab.url, tab.title)
                }
            }
            for (id in closedByEngine) {
                val place = state.find(id)?.place ?: continue
                state = if (place is TabPlace.Open) state.removeTab(id) else state.resetToPinnedUrl(id)
            }
            val previous = state.currentSpace.selectedTabId
            val selected = snapshot.selectedTabId
            if (selected != previous) {
                // Leaving a Quick Tab and arriving at one both count as viewing it.
                previous?.let { state = state.markViewed(it, now) }
                selected?.let { state = state.markViewed(it, now) }
            }
            state = if (selected != null) state.select(selected) else state.clearSelection()
            state
        }
        loadedTabIds = ids
    }

    private data class EngineTab(val id: String, val url: String, val title: String, val parentId: String?)

    private data class EngineSnapshot(val tabs: List<EngineTab>, val selectedTabId: String?)
}
