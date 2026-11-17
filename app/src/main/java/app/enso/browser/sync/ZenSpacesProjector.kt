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
 * deleted, and a folder only once it has gone up emptied ([SpacesState.deletedFolders]). Ids the server's lists hold
 * that it never sent a record for stay in them; [gone] are the listed ids whose records were deleted, which don't.
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
    gone: Set<String> = emptySet(),
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

    /**
     * The open tabs of [space] that the server has records of but its copy of the space doesn't list, by itself or
     * in a split view. Zen left them out (its normal-tab sync is off, so it takes no part in the phone's open tabs),
     * or an edit only half arrived. The phone keeps them out of children too: putting them back would have the two
     * trade the space's record on every sync. Their own records still go up as usual, and Zen lists them by itself
     * if it takes them in.
     */
    fun leftOut(space: Space, record: ZenRecord): Set<String> {
        val children = record.data.strings("children").orEmpty()
        val listed = children.toSet() +
            children.flatMap { base[it]?.takeIf { child -> child.kind == KIND_SPLIT }?.data?.strings("tabs").orEmpty() }
        return space.tabs.mapNotNullTo(mutableSetOf()) { tab ->
            val data = base[tab.id]?.takeIf { it.kind == KIND_TAB }?.data
            tab.id.takeIf { data != null && it !in listed && data.bool("pinned") == false && data.string("workspaceUuid") == space.id }
        }
    }

    /**
     * [ids], a list the phone writes (a parent's children, a group of Essentials, a split view's tabs), with the
     * ids of [onServer], the server's copy of that list, that the phone has no record of: each goes back after the
     * id it followed there. Zen lists a tab that has no page yet without sending a record for it, and a record can
     * fail to read. The phone can't show those, and it mustn't write them out of the list either: Zen puts them
     * back, and the two trade the record on every sync. An id whose record was deleted ([gone]) is only left over
     * in the list, and isn't kept.
     */
    fun keepingUnknown(ids: List<String>, onServer: List<String>?): List<String> {
        fun unknown(id: String) = id !in base && id !in gone
        if (onServer == null || onServer.none(::unknown)) return ids
        val result = ids.toMutableList()
        var previous: String? = null
        for (id in onServer) {
            if (unknown(id) && id !in result) result.add(previous?.let { result.indexOf(it) + 1 } ?: 0, id)
            if (id in result) previous = id
        }
        return result
    }

    fun put(id: String, kind: String, data: JsonObject) {
        projected[id] = ZenRecord(id, kind, data)
    }

    fun putTab(tab: Tab, pinned: Boolean, essential: Boolean, space: String?, folder: String?, container: String?) {
        val baseData = base[tab.id]?.takeIf { it.kind == KIND_TAB }?.data
        val baseUrl = baseData?.string("url")
        // A pinned tab keeps the page it was pinned on. A normal tab from Zen keeps Zen's page unless navigations
        // sync: pages often redirect on the phone (to a login page, say), and that mustn't reach the desktop.
        val wanted = when {
            pinned -> tab.pinnedUrl ?: baseUrl ?: tab.url
            baseUrl != null && !syncTabNavigations -> baseUrl
            else -> tab.url
        }
        // Zen doesn't sync blank tabs, so a new one waits for a page. A tab Zen already has keeps Zen's page: it's
        // only blank on the phone, and leaving it out here would delete it from Zen.
        val url = if (wanted.isBlankPage()) baseUrl?.takeUnless { it.isBlankPage() } ?: return else wanted
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
        fun place(id: String, members: List<String>, least: Int = 2, data: () -> JsonObject) {
            if (id in keptSplits || members.size < least || !result.containsAll(members)) return
            val at = result.indexOfFirst { it in members }
            result.removeAll(members.toSet())
            result.add(at.coerceAtMost(result.size), id)
            keptSplits += id
            put(id, KIND_SPLIT, data())
        }
        for (split in space.splits) {
            val baseData = base[split.id]?.takeIf { it.kind == KIND_SPLIT }?.data
            place(split.id, split.tabIds) {
                (baseData ?: JsonObject(emptyMap())).with(
                    "splitId" to split.id,
                    "gridType" to split.layout.zenName,
                    "pinned" to pinned,
                    "tabs" to keepingUnknown(split.tabIds, baseData?.strings("tabs")),
                    "workspaceUuid" to space.id,
                    "folderId" to folder,
                )
            }
        }
        for (split in zenOnlySplits) {
            // Its tabs the phone has no record of count as sitting with the others, so one tab the phone has is
            // enough to keep the split's place.
            val tabs = split.data.strings("tabs").orEmpty()
            val known = tabs.filter { it in base || it in gone }
            place(split.id, known, least = if (known.size < tabs.size) 1 else 2) {
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
                    if (!live) data = data.with("children" to keepingUnknown(children, baseData?.strings("children")))
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
        val unlisted = leftOut(space, record)
        val openIds = withSplits(open.map { it.id }.filter { it in projected && it !in unlisted }, space, folder = null, pinned = false)
        val children = keepingUnknown(pinnedIds + openIds, record.data.strings("children"))
        put(record.id, KIND_SPACE, record.data.with("children" to children))
    }

    for (tab in state.essentials) {
        putTab(tab, pinned = true, essential = true, space = null, folder = null, container = tab.container)
    }
    base[LAYOUT_ID]?.takeIf { it.kind == KIND_LAYOUT }?.let { layout ->
        val own = state.essentials.filter { it.id in projected }
            .groupBy({ it.container ?: DEFAULT_CONTAINER }, { it.id })
        // Zen's groups first, each with what it holds that the phone has no record of, then the phone's new ones.
        val groups = LinkedHashMap<String, List<String>>()
        for ((container, ids) in (layout.data["essentials"] as? JsonObject).orEmpty()) {
            val onServer = ids.strings()
            val kept = keepingUnknown(own[container].orEmpty(), onServer)
            // A group that was empty on the server stays; one the phone emptied goes.
            if (kept.isNotEmpty() || onServer.isEmpty()) groups[container] = kept
        }
        own.forEach { (container, ids) -> groups.getOrPut(container) { ids } }
        put(LAYOUT_ID, KIND_LAYOUT, layout.data.with("essentials" to groups))
    }

    // Folders the phone deleted go in two steps, because Zen deletes the tabs still inside a folder when the
    // folder's deletion arrives. While the server's copy has anything in it, or any record still says it's in the
    // folder (a move Zen's change held back, say), the folder only goes up emptied. Its deletion follows in a later
    // sync, once the server shows that everything has left it. If Zen puts something in it meanwhile, the folder's
    // record changes on the server, and Zen's version wins as for any record.
    val folderDeletions = state.deletedFolders.filter { id ->
        val record = base[id]?.takeIf { it.kind == KIND_FOLDER } ?: return@filter false
        if (id in projected) return@filter false
        val inUse = record.data.strings("children").orEmpty().isNotEmpty() || base.values.any { it.isIn(id) }
        if (inUse) put(id, KIND_FOLDER, record.data.with("children" to emptyList<String>()))
        !inUse
    }

    // Deletions: only what the phone showed, so an item it never displayed isn't removed for being missing. A folder
    // is only deleted when the user deleted it (above), never for being missing from the model.
    val shown = shownIds(base)
    val deletions = shown.filter { id ->
        id !in projected && base[id]?.kind != KIND_FOLDER && (syncOpenTabs || base[id]?.data?.bool("pinned") != false)
    }
    return projected.values.filter { base[it.id] != it }.map { ZenChange(it.id, it) } +
        (deletions + folderDeletions).map { ZenChange(it, null) }
}

/** Whether this record says it sits directly in folder [folderId]: a tab or split by its folder, a folder by its parent. */
internal fun ZenRecord.isIn(folderId: String): Boolean = when (kind) {
    KIND_TAB, KIND_SPLIT -> data.string("folderId") == folderId
    KIND_FOLDER -> data.string("parentFolderId") == folderId
    else -> false
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

private fun String.isBlankPage() = isBlank() || this == BLANK_PAGE

private const val BLANK_PAGE = "about:blank"
