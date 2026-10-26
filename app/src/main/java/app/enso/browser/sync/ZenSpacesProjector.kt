package app.enso.browser.sync

import app.enso.browser.spaces.Folder
import app.enso.browser.spaces.PinnedItem
import app.enso.browser.spaces.PinnedTab
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Tab
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A record to upload, or a deletion when [record] is null. */
data class ZenChange(val id: String, val record: ZenRecord?)

/**
 * The phone's changes to Zen's spaces, as records to upload (phone → Zen).
 *
 * Every record starts from the server's copy in [base], and only what the phone owns changes: whether a tab is
 * pinned or an Essential, which space and folder it's in, new tabs' pages, folder names and children, and split
 * views' tabs. The rest (containers, icons, themes, split layouts, live folders) passes through, so a record the
 * phone didn't touch comes out identical and isn't sent. Only items the phone showed and the user removed are
 * deleted.
 *
 * A phone split goes to Zen when its tabs all go to Zen under the same parent: no Quick Tabs, and not a pinned tab
 * with an open one, which Zen's split view can't hold. Zen's own splits the phone hasn't taken on (before its first
 * sync with splits, say) pass through while their tabs still sit together, unless the phone ended them.
 */
internal fun zenChanges(
    state: SpacesState,
    base: Map<String, ZenRecord>,
    syncOpenTabs: Boolean,
    syncTabNavigations: Boolean,
): List<ZenChange> {
    val baseSpaces = base.values.filter { it.kind == KIND_SPACE }.associateBy { it.uuid }
    // Nothing downloaded yet: the phone has nothing to say to Zen.
    if (baseSpaces.isEmpty()) return emptyList()

    val projected = LinkedHashMap<String, ZenRecord>()
    val phoneSplits = state.spaces.flatMap { it.splits }
    val phoneSplitTabs = phoneSplits.flatMapTo(mutableSetOf()) { it.tabIds }
    // Zen's splits the phone doesn't have: they pass through as they are, unless the phone ended them or took one
    // of their tabs into a split of its own.
    val zenOnlySplits = base.values.filter { record ->
        record.kind == KIND_SPLIT && record.id !in state.endedSplits && phoneSplits.none { it.id == record.id } &&
            record.data.strings("tabs").orEmpty().none { it in phoneSplitTabs }
    }
    val keptSplits = mutableSetOf<String>()

    fun put(id: String, kind: String, data: JsonObject) {
        projected[id] = ZenRecord(id, kind, data)
    }

    fun putTab(tab: Tab, pinned: Boolean, essential: Boolean, space: String?, folder: String?, container: String?) {
        if (tab.url.isBlank() || tab.url == BLANK_PAGE) return // Zen doesn't sync blank tabs.
        val baseData = base[tab.id]?.takeIf { it.kind == KIND_TAB }?.data
        val baseUrl = baseData?.string("url")
        // A pinned tab keeps the page it was pinned on. A normal tab from Zen keeps Zen's page unless navigations
        // sync: pages often redirect on the phone (to a login page, say), and that mustn't reach the desktop.
        val url = when {
            pinned -> tab.pinnedUrl ?: baseUrl ?: tab.url
            baseUrl != null && !syncTabNavigations -> baseUrl
            else -> tab.url
        }
        val title = if (url == baseUrl) baseData.string("title").orEmpty() else tab.title
        val data = (baseData ?: newTabData(container)).with(
            "tabId" to tab.id,
            "url" to url,
            "title" to title,
            "pinned" to pinned,
            "essential" to essential,
            "workspaceUuid" to space,
            "folderId" to folder,
        )
        put(tab.id, KIND_TAB, data)
    }

    /**
     * The ids of [ids] (items directly under one parent) as Zen orders them, with each split view in place of its
     * tabs when they all sit under this parent: the space's own splits, then Zen's that pass through.
     */
    fun withSplits(ids: List<String>, space: Space, folder: String?, pinned: Boolean): List<String> {
        val result = ids.toMutableList()
        fun place(id: String, members: List<String>, data: () -> JsonObject) {
            if (id in keptSplits || members.size < 2 || !result.containsAll(members)) return
            val at = result.indexOfFirst { it in members }
            result.removeAll(members.toSet())
            result.add(at.coerceAtMost(result.size), id)
            keptSplits += id
            put(id, KIND_SPLIT, data())
        }
        for (split in space.splits) {
            place(split.id, split.tabIds) {
                (base[split.id]?.takeIf { it.kind == KIND_SPLIT }?.data ?: JsonObject(emptyMap())).with(
                    "splitId" to split.id,
                    "gridType" to split.layout.zenName,
                    "pinned" to pinned,
                    "tabs" to split.tabIds,
                    "workspaceUuid" to space.id,
                    "folderId" to folder,
                )
            }
        }
        for (split in zenOnlySplits) {
            place(split.id, split.data.strings("tabs").orEmpty()) {
                split.data.with("pinned" to pinned, "workspaceUuid" to space.id, "folderId" to folder)
            }
        }
        return result
    }

    /** Projects pinned [items] of [space] under [folder], returning their ids for the parent's children. */
    fun putPinned(items: List<PinnedItem>, space: Space, folder: String?): List<String> {
        for (item in items) {
            when (item) {
                is PinnedTab -> putTab(item.tab, pinned = true, essential = false, space.id, folder, space.container)
                is Folder -> {
                    val baseData = base[item.id]?.takeIf { it.kind == KIND_FOLDER }?.data
                    val children = putPinned(item.items, space, item.id)
                    // A live folder's children come from its feed in Zen, not from the phone.
                    val live = baseData?.get("live").let { it != null && it !is JsonNull }
                    var data = (baseData ?: newFolderData()).with(
                        "folderId" to item.id,
                        "name" to item.name,
                        "workspaceUuid" to space.id,
                        "parentFolderId" to folder,
                    )
                    if (!live) data = data.with("children" to children)
                    put(item.id, KIND_FOLDER, data)
                }
            }
        }
        return withSplits(items.map { it.id }.filter { it in projected }, space, folder, pinned = true)
    }

    for (space in state.spaces) {
        val record = baseSpaces[space.id] ?: continue // The phone can't create spaces.
        val pinnedIds = putPinned(space.pinned, space, folder = null)
        // Distinct ids: a tab must never be listed twice in what Zen receives.
        val open = space.tabs.filter { it.quick == null && (syncOpenTabs || it.synced) }.distinctBy { it.id }
        for (tab in open) {
            if (syncOpenTabs) {
                putTab(tab, pinned = false, essential = false, space.id, folder = null, space.container)
            } else {
                // Zen's own open tabs pass through untouched.
                base[tab.id]?.let { projected[tab.id] = it }
            }
        }
        val openIds = withSplits(open.map { it.id }.filter { it in projected }, space, folder = null, pinned = false)
        put(record.id, KIND_SPACE, record.data.with("children" to pinnedIds + openIds))
    }

    for (tab in state.essentials) {
        putTab(tab, pinned = true, essential = true, space = null, folder = null, container = tab.container)
    }
    base[LAYOUT_ID]?.takeIf { it.kind == KIND_LAYOUT }?.let { layout ->
        val groups = state.essentials.filter { it.id in projected }
            .groupBy({ it.container ?: DEFAULT_CONTAINER }, { it.id })
        put(LAYOUT_ID, KIND_LAYOUT, layout.data.with("essentials" to groups))
    }

    // Deletions: only what the phone showed, so an item it never displayed isn't removed for being missing.
    val shown = shownIds(base)
    val deletions = shown.filter { id ->
        id !in projected && (syncOpenTabs || base[id]?.data?.bool("pinned") != false)
    }
    return projected.values.filter { base[it.id] != it }.map { ZenChange(it.id, it) } +
        deletions.map { ZenChange(it, null) }
}

/** Ids of the tabs, folders and splits the phone shows from [base]: what a deletion may remove. */
private fun shownIds(base: Map<String, ZenRecord>): Set<String> {
    val shown = SpacesState.initial().withZenSpaces(ZenMirror(records = base))
    val tabs = shown.essentials.map { it.id } + shown.spaces.flatMap { space -> space.allTabs.map { it.id } }
    val folders = shown.spaces.flatMap { space -> space.folders.map { it.id } }
    val splits = base.values.filter { split ->
        split.kind == KIND_SPLIT && split.data.strings("tabs").orEmpty().let { it.isNotEmpty() && tabs.containsAll(it) }
    }.map { it.id }
    return (tabs + folders + splits).toSet()
}

private fun newTabData(container: String?) = JsonObject(emptyMap()).with(
    "icon" to "",
    "containerGuid" to container,
    "staticLabel" to null,
    "hasStaticIcon" to false,
    // Like Zen, a tab that takes its space's container is marked as having the default container.
    "defaultContainer" to (container != null),
)

private fun newFolderData() = JsonObject(emptyMap()).with("icon" to null, "live" to null)

private const val BLANK_PAGE = "about:blank"
