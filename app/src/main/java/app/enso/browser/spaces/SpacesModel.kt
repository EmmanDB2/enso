package app.enso.browser.spaces

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Local spaces data (build step 3), shaped after Zen so step 5 can sync it:
 * spaces with pinned tabs, folders and open tabs, plus Essentials shared by all spaces.
 */
@Serializable
data class SpacesState(
    val spaces: List<Space>,
    /** Shared across all spaces, like Zen. */
    val essentials: List<Tab> = emptyList(),
    val currentSpaceId: String,
) {
    val currentSpace: Space get() = spaces.first { it.id == currentSpaceId }

    companion object {
        fun initial(): SpacesState {
            val home = Space(id = newId(), name = "Home", icon = "home", accent = 0xFF4F7CFF)
            return SpacesState(spaces = listOf(home), currentSpaceId = home.id)
        }
    }
}

@Serializable
data class Space(
    val id: String,
    val name: String,
    /** A [app.enso.browser.ui.SpaceIcons] key, or an emoji. */
    val icon: String,
    /** ARGB. */
    val accent: Long,
    /** Pinned tabs and folders, in order. */
    val pinned: List<PinnedItem> = emptyList(),
    /** Open tabs, in order. */
    val tabs: List<Tab> = emptyList(),
    val selectedTabId: String? = null,
) {
    /** Pinned tabs (including those in folders) and open tabs. Essentials aren't counted. */
    val allTabs: List<Tab> get() = pinned.flatMap { it.tabs } + tabs
    val folders: List<Folder> get() = pinned.filterIsInstance<Folder>()
}

@Serializable
sealed interface PinnedItem {
    val id: String
    val tabs: List<Tab>
}

@Serializable
@SerialName("tab")
data class PinnedTab(val tab: Tab) : PinnedItem {
    override val id: String get() = tab.id
    override val tabs: List<Tab> get() = listOf(tab)
}

@Serializable
@SerialName("folder")
data class Folder(
    override val id: String,
    val name: String,
    val expanded: Boolean = false,
    override val tabs: List<Tab> = emptyList(),
) : PinnedItem

@Serializable
data class Tab(
    val id: String,
    val url: String,
    val title: String = "",
    /** Pinned tabs and Essentials go back to this URL when unloaded. */
    val pinnedUrl: String? = null,
)

/** Where a tab lives. */
sealed interface TabPlace {
    data object Essential : TabPlace
    data class Pinned(val space: Space, val folder: Folder?) : TabPlace
    data class Open(val space: Space) : TabPlace
}

val TabPlace.space: Space?
    get() = when (this) {
        TabPlace.Essential -> null
        is TabPlace.Pinned -> space
        is TabPlace.Open -> space
    }

data class TabEntry(val tab: Tab, val place: TabPlace)

fun SpacesState.find(tabId: String): TabEntry? {
    essentials.firstOrNull { it.id == tabId }?.let { return TabEntry(it, TabPlace.Essential) }
    for (space in spaces) {
        for (item in space.pinned) {
            val tab = item.tabs.firstOrNull { it.id == tabId } ?: continue
            return TabEntry(tab, TabPlace.Pinned(space, item as? Folder))
        }
        space.tabs.firstOrNull { it.id == tabId }?.let { return TabEntry(it, TabPlace.Open(space)) }
    }
    return null
}

internal fun newId() = UUID.randomUUID().toString()
