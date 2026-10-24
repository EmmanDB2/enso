package app.enso.browser.spaces

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.random.Random

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
    /** A [app.enso.browser.ui.SpaceIcons] key, an emoji, or a Zen icon URL from sync. */
    val icon: String,
    /** ARGB. */
    val accent: Long,
    /** The Zen theme's gradient colors as ARGB, in Zen's order. Empty when the theme has none. */
    val gradient: List<Long> = emptyList(),
    /** The Zen container the space opens tabs in, or null for none. Decides which Essentials it shows. */
    val container: String? = null,
    /** Pinned tabs and folders, in order. */
    val pinned: List<PinnedItem> = emptyList(),
    /** Open tabs, in order. */
    val tabs: List<Tab> = emptyList(),
    val selectedTabId: String? = null,
) {
    /** Pinned tabs (including those in folders) and open tabs. Essentials aren't counted. */
    val allTabs: List<Tab> get() = pinned.flatMap { it.tabs } + tabs

    /** Every folder in the space, nested ones included, top to bottom. */
    val folders: List<Folder> get() = pinned.flatMap { it.folders }
}

@Serializable
sealed interface PinnedItem {
    val id: String

    /** Every tab in this item, including inside nested folders. */
    val tabs: List<Tab>

    /** This item if it's a folder, and every folder nested in it. */
    val folders: List<Folder>
}

@Serializable
@SerialName("tab")
data class PinnedTab(val tab: Tab) : PinnedItem {
    override val id: String get() = tab.id
    override val tabs: List<Tab> get() = listOf(tab)
    override val folders: List<Folder> get() = emptyList()
}

@Serializable
@SerialName("folder")
data class Folder(
    override val id: String,
    val name: String,
    val expanded: Boolean = false,
    /** Tabs and subfolders, in order. Zen folders can nest. */
    val items: List<PinnedItem> = emptyList(),
) : PinnedItem {
    override val tabs: List<Tab> get() = items.flatMap { it.tabs }
    override val folders: List<Folder> get() = listOf(this) + items.flatMap { it.folders }
}

@Serializable
data class Tab(
    val id: String,
    val url: String,
    val title: String = "",
    /** Pinned tabs and Essentials go back to this URL when unloaded. */
    val pinnedUrl: String? = null,
    /** Set while this is a Quick Tab: temporary, never synced, cleared after a while unviewed. */
    val quick: QuickTab? = null,
    /** Mirrors a tab record in Zen's spaces sync. Tabs without it exist only on the phone. */
    val synced: Boolean = false,
    /** For synced tabs: the URL Zen's record had when last applied, to tell Zen's navigations from the phone's. */
    val zenUrl: String? = null,
    /** For Essentials: the Zen container it belongs to, or null for none. */
    val container: String? = null,
)

@Serializable
data class QuickTab(val lastViewedAt: Long)

/** Where a tab lives. */
sealed interface TabPlace {
    data object Essential : TabPlace

    /** [folder] is the innermost folder holding the tab, if any. */
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
        findPinned(space.pinned, tabId, folder = null)?.let { (tab, folder) ->
            return TabEntry(tab, TabPlace.Pinned(space, folder))
        }
        space.tabs.firstOrNull { it.id == tabId }?.let { return TabEntry(it, TabPlace.Open(space)) }
    }
    return null
}

private fun findPinned(items: List<PinnedItem>, tabId: String, folder: Folder?): Pair<Tab, Folder?>? {
    for (item in items) {
        when (item) {
            is PinnedTab -> if (item.id == tabId) return item.tab to folder
            is Folder -> findPinned(item.items, tabId, item)?.let { return it }
        }
    }
    return null
}

/**
 * The Essentials [space] shows. Like Zen, each container has its own Essentials unless [perContainer] is off,
 * in which case every space shows all of them.
 */
fun SpacesState.essentialsFor(space: Space = currentSpace, perContainer: Boolean = true): List<Tab> =
    if (perContainer) essentials.filter { it.container == space.container } else essentials

/** Whether [tabId] shows in the current space: one of its tabs, or one of the Essentials it shows. */
fun SpacesState.isInCurrentSpace(tabId: String, essentialsPerContainer: Boolean = true): Boolean {
    val place = find(tabId)?.place ?: return false
    return place.space?.let { it.id == currentSpaceId }
        ?: essentialsFor(perContainer = essentialsPerContainer).any { it.id == tabId }
}

internal fun newId() = UUID.randomUUID().toString()

/** A tab id in Zen's format (`<time>-<uuid>`), so tabs made on the phone sync like Zen's own. */
internal fun newTabId() = "${System.currentTimeMillis()}-${UUID.randomUUID()}"

/** A folder id in Zen's (Firefox tab group) format, `<time>-<0..99>`. */
internal fun newFolderId() = "${System.currentTimeMillis()}-${Random.nextInt(100)}"
