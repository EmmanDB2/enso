package app.enso.browser.sync

import app.enso.browser.spaces.Folder
import app.enso.browser.spaces.PinnedTab
import app.enso.browser.spaces.SpacesState
import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import app.enso.browser.spaces.addToEssentials
import app.enso.browser.spaces.deleteFolder
import app.enso.browser.spaces.moveToFolder
import app.enso.browser.spaces.pin
import app.enso.browser.spaces.removeFromFolder
import app.enso.browser.spaces.removeTab
import app.enso.browser.spaces.renameFolder
import app.enso.browser.spaces.unpin
import app.enso.browser.spaces.updateSpace
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * Random edits on the phone and in Zen, with syncs in between that are interrupted in every way the app can be: an
 * edit mid-sync, a dry run, Zen uploading first, a lost answer, the app killed, spaces.json put back from a backup.
 * Zen also lists tabs it sends no record for, and uploads records the phone can't read. Whatever the order, the
 * phone must only delete from Zen what its user removed, must never bring back what was deleted, must leave alone
 * what it can't show or read, and must end up agreeing with Zen.
 */
class SyncFuzzTest {
    @Test
    fun `random edits and interrupted syncs never lose what Zen has`() {
        for (seed in 0 until RUNS) Scenario(seed).run()
    }

    private class Killed : RuntimeException()

    private class Scenario(private val seed: Int) {
        private val random = Random(seed)

        /** Whether this scenario's Zen syncs normal tabs (`zen.spaces-sync.normal-tabs`). It's off by default in Zen. */
        private val normalTabs = seed % 2 == 0
        private val zen = FakeSyncServer()
        private var phone = Phone(zen)
        private var nextId = 0
        private val log = mutableListOf<String>()

        /** What the phone's user removed by hand: the only ids the phone may delete from Zen. */
        private val removedOnPhone = mutableSetOf<String>()

        /** Every id anyone has deleted from the server. */
        private val deleted = mutableSetOf<String>()
        private val backups = mutableListOf<SpacesState>()

        /** Tabs Zen lists in the space without a record, because they have no page yet. */
        private val blank = mutableSetOf<String>()

        fun run() {
            zen.zenUploads(
                layoutRecord(),
                spaceRecord(listOf("p1", "p2", "f1", "o1", "o2")),
                tabRecord("p1"),
                tabRecord("p2"),
                folderRecord("f1", listOf("p3")),
                tabRecord("p3", folder = "f1"),
                tabRecord("o1", pinned = false),
                tabRecord("o2", pinned = false),
            )
            zen.afterEachUpload = ::checkUpload
            watch(phone)
            phone.syncUntilSettled()

            repeat(STEPS) { step() }

            // Everything interrupted is over: the two sync until neither has anything left to say.
            zen.duringNextDownload = null
            zen.beforeNextUpload = null
            zen.loseNextUploadAnswer = false
            phone.mirrors.afterNextSave = null
            zen.unreadableIds().forEach(zen::becomesReadable)
            log += "the end: everything can be read, and both sync until they agree"
            var rounds = 0
            do {
                val uploadsBefore = zen.phoneUploads.size
                settle()
                val phoneSpoke = zen.phoneUploads.size != uploadsBefore
                val zenSpoke = zenTakesIn()
                check(++rounds < MAX_ROUNDS_TO_AGREE) { "The phone and Zen keep trading changes" }
            } while (phoneSpoke || zenSpoke)
            val uploads = zen.phoneUploads.size
            settle()
            check(zen.phoneUploads.size == uploads) { "A settled phone still uploads" }
            check(!zenTakesIn()) { "Zen still has something to say to a settled phone" }
            phone.assertModelReflectsMirror()

            // The phone shows what a phone starting from the server would, bar folders whose deletion still waits.
            val fresh = SpacesState.initial().withZenSpaces(ZenMirror(records = zen.records()))
            val waiting = phone.model.state.deletedFolders
            check(outline(fresh, waiting) == outline(phone.model.state, waiting)) {
                "The phone and Zen disagree:\n  phone ${outline(phone.model.state, waiting)}\n  zen   ${outline(fresh, waiting)}"
            }
        }

        /**
         * Zen syncs, and takes in what the phone sent. With normal tabs it has the phone's open tabs, so its space
         * lists any the space's children lacked. Without, it has none of them, and its space lists none. Returns
         * whether it uploaded anything.
         */
        private fun zenTakesIn(): Boolean {
            if (!normalTabs) return zen.zenWithoutNormalTabsSyncs()
            val records = zen.records()
            val listed = children(SPACE)
            val unlisted = records.values.filter {
                it.kind == KIND_TAB && it.id !in listed && it.data.bool("pinned") == false && it.data.string("workspaceUuid") == SPACE
            }.map { it.id }.sorted()
            if (unlisted.isEmpty()) return false
            zen.zenUploads(withChildren(SPACE, listed + unlisted))
            return true
        }

        private fun check(ok: Boolean, problem: () -> String) {
            if (!ok) throw AssertionError("Seed $seed: ${problem()}\n" + log.joinToString("\n") { "  $it" })
        }

        /** Tabs and folders the phone holds, or held when a sync started, without their being marked as synced. */
        private val ownTabs = mutableSetOf<String>()
        private val ownFolders = mutableSetOf<String>()

        private fun watch(phone: Phone) {
            noteOwnTabs(phone.model.state)
            phone.model.onChange = { before, after, byEdit ->
                if (byEdit) removedOnPhone += ids(before) - ids(after) else checkApplied(before, after)
                noteOwnTabs(after)
            }
        }

        /** After Zen's state went over the model: did the phone lose a tab it shouldn't have? */
        private fun checkApplied(before: SpacesState, after: SpacesState) {
            val left = ids(after)
            for (tab in before.essentials + before.spaces.flatMap { it.allTabs }) {
                if (tab.id in left) continue
                check(tab.synced) { "A sync took the phone's own tab ${tab.id} away, which Zen never had" }
                val record = zen.record(tab.id)
                check(record == null || record.data.bool("pinned") != false) {
                    "A sync took ${tab.id} off the phone, which the server still has as an open tab"
                }
            }
        }

        private fun noteOwnTabs(state: SpacesState) {
            for (tab in state.essentials + state.spaces.flatMap { it.allTabs }) {
                if (tab.synced) ownTabs -= tab.id else ownTabs += tab.id
            }
            for (folder in state.spaces.flatMap { it.folders }) {
                if (folder.synced) ownFolders -= folder.id else ownFolders += folder.id
            }
        }

        private fun ids(state: SpacesState): Set<String> =
            (state.essentials.map { it.id } + state.spaces.flatMap { space -> space.allTabs.map { it.id } + space.folders.map { it.id } }).toSet()

        /** A space's pinned items (folders with what's in them), open tabs and the Essentials, by id. */
        private fun outline(state: SpacesState, hidden: Set<String>): String {
            fun items(list: List<app.enso.browser.spaces.PinnedItem>): String = list.filterNot { it.id in hidden }.joinToString(",") {
                if (it is Folder) "${it.id}[${items(it.items)}]" else it.id
            }
            val space = state.spaces.single { it.id == SPACE }
            // Open tabs no children list have no order of Zen's: each phone keeps its own.
            val listed = children(SPACE)
            val (ordered, unordered) = space.tabs.map { it.id }.partition { it in listed }
            return "pinned ${items(space.pinned)} open $ordered and ${unordered.sorted()} essentials ${state.essentials.map { it.id }}"
        }

        /** After each of the phone's uploads: what did it just do to the server? */
        private fun checkUpload() {
            val records = zen.records()
            check(zen.overwrittenUnreadable.isEmpty()) { "The phone wrote over ${zen.overwrittenUnreadable}, whose newer version it couldn't read" }
            if (SPACE in zen.phoneUploads.last()) {
                check(children(SPACE).containsAll(blank)) { "The phone wrote ${blank - children(SPACE).toSet()} out of the space, which it can't show" }
            }
            for (id in zen.phoneUploads.last()) {
                val record = records[id]
                if (record == null) {
                    check(id in removedOnPhone) { "The phone deleted $id, which its user never removed" }
                    check(records.values.none { it.data.string("folderId") == id || it.data.string("parentFolderId") == id }) {
                        "The phone deleted folder $id while a record on the server was still in it"
                    }
                    deleted += id
                } else {
                    // The phone sends what it counts as its own: a tab or folder it holds that isn't marked as
                    // synced, or a folder no mirror of its has ever held. Two ways that brings back something
                    // deleted, both known:
                    // - Zen deleted a tab or folder the phone had just made, before the phone knew its upload had
                    //   arrived (the answer was lost) or had taken a sync in (an edit during each sync since, so
                    //   the item was never marked as synced). Zen would have to be seconds behind it.
                    // - spaces.json was put back from a backup in which the tab was still the phone's own. A
                    //   restore brings the backup's own tabs back, on the phone and then in Zen.
                    if (id in deleted) {
                        check(id in ownTabs || id in ownFolders || id !in phone.mirrors.everHeld) { "The phone brought back $id, which was deleted" }
                        deleted -= id
                    }
                    val children = record.data.strings("children").orEmpty()
                    check(children.size == children.toSet().size) { "The phone listed an id twice in $id: $children" }
                }
            }
        }

        private fun settle() {
            try {
                phone.syncUntilSettled()
            } catch (_: SyncConflictException) {
                phone.syncUntilSettled()
            }
        }

        private fun step() {
            when (random.nextInt(20)) {
                in 0..6 -> phoneEdit()
                in 7..11 -> zenChange()
                12, 13 -> act("sync until settled") { settle() }
                14 -> act("one sync") { phone.sync() }
                15 -> act("sync with an edit during it") {
                    zen.duringNextDownload = { phoneEdit() }
                    phone.sync()
                    zen.duringNextDownload = null
                }
                16 -> act("dry run") { phone.syncUntilSettled(dryRun = true) }
                17 -> act("sync with Zen uploading first, or a lost answer") {
                    if (random.nextBoolean()) zen.beforeNextUpload = { zenChange() } else zen.loseNextUploadAnswer = true
                    phone.sync()
                }
                18 -> act("killed as the mirror is saved, then restarted") {
                    phone.mirrors.afterNextSave = { throw Killed() }
                    try {
                        phone.sync()
                    } finally {
                        phone.mirrors.afterNextSave = null
                        phone = phone.restarted().also(::watch)
                    }
                }
                else -> if (backups.isNotEmpty() && random.nextBoolean()) {
                    act("spaces.json put back from a backup") {
                        phone = Phone(zen, phone.mirrors, PhoneModel(backups.random(random))).also(::watch)
                    }
                } else {
                    act("backup") { backups += phone.model.saved }
                }
            }
        }

        /** Runs a sync step. A sync that ends in a conflict, a lost connection or a kill is what the step is for. */
        private fun act(what: String, action: () -> Unit) {
            log += what
            try {
                action()
            } catch (_: SyncConflictException) {
                log += "  (conflict)"
            } catch (_: IOException) {
                log += "  (connection lost)"
            } catch (_: Killed) {
                log += "  (killed)"
            } finally {
                zen.beforeNextUpload = null
                zen.loseNextUploadAnswer = false
            }
        }

        private fun newId(prefix: String) = "$prefix${nextId++}"

        private fun <T> List<T>.pick(): T? = if (isEmpty()) null else this[random.nextInt(size)]

        private fun phoneEdit() {
            val space = phone.space
            val pinnedTabs = space.pinned.flatMap { it.tabs }
            val edit: Pair<String, (SpacesState) -> SpacesState>? = when (random.nextInt(11)) {
                0, 1 -> (space.allTabs + phone.model.state.essentials).pick()?.let { tab -> "close ${tab.id}" to { it.removeTab(tab.id) } }
                2 -> space.tabs.pick()?.let { tab -> "pin ${tab.id}" to { it.pin(tab.id) } }
                3 -> pinnedTabs.pick()?.let { tab -> "unpin ${tab.id}" to { it.unpin(tab.id) } }
                4 -> newId("k").let { id -> "keep new tab $id" to { it.addOpenTab(Tab(id, "https://example.com/$id"), afterId = null) } }
                5 -> space.pinned.filterIsInstance<PinnedTab>().pick()?.let { item ->
                    val folder = newId("pf")
                    "move ${item.id} to new folder $folder" to { state ->
                        state.removeTab(item.id).updateSpace(SPACE) { it.copy(pinned = it.pinned + Folder(folder, "New", items = listOf(item))) }
                    }
                }
                6 -> space.folders.pick()?.let { folder -> pinnedTabs.pick()?.let { tab -> "move ${tab.id} to folder ${folder.id}" to { it.moveToFolder(tab.id, folder.id) } } }
                7 -> space.folders.flatMap { it.items }.filterIsInstance<PinnedTab>().pick()?.let { item -> "take ${item.id} out of its folder" to { it.removeFromFolder(item.id) } }
                8 -> space.folders.pick()?.let { folder -> "rename folder ${folder.id}" to { it.renameFolder(folder.id, newId("name")) } }
                9 -> space.folders.pick()?.let { folder -> "delete folder ${folder.id}" to { it.deleteFolder(folder.id) } }
                else -> if (random.nextBoolean()) {
                    space.allTabs.pick()?.let { tab -> "make ${tab.id} an Essential" to { it.addToEssentials(tab.id) } }
                } else {
                    phone.model.state.essentials.pick()?.let { tab -> "unpin Essential ${tab.id}" to { it.unpin(tab.id) } }
                }
            }
            if (edit != null) {
                log += "phone: ${edit.first}"
                phone.model.edit(edit.second)
            }
        }

        // Zen's side. Zen is taken to be in sync: it changes what the server holds, in one upload, as its own
        // projection would (an item's record and its parent's children together).

        private fun children(id: String) = zen.record(id)?.data?.strings("children").orEmpty()

        private fun withChildren(id: String, children: List<String>) = zen.record(id)!!.let { ZenRecord(it.id, it.kind, it.data.with("children" to children)) }

        private fun isTab(id: String, pinned: Boolean) =
            zen.record(id)?.let { it.kind == KIND_TAB && (it.data.bool("pinned") != false) == pinned && it.data.bool("essential") != true } == true

        /** Where a new pinned item goes among the space's children: before the first open tab. */
        private fun pinnedEnd(children: List<String>) = children.indexOfFirst { isTab(it, pinned = false) }.let { if (it < 0) children.size else it }

        private fun listedFolders() = children(SPACE).filter { zen.record(it)?.kind == KIND_FOLDER }

        private fun zenChange() {
            // Zen syncs before it uploads a change of its own, so it has taken the phone's records in by then.
            if (zenTakesIn()) log += "zen: takes in what the phone sent"
            val top = children(SPACE)
            // Without normal-tab sync, Zen's own open tabs never reach the server.
            when (random.nextInt(11).let { if (it == 1 && !normalTabs) 0 else it }) {
                0 -> newId("z").let { id ->
                    log += "zen: new pinned tab $id"
                    zen.zenUploads(withChildren(SPACE, top.toMutableList().apply { add(pinnedEnd(top), id) }), tabRecord(id))
                }
                1 -> newId("z").let { id ->
                    log += "zen: new open tab $id"
                    zen.zenUploads(withChildren(SPACE, top + id), tabRecord(id, pinned = false))
                }
                2, 3 -> (top.filter { zen.record(it)?.kind == KIND_TAB } + listedFolders().flatMap(::children)).pick()?.let { id ->
                    log += "zen: close tab $id"
                    val parents = (listOf(SPACE) + listedFolders()).filter { id in children(it) }.map { withChildren(it, children(it) - id) }
                    zen.zenUploads(*parents.toTypedArray(), deleted = listOf(id))
                    deleted += id
                }
                4 -> {
                    val folder = newId("zf")
                    val tab = newId("z")
                    log += "zen: new folder $folder with tab $tab"
                    zen.zenUploads(
                        withChildren(SPACE, top.toMutableList().apply { add(pinnedEnd(top), folder) }),
                        folderRecord(folder, listOf(tab)),
                        tabRecord(tab, folder = folder),
                    )
                }
                5 -> listedFolders().pick()?.let { folder ->
                    log += "zen: rename folder $folder"
                    zen.zenUploads(zen.record(folder)!!.let { ZenRecord(it.id, it.kind, it.data.with("name" to newId("zen name"))) })
                }
                6 -> listedFolders().pick()?.let { folder ->
                    top.filter { isTab(it, pinned = true) }.pick()?.let { tab ->
                        log += "zen: move $tab into folder $folder"
                        zen.zenUploads(
                            withChildren(SPACE, top - tab),
                            withChildren(folder, children(folder) + tab),
                            zen.record(tab)!!.let { ZenRecord(it.id, it.kind, it.data.with("folderId" to folder)) },
                        )
                    }
                }
                8 -> newId("b").let { id ->
                    log += "zen: new pinned tab $id with no page yet, so no record"
                    zen.zenUploads(withChildren(SPACE, top.toMutableList().apply { add(pinnedEnd(top), id) }))
                    blank += id
                }
                9 -> blank.sorted().pick()?.let { id ->
                    blank -= id
                    if (random.nextBoolean()) {
                        log += "zen: $id gets a page, and a record"
                        zen.zenUploads(tabRecord(id))
                    } else {
                        log += "zen: closes $id, which never had a record"
                        zen.zenUploads(withChildren(SPACE, top - id))
                    }
                }
                10 -> if (zen.unreadableIds().isNotEmpty() && random.nextBoolean()) {
                    zen.unreadableIds().pick()?.let { id ->
                        log += "zen: $id can be read after all"
                        zen.becomesReadable(id)
                    }
                } else {
                    top.filter { isTab(it, pinned = true) }.pick()?.let { id ->
                        log += "zen: renames $id in a way the phone can't read"
                        zen.zenUploadsUnreadable(zen.record(id)!!.let { ZenRecord(it.id, it.kind, it.data.with("title" to newId("zen title"))) })
                    }
                }
                else -> listedFolders().pick()?.let { folder ->
                    // As in Zen, a folder goes with everything in it.
                    val inside = (children(folder) + zen.records().values.filter { it.data.string("folderId") == folder }.map { it.id }).distinct()
                    log += "zen: delete folder $folder with $inside"
                    zen.zenUploads(withChildren(SPACE, top - folder), deleted = listOf(folder) + inside)
                    deleted += folder
                    deleted += inside
                }
            }
        }
    }

    private companion object {
        /**
         * Scenarios per run of the suite. To hunt after a change to sync, set `ENSO_FUZZ_RUNS` (and pass `--rerun`,
         * since Gradle doesn't see it change): 30,000 take about six minutes.
         */
        val RUNS = System.getenv("ENSO_FUZZ_RUNS")?.toIntOrNull() ?: 300
        const val STEPS = 50
        const val MAX_ROUNDS_TO_AGREE = 10
    }
}
