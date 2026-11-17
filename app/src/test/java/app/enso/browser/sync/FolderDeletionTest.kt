package app.enso.browser.sync

import app.enso.browser.spaces.deleteFolder
import app.enso.browser.spaces.moveToNewFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zen deletes the tabs still inside a folder when the folder's deletion arrives. So the phone deletes a folder in two
 * steps: it empties it first, and only deletes it once the server shows nothing in it.
 */
class FolderDeletionTest {
    private val zen = FakeSyncServer()

    private fun phoneWithFolder(): Phone {
        zen.zenUploads(
            layoutRecord(),
            spaceRecord(listOf("f")),
            folderRecord("f", listOf("a", "b")),
            tabRecord("a", folder = "f"),
            tabRecord("b", folder = "f"),
        )
        return syncedPhone(zen)
    }

    /** Whether the server ever held the folder's deletion while one of its records still said it was in the folder. */
    private fun watchForTabsLeftInDeletedFolder(): () -> Boolean {
        var seen = false
        zen.afterEachUpload = {
            if (zen.isDeleted("f") && zen.records().values.any { it.data.string("folderId") == "f" }) seen = true
        }
        return { seen }
    }

    @Test
    fun `deleting a folder empties it first and deletes it in a later sync`() {
        val phone = phoneWithFolder()

        phone.model.edit { it.deleteFolder("f") }
        assertEquals("Its tabs stay pinned where it was", listOf("a", "b"), phone.pinnedIds)
        phone.sync()

        assertFalse("The folder isn't deleted in the sync that empties it", zen.isDeleted("f"))
        assertEquals(emptyList<String>(), zen.record("f")?.data?.strings("children"))
        assertNull(zen.record("a")?.data?.string("folderId"))
        assertNull(zen.record("b")?.data?.string("folderId"))

        phone.syncUntilSettled()
        assertTrue("The emptied folder is deleted afterwards", zen.isDeleted("f"))
        assertNotNull(zen.record("a"))
        assertNotNull(zen.record("b"))
        assertEquals(listOf("a", "b"), zen.record(SPACE)?.data?.strings("children"))
        assertEquals(listOf("a", "b"), phone.pinnedIds)
    }

    @Test
    fun `a folder is not deleted while Zen still has one of its tabs in it`() {
        val phone = phoneWithFolder()
        val tabsLeftInDeletedFolder = watchForTabsLeftInDeletedFolder()

        // On the desktop B gets a new name. The phone hasn't heard when its folder is deleted there.
        zen.zenUploads(tabRecord("b", folder = "f", title = "Renamed"))
        phone.model.edit { it.deleteFolder("f") }
        phone.syncUntilSettled()

        assertFalse("The folder was deleted while Zen still had a tab in it", tabsLeftInDeletedFolder())
        assertTrue(zen.isDeleted("f"))
        assertNotNull(zen.record("a"))
        assertEquals("Renamed", zen.record("b")?.data?.string("title"))
        assertNull(zen.record("b")?.data?.string("folderId"))
        assertEquals(listOf("a", "b"), phone.pinnedIds)
    }

    @Test
    fun `a folder Zen put a tab into comes back`() {
        val phone = phoneWithFolder()

        phone.model.edit { it.deleteFolder("f") }
        phone.sync()
        // Zen had a tab in the folder that it hadn't uploaded yet. It takes the phone's changes and sends its own.
        zen.zenUploads(spaceRecord(listOf("a", "b", "f")), folderRecord("f", listOf("n")), tabRecord("n", folder = "f"))
        phone.syncUntilSettled()

        assertFalse(zen.isDeleted("f"))
        assertNotNull(zen.record("n"))
        assertEquals(listOf("n"), zen.record("f")?.data?.strings("children"))
        assertEquals(listOf("a", "b", "f"), phone.pinnedIds)
        assertEquals(listOf("n"), phone.folder("f").items.map { it.id })
    }

    @Test
    fun `a folder made and deleted on the phone before it synced leaves nothing behind`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        val phone = syncedPhone(zen)

        phone.model.edit { it.moveToNewFolder("a", "Later") }
        val made = phone.space.folders.single().id
        phone.model.edit { it.deleteFolder(made) }
        phone.syncUntilSettled()

        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertNull(zen.record(made))
        assertEquals(listOf("a"), phone.pinnedIds)
    }

    // A record the phone can't read can say it's in any folder. The fuzz test found this: after the mirror started
    // over, the phone had no copy of the tab at all, saw an empty folder, and deleted it with the tab inside.
    @Test
    fun `a folder isn't deleted while a record the phone can't read could be in it`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("f")), folderRecord("f", emptyList()))
        zen.zenUploadsUnreadable(tabRecord("p", folder = "f"))
        val phone = syncedPhone(zen)
        assertEquals("The phone sees an empty folder", emptyList<String>(), phone.folder("f").tabs.map { it.id })

        phone.model.edit { it.deleteFolder("f") }
        phone.syncUntilSettled()
        assertNotNull("The folder stays in Zen while the record can't be read", zen.record("f"))

        // Once it reads, it says it's in the folder. The folder's deletion would take it along.
        zen.becomesReadable("p")
        phone.syncUntilSettled()
        assertNotNull(zen.record("f"))
        assertNotNull(zen.record("p"))
        assertEquals("Only the space went up, without the folder", listOf(listOf(SPACE)), zen.phoneUploads)
    }
}
