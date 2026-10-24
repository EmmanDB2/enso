package app.enso.browser.sync

import app.enso.browser.spaces.DEFAULT_THEME_OPACITY
import app.enso.browser.spaces.Folder
import app.enso.browser.spaces.PinnedItem
import app.enso.browser.spaces.PinnedTab
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Tab
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Applies Zen's spaces from [mirror] to the phone (read-only sync, Zen → phone).
 *
 * Zen decides the spaces, their order and looks, pinned tabs, folders and Essentials. The phone keeps its own
 * open tabs (Quick Tabs included), selection and folder open/closed states. Phone spaces Zen doesn't have, like
 * the starting "Home", go away; their phone-only open tabs move to the first Zen space.
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

    /** A space's or folder's children: pinned items, plus unpinned tabs (synced only when Zen's option is on). */
    fun childrenOf(ids: List<String>, seen: MutableSet<String>): Pair<List<PinnedItem>, List<Tab>> {
        val pinned = mutableListOf<PinnedItem>()
        val open = mutableListOf<Tab>()
        for (id in ids) {
            if (!seen.add(id)) continue
            val record = records[id] ?: continue // Not received yet; it shows once it arrives.
            when (record.kind) {
                KIND_TAB -> tabFrom(id)?.let { if (it.pinnedUrl != null) pinned += PinnedTab(it) else open += it }
                KIND_FOLDER -> {
                    val (items, folderOpen) = childrenOf(record.data.strings("children").orEmpty(), seen)
                    pinned += Folder(
                        id = id,
                        name = record.data.string("name").orEmpty(),
                        expanded = localFolders[id]?.expanded ?: false,
                        items = items,
                    )
                    open += folderOpen
                }
                // Split view arrives in step 8; until then a split's tabs show one after another.
                KIND_SPLIT -> record.data.strings("tabs").orEmpty().mapNotNull(::tabFrom).forEach {
                    if (it.pinnedUrl != null) pinned += PinnedTab(it) else open += it
                }
            }
        }
        return pinned to open
    }

    val seen = mutableSetOf<String>()
    val zenSpaces = ordered.map { record ->
        val uuid = record.uuid
        val local = spaces.firstOrNull { it.id == uuid }
        val (pinned, syncedOpen) = childrenOf(record.data.strings("children").orEmpty(), seen)
        Space(
            id = uuid,
            name = record.data.string("name").orEmpty(),
            icon = record.data.string("icon").orEmpty(),
            accent = accentOf(record.data["theme"]) ?: local?.accent ?: DEFAULT_ACCENT,
            gradient = gradientOf(record.data["theme"]),
            themeOpacity = opacityOf(record.data["theme"]),
            container = record.data.string("containerGuid"),
            pinned = pinned,
            tabs = withPhoneOnlyTabs(syncedOpen, local?.tabs.orEmpty()),
            selectedTabId = local?.selectedTabId,
        )
    }

    // Phone spaces Zen doesn't have: their phone-only open tabs move to the first Zen space.
    val zenIds = zenSpaces.mapTo(mutableSetOf()) { it.id }
    val orphans = spaces.filterNot { it.id in zenIds }
    val movedTabs = orphans.flatMap { space -> space.tabs.filterNot { it.synced } }
    val currentWasOrphan = currentSpaceId !in zenIds
    val first = zenSpaces.first().let { space ->
        val movedSelection = orphans.firstOrNull { it.id == currentSpaceId }?.selectedTabId
            ?.takeIf { id -> movedTabs.any { it.id == id } }
        space.copy(
            tabs = space.tabs + movedTabs,
            selectedTabId = if (currentWasOrphan) movedSelection ?: space.selectedTabId else space.selectedTabId,
        )
    }

    // Zen keeps Essentials per container: layout.essentials maps a container guid ("default" for none) to its tabs.
    val essentialGroups = (layout?.get("essentials") as? JsonObject)?.mapValues { it.value.strings() }
        ?: mapOf(DEFAULT_CONTAINER to records.values.filter { it.kind == KIND_TAB && it.data.bool("essential") == true }.map { it.id })
    val essentials = essentialGroups.flatMap { (container, ids) ->
        ids.mapNotNull(::tabFrom).map { it.copy(container = container.takeUnless { it == DEFAULT_CONTAINER }) }
    }.distinctBy { it.id }

    return copy(
        spaces = listOf(first) + zenSpaces.drop(1),
        essentials = essentials,
        currentSpaceId = if (currentWasOrphan) first.id else currentSpaceId,
    )
}

/**
 * Open tabs in Zen's order ([synced]), with the phone's own open tabs (Quick Tabs, tabs not uploaded yet) kept
 * just after the synced tab they followed on the phone. Synced tabs Zen no longer has are gone.
 */
private fun withPhoneOnlyTabs(synced: List<Tab>, local: List<Tab>): List<Tab> {
    val result = synced.toMutableList()
    val syncedIds = synced.mapTo(mutableSetOf()) { it.id }
    var previous: String? = null
    for (tab in local) {
        // A phone tab Zen now has (just uploaded) is the synced one from here on; never keep both.
        if (tab.synced || tab.id in syncedIds) {
            if (tab.id in syncedIds) previous = tab.id
            continue
        }
        val at = previous?.let { id -> result.indexOfFirst { it.id == id } + 1 } ?: 0
        result.add(at, tab)
        previous = tab.id
    }
    return result
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
