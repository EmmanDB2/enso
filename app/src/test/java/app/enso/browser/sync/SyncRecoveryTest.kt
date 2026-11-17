package app.enso.browser.sync

import app.enso.browser.spaces.pin
import app.enso.browser.spaces.removeTab
import app.enso.browser.spaces.renameFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Syncs that go wrong partway, or start from files that don't fit: nothing of Zen's may be lost over them. */
class SyncRecoveryTest {
    private val zen = FakeSyncServer()

    private fun phoneWithTabs(): Phone {
        zen.zenUploads(
            layoutRecord(),
            spaceRecord(listOf("a", "f", "c")),
            tabRecord("a"),
            folderRecord("f", listOf("b")),
            tabRecord("b", folder = "f"),
            tabRecord("c", pinned = false),
        )
        return syncedPhone(zen)
    }

    @Test
    fun `Zen uploading during the phone's sync makes the phone sync again`() {
        val phone = phoneWithTabs()

        phone.model.edit { it.removeTab("c") }
        // Zen gets a new tab in after the phone has downloaded and before it uploads.
        zen.beforeNextUpload = { zen.zenUploads(spaceRecord(listOf("a", "f", "n", "c")), tabRecord("n")) }
        try {
            phone.sync()
            fail("The server should have refused the upload")
        } catch (_: SyncConflictException) {
            // ZenSync syncs again a few seconds later.
        }
        assertEquals("The refused upload stored nothing", emptyList<List<String>>(), zen.phoneUploads)

        phone.syncUntilSettled()
        assertNotNull(zen.record("n"))
        assertTrue(zen.isDeleted("c"))
        assertEquals(listOf("a", "f", "n"), zen.record(SPACE)?.data?.strings("children"))
        assertEquals(listOf("a", "f", "n"), phone.pinnedIds)
        assertEquals(emptyList<String>(), phone.openIds)
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `an upload whose answer is lost is not sent twice or taken back`() {
        val phone = phoneWithTabs()

        phone.model.edit { it.renameFolder("f", "Reading").pin("c") }
        zen.loseNextUploadAnswer = true
        try {
            phone.sync()
            fail("The connection should have dropped")
        } catch (_: IOException) {
            // The upload went through, and the phone doesn't know.
        }
        val uploadsSoFar = zen.phoneUploads.size

        phone.syncUntilSettled()
        assertEquals("What already went up isn't uploaded again", uploadsSoFar, zen.phoneUploads.size)
        assertEquals("Reading", zen.record("f")?.data?.string("name"))
        assertEquals(true, zen.record("c")?.data?.bool("pinned"))
        assertEquals("Reading", phone.folder("f").name)
        assertEquals(listOf("a", "f", "c"), phone.pinnedIds)
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `a missing mirror uploads nothing`() {
        val phone = phoneWithTabs()

        // Signed out and in again, or the file was lost: the model stays, the mirror is gone. Meanwhile the phone
        // closed a tab and Zen added one.
        phone.mirrors.clear()
        phone.model.edit { it.removeTab("a") }
        zen.zenUploads(spaceRecord(listOf("a", "f", "n", "c")), tabRecord("n"))
        phone.syncUntilSettled()

        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertEquals("Zen's state wins", listOf("a", "f", "n"), phone.pinnedIds)
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `a collection Zen set up again uploads nothing`() {
        val phone = phoneWithTabs()

        // Zen resets the collection: a new sync id, and its records uploaded afresh, without B.
        zen.syncId = "another-sync-id"
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "f", "c")), tabRecord("a"), folderRecord("f", emptyList()), tabRecord("c", pinned = false), deleted = listOf("b"))
        phone.model.edit { it.removeTab("a") }
        phone.syncUntilSettled()

        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertEquals(listOf("a", "f"), phone.pinnedIds)
        assertEquals(emptyList<String>(), phone.folder("f").items.map { it.id })
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `the app killed before the mirror is saved downloads everything again`() {
        val phone = phoneWithTabs()

        zen.zenUploads(spaceRecord(listOf("a", "f", "n", "c")), tabRecord("n"))
        // The model's file is written, and the app dies before the mirror's is.
        val mirrorBefore = phone.mirrors.load()
        phone.sync()
        phone.mirrors.save(mirrorBefore)

        val restarted = phone.restarted()
        restarted.syncUntilSettled()
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertNotNull(zen.record("n"))
        assertEquals(listOf("a", "f", "n"), restarted.pinnedIds)
        restarted.assertModelReflectsMirror()
    }
}
