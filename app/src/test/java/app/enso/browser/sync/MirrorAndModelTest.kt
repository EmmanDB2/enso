package app.enso.browser.sync

import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import app.enso.browser.spaces.addToEssentials
import app.enso.browser.spaces.pin
import app.enso.browser.spaces.removeTab
import app.enso.browser.spaces.renameFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The mirror (what sync last knew of Zen) must never get ahead of the phone's model. When it did, the next sync
 * compared an old model with a new mirror and uploaded the difference: it deleted what Zen had added and re-created
 * what Zen had deleted.
 */
class MirrorAndModelTest {
    private val zen = FakeSyncServer()

    @Test
    fun `an edit during a sync does not undo what Zen changed`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b", "c")), tabRecord("a"), tabRecord("b"), tabRecord("c", pinned = false))
        val phone = syncedPhone(zen)
        assertEquals(listOf("a", "b"), phone.pinnedIds)
        assertEquals(listOf("c"), phone.openIds)

        // On the desktop: a new folder with two new pinned tabs, and pinned tab B closed.
        zen.zenUploads(
            spaceRecord(listOf("a", "f", "c")),
            folderRecord("f", listOf("n1", "n2")),
            tabRecord("n1", folder = "f"),
            tabRecord("n2", folder = "f"),
            deleted = listOf("b"),
        )
        // The phone syncs, and while that sync runs the user closes C in the tab sheet.
        zen.duringNextDownload = { phone.model.edit { it.removeTab("c") } }
        phone.syncUntilSettled()

        // Zen keeps what it made, B stays closed, and the phone's own edit went through.
        assertNotNull("Zen's new folder is still there", zen.record("f"))
        assertNotNull("Zen's new tab is still there", zen.record("n1"))
        assertNotNull("Zen's new tab is still there", zen.record("n2"))
        assertTrue("The tab Zen closed stays closed", zen.isDeleted("b"))
        assertTrue("The tab the phone closed is closed in Zen", zen.isDeleted("c"))
        assertEquals(listOf("a", "f"), zen.record(SPACE)?.data?.strings("children"))
        // And the phone has taken Zen's changes in.
        assertEquals(listOf("a", "f"), phone.pinnedIds)
        assertEquals(listOf("n1", "n2"), phone.folder("f").items.map { it.id })
        assertEquals(emptyList<String>(), phone.openIds)
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `a sync the model did not take in leaves the mirror where the model is`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b")), tabRecord("a"), tabRecord("b"))
        val phone = syncedPhone(zen)
        val before = phone.mirrors.load()

        zen.zenUploads(spaceRecord(listOf("a", "b", "n")), tabRecord("n"))
        zen.duringNextDownload = { phone.model.edit { it.removeTab("b") } }
        val outcome = phone.sync()

        assertTrue("Another sync follows for the edit", outcome.again)
        assertEquals("The model hasn't got Zen's new tab yet", listOf("a"), phone.pinnedIds)
        assertEquals("So the mirror hasn't either", before.records, phone.mirrors.load().records)
        assertEquals(before.lastModified, phone.mirrors.load().lastModified)
        phone.model.settle()
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `a backup from before an upload the model did not follow uploads nothing`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b", "c")), tabRecord("a"), tabRecord("b"), tabRecord("c", pinned = false))
        val phone = syncedPhone(zen)
        val backup = phone.model.saved

        // C is closed and that goes up, but an edit during the sync keeps the model from taking the sync in: the
        // mirror now holds the phone's upload, at the server time it had before.
        phone.model.edit { it.removeTab("c") }
        zen.duringNextUpload = { phone.model.edit { it.removeTab("b") } }
        phone.sync()
        assertTrue(zen.isDeleted("c"))
        assertEquals(backup.zenApplied?.lastModified, phone.mirrors.load().lastModified)
        zen.phoneUploads.clear()

        // spaces.json is put back from the backup, which still has C.
        val restored = Phone(zen, phone.mirrors, PhoneModel(backup))
        restored.syncUntilSettled()

        assertEquals("A restored model has nothing to say to Zen", emptyList<List<String>>(), zen.phoneUploads)
        assertTrue("What the phone deleted stays deleted", zen.isDeleted("c"))
        assertNotNull(zen.record("b"))
        assertEquals(listOf("a", "b"), restored.pinnedIds)
        assertEquals(emptyList<String>(), restored.openIds)
        restored.assertModelReflectsMirror()
    }

    @Test
    fun `an install from before the stamp downloads everything and uploads nothing`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b")), tabRecord("a"), tabRecord("b"))
        val synced = syncedPhone(zen)

        // As an older build left it: a mirror, a model that doesn't say which mirror it reflects, and with them
        // possibly the very state this stamp is there to catch. Here the model lacks a tab the mirror has.
        val older = synced.model.state.copy(zenApplied = null).removeTab("b")
        val phone = Phone(zen, synced.mirrors, PhoneModel(older))
        phone.syncUntilSettled()

        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertNotNull(zen.record("b"))
        assertEquals("Zen's state wins, once", listOf("a", "b"), phone.pinnedIds)
        phone.assertModelReflectsMirror()

        // From there on, sync works as usual.
        phone.model.edit { it.removeTab("b") }
        phone.syncUntilSettled()
        assertTrue(zen.isDeleted("b"))
        assertEquals(listOf("a"), zen.record(SPACE)?.data?.strings("children"))
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `a dry run does not make the next real sync undo what Zen changed`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b")), tabRecord("a"), tabRecord("b"))
        val phone = syncedPhone(zen)

        // A test edit, which a dry run only logs: the phone closes A.
        phone.model.edit { it.removeTab("a") }
        // Meanwhile on the desktop: a new pinned tab N, and B closed.
        zen.zenUploads(spaceRecord(listOf("a", "n")), tabRecord("n"), deleted = listOf("b"))
        phone.syncUntilSettled(dryRun = true)
        assertEquals("A dry run uploads nothing", emptyList<List<String>>(), zen.phoneUploads)

        // Dry run is turned off.
        phone.syncUntilSettled()
        assertNotNull("Zen's new tab is still there", zen.record("n"))
        assertTrue("The tab Zen closed stays closed", zen.isDeleted("b"))
        assertTrue("The tab the phone closed is closed in Zen", zen.isDeleted("a"))
        assertEquals(listOf("n"), zen.record(SPACE)?.data?.strings("children"))
        assertEquals(listOf("n"), phone.pinnedIds)
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `a spaces file older than the mirror uploads nothing`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b")), tabRecord("a"), tabRecord("b"))
        val phone = syncedPhone(zen)
        val backup = phone.model.state

        zen.zenUploads(spaceRecord(listOf("a", "n")), tabRecord("n"), deleted = listOf("b"))
        phone.syncUntilSettled()
        assertEquals(listOf("a", "n"), phone.pinnedIds)

        // spaces.json is put back from the backup while the app is stopped. The mirror file stays as it is.
        val restored = Phone(zen, phone.mirrors, PhoneModel(backup))
        restored.syncUntilSettled()

        assertEquals("A restored model has nothing to say to Zen", emptyList<List<String>>(), zen.phoneUploads)
        assertNotNull(zen.record("n"))
        assertTrue(zen.isDeleted("b"))
        assertEquals(listOf("a", "n"), restored.pinnedIds)
    }

    @Test
    fun `a restored tab that Zen has since pinned shows once, in Zen's place`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        val phone = syncedPhone(zen)

        // A tab is kept on the phone. In the backup it's still the phone's own: Zen hasn't got it yet.
        phone.model.edit { it.addOpenTab(Tab("k", "https://example.com/k"), afterId = null) }
        val backup = phone.model.state
        phone.syncUntilSettled()
        // Then it's pinned, and made an Essential's neighbour: another kept tab becomes an Essential.
        phone.model.edit { it.addOpenTab(Tab("e", "https://example.com/e"), afterId = null) }
        val backupWithBoth = phone.model.state.let { state -> state.copy(spaces = state.spaces.map { space -> space.copy(tabs = space.tabs + backup.spaces.single { it.id == SPACE }.tabs.filter { it.id == "k" }.filterNot { k -> space.tabs.any { it.id == k.id } }) }) }
        phone.model.edit { it.pin("k").addToEssentials("e") }
        phone.syncUntilSettled()
        assertEquals(true, zen.record("k")?.data?.bool("pinned"))
        assertEquals(true, zen.record("e")?.data?.bool("essential"))
        zen.phoneUploads.clear()

        // spaces.json is put back from before: K and E are open tabs of the phone's own there.
        val older = backupWithBoth.copy(zenApplied = backup.zenApplied)
        val restored = Phone(zen, phone.mirrors, PhoneModel(older))
        assertEquals(setOf("k", "e"), restored.openIds.toSet())
        restored.syncUntilSettled()

        assertEquals("A restored model has nothing to say to Zen", emptyList<List<String>>(), zen.phoneUploads)
        assertEquals(listOf("a", "k"), restored.pinnedIds)
        assertEquals("Neither is still an open tab as well", emptyList<String>(), restored.openIds)
        assertEquals(listOf("e"), restored.model.state.essentials.map { it.id })
        assertEquals(true, zen.record("k")?.data?.bool("pinned"))
    }

    @Test
    fun `the app killed right after the mirror is saved does not undo what Zen changed`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b")), tabRecord("a"), tabRecord("b"))
        val phone = syncedPhone(zen)

        zen.zenUploads(spaceRecord(listOf("a", "n")), tabRecord("n"), deleted = listOf("b"))
        phone.mirrors.afterNextSave = { throw Killed() }
        try {
            phone.sync()
            fail("The app should have been killed as the mirror was saved")
        } catch (_: Killed) {
            // What follows is the next start.
        }

        val restarted = phone.restarted()
        restarted.syncUntilSettled()
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertNotNull(zen.record("n"))
        assertTrue(zen.isDeleted("b"))
        assertEquals(listOf("a", "n"), restarted.pinnedIds)
    }

    @Test
    fun `the phone's own upload is not taken for Zen's change`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("f")), folderRecord("f", listOf("a")), tabRecord("a", folder = "f"))
        val phone = syncedPhone(zen)

        // The folder is renamed, and renamed again while the first name is on its way up.
        phone.model.edit { it.renameFolder("f", "One") }
        zen.duringNextUpload = { phone.model.edit { it.renameFolder("f", "Two") } }
        phone.syncUntilSettled()

        assertEquals("Two", zen.record("f")?.data?.string("name"))
        assertEquals("Two", phone.folder("f").name)
    }

    @Test
    fun `a sync with nothing new uploads nothing`() {
        zen.zenUploads(
            layoutRecord(essentials = listOf("e")),
            spaceRecord(listOf("a", "f", "c")),
            tabRecord("a"),
            folderRecord("f", listOf("b")),
            tabRecord("b", folder = "f"),
            tabRecord("c", pinned = false),
            tabRecord("e", essential = true),
        )
        val phone = syncedPhone(zen)
        assertEquals(listOf("a", "f"), phone.pinnedIds)
        assertEquals(listOf("c"), phone.openIds)
        assertEquals(listOf("e"), phone.model.state.essentials.map { it.id })

        phone.syncUntilSettled()
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
    }

    private class Killed : RuntimeException()
}
