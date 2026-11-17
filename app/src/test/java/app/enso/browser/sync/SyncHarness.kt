package app.enso.browser.sync

import app.enso.browser.spaces.Folder
import app.enso.browser.spaces.Space
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.withValidSplits
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import kotlin.coroutines.EmptyCoroutineContext

// The phone's side of a sync for tests: the real engine, projector and mapper over an in-memory model and files.

/** The one space the tests' Zen has. */
internal const val SPACE = "{11111111-2222-3333-4444-555555555555}"

/** A space record as Zen writes it. */
internal fun spaceRecord(children: List<String>, uuid: String = SPACE): ZenRecord = ZenRecord(
    uuid,
    KIND_SPACE,
    JsonObject(emptyMap()).with("uuid" to uuid, "name" to "Work", "icon" to null, "theme" to null, "containerGuid" to null, "children" to children),
)

/** A tab record as Zen writes it: pinned unless [pinned] is false, and an Essential with [essential]. */
internal fun tabRecord(
    id: String,
    pinned: Boolean = true,
    folder: String? = null,
    essential: Boolean = false,
    title: String = "Page $id",
): ZenRecord = ZenRecord(
    id,
    KIND_TAB,
    JsonObject(emptyMap()).with(
        "tabId" to id,
        "url" to "https://example.com/$id",
        "title" to title,
        "icon" to "",
        "containerGuid" to null,
        "essential" to essential,
        "pinned" to pinned,
        "workspaceUuid" to (if (essential) null else SPACE),
        "folderId" to folder,
        "staticLabel" to null,
        "hasStaticIcon" to false,
        "defaultContainer" to false,
    ),
)

/** A folder record as Zen writes it. */
internal fun folderRecord(id: String, children: List<String>, name: String = "Folder $id"): ZenRecord = ZenRecord(
    id,
    KIND_FOLDER,
    JsonObject(emptyMap()).with(
        "folderId" to id,
        "name" to name,
        "icon" to null,
        "workspaceUuid" to SPACE,
        "parentFolderId" to null,
        "live" to null,
        "children" to children,
    ),
)

/** A split view record as Zen writes it, of open tabs unless [pinned]. */
internal fun splitRecord(id: String, tabs: List<String>, pinned: Boolean = false): ZenRecord = ZenRecord(
    id,
    KIND_SPLIT,
    JsonObject(emptyMap()).with(
        "splitId" to id,
        "gridType" to "vsep",
        "pinned" to pinned,
        "tabs" to tabs,
        "workspaceUuid" to SPACE,
        "folderId" to null,
    ),
)

/** The layout record as Zen writes it, with the Essentials outside any container. */
internal fun layoutRecord(essentials: List<String> = emptyList()): ZenRecord = ZenRecord(
    LAYOUT_ID,
    KIND_LAYOUT,
    JsonObject(emptyMap()).with(
        "spaces" to listOf(SPACE),
        "essentials" to (if (essentials.isEmpty()) emptyMap() else mapOf(DEFAULT_CONTAINER to essentials)),
    ),
)

/** The mirror file, in memory. */
internal class MemoryMirrorStore : MirrorStore {
    var saved: ZenMirror? = null

    /** Runs once, right after the next save: the app being killed at that moment, say. */
    var afterNextSave: (() -> Unit)? = null

    override fun load(): ZenMirror = saved ?: ZenMirror()

    /** Every record id a saved mirror has held: what the phone has known to be on the server at some point. */
    val everHeld = mutableSetOf<String>()

    override fun save(mirror: ZenMirror) {
        saved = mirror
        everHeld += mirror.records.keys
        afterNextSave?.also { afterNextSave = null }?.invoke()
    }

    override fun clear() {
        saved = null
    }
}

/** The snapshot files, in memory. */
internal class MemorySnapshotStore : SnapshotStore {
    /** The one from before the first write. */
    var snapshot: ZenSnapshot? = null

    /** The ones kept aside, by name, oldest first. */
    val kept = LinkedHashMap<String, ZenSnapshot>()

    override fun load(name: String?): ZenSnapshot? = if (name == null) snapshot else kept[name]

    override fun save(snapshot: ZenSnapshot) {
        this.snapshot = snapshot
    }

    override fun keep(name: String, snapshot: () -> ZenSnapshot): Boolean {
        if (name in kept) return false
        kept[name] = snapshot()
        return true
    }
}

/** The spaces model and its file (spaces.json), in memory. */
internal class PhoneModel(override var state: SpacesState = SpacesState.initial()) : SyncedModel {
    override var edits = 0

    /** What spaces.json holds. The app writes it half a second after a change; here [settle] does. */
    var saved: SpacesState = state

    /** Told of every change to the model, with the state before it. */
    var onChange: ((before: SpacesState, after: SpacesState, byEdit: Boolean) -> Unit)? = null

    override fun apply(transform: (SpacesState) -> SpacesState) = change(transform, byEdit = false)

    private fun change(transform: (SpacesState) -> SpacesState, byEdit: Boolean) {
        val before = state
        state = transform(state).withValidSplits()
        onChange?.invoke(before, state, byEdit)
    }

    override fun flush() {
        saved = state
    }

    /** An edit in the tab sheet: the model changes, and sync is told. */
    fun edit(transform: (SpacesState) -> SpacesState) {
        change(transform, byEdit = true)
        edits++
    }

    /** A change sync isn't told about, like a page moving on by itself. */
    fun change(transform: (SpacesState) -> SpacesState) = change(transform, byEdit = true)

    /** Time passes, and the model reaches its file. */
    fun settle() = flush()
}

/** A phone signed in to the account [server] holds. */
internal class Phone(
    private val server: FakeSyncServer,
    val mirrors: MemoryMirrorStore = MemoryMirrorStore(),
    val model: PhoneModel = PhoneModel(),
    val snapshots: MemorySnapshotStore = MemorySnapshotStore(),
    /** The phone's record for the account's Sync device list, when a test is about it. */
    private val device: SyncDevice? = null,
) {
    val engine = ZenSpacesEngine(server, mirrors, snapshots)
    private val round = ZenSyncRound(engine, model, EmptyCoroutineContext)

    /** One sync. */
    fun sync(dryRun: Boolean = false): SyncOutcome = runBlocking {
        round.run(server.auth, device, SyncOptions(syncOpenTabs = true, syncTabNavigations = false, dryRun = dryRun))
    }

    /** Syncs as the app does: again, a few seconds later, while a sync leaves something to send. */
    fun syncUntilSettled(dryRun: Boolean = false): SyncOutcome {
        repeat(MAX_ROUNDS) {
            val outcome = sync(dryRun)
            if (!outcome.again) return outcome.also { model.settle() }
        }
        error("Sync didn't settle in $MAX_ROUNDS rounds")
    }

    /** The phone as it comes back after the app was killed: the same files, and the model from its file. */
    fun restarted(): Phone = Phone(server, mirrors, PhoneModel(model.saved), snapshots, device)

    val space: Space get() = model.state.spaces.single { it.id == SPACE }

    /** The ids of the space's pinned tabs and folders, top to bottom. */
    val pinnedIds: List<String> get() = space.pinned.map { it.id }

    val openIds: List<String> get() = space.tabs.map { it.id }

    fun folder(id: String): Folder = space.folders.single { it.id == id }

    /** The rule behind the mirror: the model in its file says it reflects exactly the mirror in its file. */
    fun assertModelReflectsMirror() {
        val mirror = mirrors.load()
        assertEquals(mirror.stamp, model.saved.zenApplied)
    }

    private companion object {
        const val MAX_ROUNDS = 8
    }
}

/** A phone that has taken in what [zen] holds, with nothing left to send. */
internal fun syncedPhone(zen: FakeSyncServer): Phone {
    val phone = Phone(zen)
    phone.syncUntilSettled()
    phone.syncUntilSettled()
    assertEquals("A phone that only took Zen's state in has nothing to upload", emptyList<List<String>>(), zen.phoneUploads)
    phone.assertModelReflectsMirror()
    return phone
}

/**
 * Zen with its normal-tab sync off (its default) takes in what a phone sent: it leaves unpinned tabs' records alone,
 * and uploads each space again without them in `children`. Returns whether it had anything to upload.
 */
internal fun FakeSyncServer.zenWithoutNormalTabsSyncs(): Boolean {
    val records = records()
    val changed = records.values.filter { it.kind == KIND_SPACE }.mapNotNull { space ->
        val children = space.data.strings("children").orEmpty()
        val kept = children.filterNot { id -> records[id]?.let { it.kind == KIND_TAB && it.data.bool("pinned") == false } == true }
        if (kept == children) null else ZenRecord(space.id, space.kind, space.data.with("children" to kept))
    }
    if (changed.isNotEmpty()) zenUploads(*changed.toTypedArray())
    return changed.isNotEmpty()
}
