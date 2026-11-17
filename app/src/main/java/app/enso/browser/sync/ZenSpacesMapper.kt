package app.enso.browser.sync

import app.enso.browser.spaces.DEFAULT_THEME_OPACITY
import app.enso.browser.spaces.Folder
import app.enso.browser.spaces.PinnedItem
import app.enso.browser.spaces.PinnedTab
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Split
import app.enso.browser.spaces.SplitLayout
import app.enso.browser.spaces.Tab
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Applies Zen's spaces from [mirror] to the phone (read-only sync, Zen → phone).
 *
 * Zen decides the spaces, their order and looks, pinned tabs, folders, split views and Essentials. The phone keeps
 * what's its own and Zen hasn't got yet (Quick Tabs, open tabs, and the tabs it pinned, folders it made and
 * Essentials it added), its own splits, selection, folder open/closed states and split sizes. Phone spaces Zen
 * doesn't have, like the starting "Home", go away; what's the phone's own in them moves to the first Zen space.
 */
fun SpacesState.withZenSpaces(mirror: ZenMirror): SpacesState {
    val records = mirror.records
    val spaceRecords = records.values.filter { it.kind == KIND_SPACE }
    // Nothing from Zen yet: never empty the phone.
    if (spaceRecords.isEmpty()) return this

    val layout = records[LAYOUT_ID]?.takeIf { it.kind == KIND_LAYOUT }?.data
    val order = layout?.strings("spaces").orEmpty()
    val ordered = spaceRecords.sortedBy { record -> order.indexOf(record.uuid).let { if (it < 0) Int.MAX_VALUE else it } }

    val localTabs = (essentials + spaces.flatMap { it.allTabs }).associateBy { it.id }
    val localFolders = spaces.flatMap { it.folders }.associateBy { it.id }
    val localSplits = spaces.flatMap { it.splits }.associateBy { it.id }
    // Splits the phone ended stay ended until Zen has dropped them too.
    val ended = endedSplits.filterTo(mutableSetOf()) { records[it]?.kind == KIND_SPLIT }
    // Folders the phone deleted stay gone while Zen's copy is empty: the sync has emptied it and deletes it next.
    // One that has something in it is Zen's again (Zen changed it, or the emptied folder never went up), so it
    // comes back, and one Zen no longer has is done with.
    val deleted = deletedFolders.filterTo(mutableSetOf()) { id ->
        records[id]?.takeIf { it.kind == KIND_FOLDER }?.let { it.data.strings("children").orEmpty().isEmpty() } ?: false
    }

    /** A tab from Zen's record, keeping what the phone knows about its page if it's the same one. */
    fun tabFrom(id: String): Tab? {
        val data = records[id]?.takeIf { it.kind == KIND_TAB }?.data ?: return null
        val url = data.string("url")?.ifEmpty { null } ?: return null
        val title = data.string("staticLabel")?.ifEmpty { null } ?: data.string("title").orEmpty()
        val local = localTabs[id]
        // Unless Zen moved the tab to another page since, keep what the phone shows.
        val samePage = local != null && (local.zenUrl ?: local.pinnedUrl ?: local.url) == url
        return Tab(
            id = id,
            url = if (samePage) local.url else url,
            title = if (samePage) local.title.ifEmpty { title } else title,
            pinnedUrl = url.takeIf { data.bool("pinned") != false },
            synced = true,
            zenUrl = url,
        )
    }

    /** A split view from Zen, with the sizes the phone gave it while it keeps the same pages and layout. */
    fun splitFrom(record: ZenRecord, tabs: List<Tab>): Split? {
        if (tabs.size < 2) return null
        val layout = SplitLayout.ofZen(record.data.string("gridType"))
        val sizes = localSplits[record.id]
            ?.takeIf { it.layout == layout && it.tabIds == tabs.map { tab -> tab.id } }
            ?.sizes.orEmpty()
        return Split(record.id, tabs.map { it.id }, layout, sizes, synced = true)
    }

    /**
     * A space's or folder's children: pinned items, plus unpinned tabs (synced only when Zen's option is on). Split
     * views among them go to [splits], and their tabs stay in place.
     */
    fun childrenOf(ids: List<String>, seen: MutableSet<String>, splits: MutableList<Split>): Pair<List<PinnedItem>, List<Tab>> {
        val pinned = mutableListOf<PinnedItem>()
        val open = mutableListOf<Tab>()
        for (id in ids) {
            if (!seen.add(id)) continue
            val record = records[id] ?: continue // Not received yet; it shows once it arrives.
            when (record.kind) {
                KIND_TAB -> tabFrom(id)?.let { if (it.pinnedUrl != null) pinned += PinnedTab(it) else open += it }
                KIND_FOLDER -> {
                    if (id in deleted) continue
                    val (items, folderOpen) = childrenOf(record.data.strings("children").orEmpty(), seen, splits)
                    pinned += Folder(
                        id = id,
                        name = record.data.string("name").orEmpty(),
                        expanded = localFolders[id]?.expanded ?: false,
                        items = items,
                        synced = true,
                    )
                    open += folderOpen
                }
                KIND_SPLIT -> {
                    val tabs = record.data.strings("tabs").orEmpty().filter { seen.add(it) }.mapNotNull(::tabFrom)
                    tabs.forEach { if (it.pinnedUrl != null) pinned += PinnedTab(it) else open += it }
                    if (id !in ended) splitFrom(record, tabs)?.let(splits::add)
                }
            }
        }
        return pinned to open
    }

    // Zen keeps Essentials per container: layout.essentials maps a container guid ("default" for none) to its tabs.
    val essentialGroups = (layout?.get("essentials") as? JsonObject)?.mapValues { it.value.strings() }
        ?: mapOf(DEFAULT_CONTAINER to records.values.filter { it.kind == KIND_TAB && it.data.bool("essential") == true }.map { it.id })
    val essentials = essentialGroups.flatMap { (container, ids) ->
        ids.mapNotNull(::tabFrom).map { it.copy(container = container.takeUnless { it == DEFAULT_CONTAINER }) }
    }.distinctBy { it.id }

    // An id is one item in one place. An Essential isn't also a tab of a space that still lists it, and nothing
    // shows twice.
    val seen = essentials.mapTo(mutableSetOf()) { it.id }
    val fromZen = ordered.map { record ->
        val splits = mutableListOf<Split>()
        val (pinned, open) = childrenOf(record.data.strings("children").orEmpty(), seen, splits)
        Triple(pinned, open, splits)
    }
    // An open tab belongs to the space its own record names, as in Zen, where a space's children only put its tabs
    // in order. One that no children list still shows. Zen with its normal-tab sync off (its default) leaves the
    // phone's kept tabs out of every space's children, and an edit that only half arrived can leave one out too.
    // Only its deletion takes it off the phone.
    val leftOut = records.values
        .filter { it.kind == KIND_TAB && it.id !in seen && it.data.bool("pinned") == false && it.data.bool("essential") != true }
        .sortedBy { it.id }
        .groupBy({ it.data.string("workspaceUuid") }, { it.id })
        .mapValues { (_, ids) -> ids.mapNotNull(::tabFrom) }
    // Every tab Zen has, wherever it put it. A tab the phone still counts as its own (uploaded but not yet marked as
    // synced, or from a spaces.json that was put back) is Zen's from here on, in Zen's place: never keep both. Two
    // copies would be listed twice in what goes back to Zen, and closing one would close both.
    val zenTabs = essentials.mapTo(mutableSetOf()) { it.id }
    for ((pinned, open, _) in fromZen) {
        pinned.flatMapTo(zenTabs) { item -> item.tabs.map { it.id } }
        open.mapTo(zenTabs) { it.id }
    }
    leftOut.values.forEach { tabs -> tabs.mapTo(zenTabs) { it.id } }
    // What Zen doesn't have yet of the phone's own pinned side: tabs it pinned, folders it made and Essentials it
    // added that haven't gone up (all of them before the first sync; the latest ones when the mirror starts over, or
    // when part of an edit had to wait). They stay, as the phone's own open tabs do, and the next sync sends them.
    val zenFolders = fromZen.flatMapTo(mutableSetOf()) { (pinned, _, _) -> pinned.flatMap { item -> item.folders.map { it.id } } }
    val intoZenFolders = mutableMapOf<String, MutableList<PinnedItem>>()

    /** The phone's own among [items], as they go on top of Zen's: folders of Zen's get theirs through [intoZenFolders]. */
    fun own(items: List<PinnedItem>): List<PinnedItem> = items.flatMap { item ->
        when (item) {
            is PinnedTab -> if (item.tab.synced || item.id in zenTabs) emptyList() else listOf(item)
            is Folder -> {
                val inside = own(item.items)
                when {
                    inside.isEmpty() -> emptyList()
                    // Zen has the folder: the phone's own tabs stay in it.
                    item.id in zenFolders -> emptyList<PinnedItem>().also { intoZenFolders.getOrPut(item.id) { mutableListOf() } += inside }
                    // The folder was Zen's and is gone there: the phone's own tabs move out of it.
                    item.synced -> inside
                    // The phone's own folder, with what's the phone's own in it.
                    else -> listOf(item.copy(items = inside))
                }
            }
        }
    }
    val ownPinned = spaces.associate { it.id to own(it.pinned) }

    /** Zen's pinned [items] with the phone's own tabs back in Zen's folders. */
    fun withOwn(items: List<PinnedItem>): List<PinnedItem> = items.map { item ->
        if (item is Folder) item.copy(items = withOwn(item.items) + intoZenFolders[item.id].orEmpty()) else item
    }

    val zenSpaces = ordered.zip(fromZen) { record, (zenPinned, syncedOpen, zenSplits) ->
        val uuid = record.uuid
        val local = spaces.firstOrNull { it.id == uuid }
        val pinned = withOwn(zenPinned) + ownPinned[uuid].orEmpty()
        Space(
            id = uuid,
            name = record.data.string("name").orEmpty(),
            icon = record.data.string("icon").orEmpty(),
            accent = accentOf(record.data["theme"]) ?: local?.accent ?: DEFAULT_ACCENT,
            gradient = gradientOf(record.data["theme"]),
            themeOpacity = opacityOf(record.data["theme"]),
            container = record.data.string("containerGuid"),
            pinned = pinned,
            tabs = withPhoneOnlyTabs(syncedOpen, leftOut[uuid].orEmpty(), local?.tabs.orEmpty(), zenTabs),
            selectedTabId = local?.selectedTabId,
            // Zen's splits, then the phone's own: ones Zen can't hold (with a Quick Tab, or pinned and open tabs
            // together) and new ones not sent yet. A phone split that was Zen's and is gone there ended on the
            // desktop, unless the phone has made it one Zen can't hold: then the phone took it out of Zen itself,
            // and it stays as the phone's own. Where a tab ends up in two, Zen's wins as the model updates.
            splits = zenSplits + local?.let { space ->
                space.splits.filter { split -> zenSplits.none { it.id == split.id } && !(split.synced && space.zenCanHold(split)) }
                    .map { if (it.synced) it.copy(synced = false) else it }
            }.orEmpty(),
        )
    }

    // Phone spaces Zen doesn't have: what's the phone's own in them moves to the first Zen space.
    val zenIds = zenSpaces.mapTo(mutableSetOf()) { it.id }
    val orphans = spaces.filterNot { it.id in zenIds }
    val movedTabs = orphans.flatMap { space -> space.tabs.filterNot { it.synced || it.id in zenTabs } }
    val movedPinned = orphans.flatMap { ownPinned[it.id].orEmpty() }
    val currentWasOrphan = currentSpaceId !in zenIds
    val first = zenSpaces.first().let { space ->
        val movedSelection = orphans.firstOrNull { it.id == currentSpaceId }?.selectedTabId
            ?.takeIf { id -> movedTabs.any { it.id == id } }
        space.copy(
            pinned = space.pinned + movedPinned,
            tabs = space.tabs + movedTabs,
            selectedTabId = if (currentWasOrphan) movedSelection ?: space.selectedTabId else space.selectedTabId,
        )
    }

    return copy(
        spaces = listOf(first) + zenSpaces.drop(1),
        essentials = essentials + this.essentials.filter { !it.synced && it.id !in zenTabs },
        currentSpaceId = if (currentWasOrphan) first.id else currentSpaceId,
        endedSplits = ended,
        deletedFolders = deleted,
    )
}

/**
 * Whether Zen can hold [split] as this space has it: none of its tabs is a Quick Tab, and they all sit under one
 * parent (the open tabs, the pinned tabs, or one folder). Those are the splits the phone sends.
 */
private fun Space.zenCanHold(split: Split): Boolean {
    // Each tab's parent: whether it's pinned, and its folder.
    val parents = HashMap<String, Pair<Boolean, String?>>()
    fun walk(items: List<PinnedItem>, folder: String?) {
        for (item in items) {
            when (item) {
                is PinnedTab -> parents[item.id] = true to folder
                is Folder -> walk(item.items, item.id)
            }
        }
    }
    walk(pinned, folder = null)
    tabs.forEach { if (it.quick == null) parents[it.id] = false to null }
    val where = split.tabIds.map { parents[it] }
    return null !in where && where.distinct().size == 1
}

/**
 * Open tabs in Zen's order ([synced]). The tabs Zen's order doesn't cover keep their place on the phone, just after
 * the tab they followed there: the phone's own open tabs (Quick Tabs, tabs not uploaded yet), and Zen's tabs of
 * this space that no children list ([leftOut]). Synced tabs Zen no longer has are gone, and so is the phone's own
 * copy of a tab Zen has anywhere ([zenTabs]).
 */
private fun withPhoneOnlyTabs(synced: List<Tab>, leftOut: List<Tab>, local: List<Tab>, zenTabs: Set<String>): List<Tab> {
    val result = synced.toMutableList()
    val syncedIds = synced.mapTo(mutableSetOf()) { it.id }
    val unplaced = leftOut.associateByTo(LinkedHashMap()) { it.id }
    var previous: String? = null
    for (tab in local) {
        val kept = when {
            tab.id in syncedIds -> {
                previous = tab.id
                continue
            }
            tab.id in unplaced -> unplaced.remove(tab.id) ?: continue
            // A phone tab Zen now has (just uploaded) is the synced one from here on; never keep both.
            tab.synced || tab.id in zenTabs -> continue
            else -> tab
        }
        val at = previous?.let { id -> result.indexOfFirst { it.id == id } + 1 } ?: 0
        result.add(at, kept)
        previous = kept.id
    }
    // Ones this phone never had come last.
    return result + unplaced.values
}

/** A space's accent: the primary color of its Zen theme gradient, as ARGB. */
private fun accentOf(theme: JsonElement?): Long? {
    val colors = gradientColorsOf(theme)
    val color = colors.firstOrNull { it.bool("isPrimary") == true } ?: colors.firstOrNull() ?: return null
    return argbOf(color)
}

/** How strongly the Zen theme lays its gradient over the chrome. */
private fun opacityOf(theme: JsonElement?): Float =
    ((theme as? JsonObject)?.get("opacity") as? JsonPrimitive)?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f) ?: DEFAULT_THEME_OPACITY

/** All the colors of a space's Zen theme gradient, as ARGB, in Zen's order. */
private fun gradientOf(theme: JsonElement?): List<Long> = gradientColorsOf(theme).mapNotNull(::argbOf)

private fun gradientColorsOf(theme: JsonElement?): List<JsonObject> =
    ((theme as? JsonObject)?.get("gradientColors") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** A gradient color's `c` (red, green, blue) as opaque ARGB. */
private fun argbOf(color: JsonObject): Long? {
    val rgb = (color["c"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt()?.coerceIn(0, 255) }
    if (rgb == null || rgb.size < 3) return null
    return 0xFF000000L or (rgb[0].toLong() shl 16) or (rgb[1].toLong() shl 8) or rgb[2].toLong()
}

internal val ZenRecord.uuid: String get() = data.string("uuid") ?: id

private const val DEFAULT_ACCENT = 0xFF4F7CFF
