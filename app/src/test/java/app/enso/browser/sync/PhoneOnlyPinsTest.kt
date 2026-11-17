package app.enso.browser.sync

import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import app.enso.browser.spaces.addToEssentials
import app.enso.browser.spaces.moveToFolder
import app.enso.browser.spaces.moveToNewFolder
import app.enso.browser.spaces.pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zen decides the pinned tabs, folders and Essentials. What the phone pinned, filed or made an Essential that Zen
 * hasn't got yet must still survive Zen's state being applied, as the phone's own open tabs do, and then go up.
 */
class PhoneOnlyPinsTest {
    private val zen = FakeSyncServer()

    private fun own(id: String) = Tab(id, "https://example.com/$id")

    @Test
    fun `what was pinned before signing in goes to Zen with the first syncs`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        // A new phone, used before signing in: a pinned tab, a folder with a tab, an Essential and an open tab, all
        // in the "Home" space it starts with.
        val phone = Phone(zen)
        phone.model.edit { state ->
            state.addOpenTab(own("t1"), afterId = null).pin("t1")
                .addOpenTab(own("t2"), afterId = null).moveToNewFolder("t2", "Mine")
                .addOpenTab(own("t3"), afterId = null).addToEssentials("t3")
                .addOpenTab(own("t4"), afterId = null)
        }
        val folder = phone.model.state.spaces.single().folders.single().id

        phone.syncUntilSettled()

        assertEquals("Zen's pinned tab, then the phone's own", listOf("a", "t1", folder), phone.pinnedIds)
        assertEquals(listOf("t2"), phone.folder(folder).items.map { it.id })
        assertEquals("Mine", phone.folder(folder).name)
        assertEquals(listOf("t3"), phone.model.state.essentials.map { it.id })
        assertEquals(listOf("t4"), phone.openIds)
        // And Zen has them all.
        assertEquals(true, zen.record("t1")?.data?.bool("pinned"))
        assertEquals(listOf("t2"), zen.record(folder)?.data?.strings("children"))
        assertEquals(folder, zen.record("t2")?.data?.string("folderId"))
        assertEquals(true, zen.record("t3")?.data?.bool("essential"))
        assertEquals(listOf("a", "t1", folder, "t4"), zen.record(SPACE)?.data?.strings("children"))
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `a pin made just before the mirror is lost survives`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        val phone = syncedPhone(zen)

        phone.model.edit { it.addOpenTab(own("p"), afterId = null).pin("p") }
        phone.mirrors.clear()
        phone.syncUntilSettled()

        assertEquals(listOf("a", "p"), phone.pinnedIds)
        assertEquals(true, zen.record("p")?.data?.bool("pinned"))
        assertEquals(listOf("a", "p"), zen.record(SPACE)?.data?.strings("children"))
    }

    @Test
    fun `a folder made on the phone survives a sync that could not list it`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        val phone = syncedPhone(zen)

        // Zen changes the space's record just before the phone's edit goes up, so the space waits and the rest goes.
        zen.zenUploads(spaceRecord(listOf("a", "n")), tabRecord("n"))
        phone.model.edit { it.addOpenTab(own("p"), afterId = null).moveToNewFolder("p", "Mine") }
        val folder = phone.space.folders.single().id
        phone.syncUntilSettled()

        assertEquals(listOf("a", "n", folder), phone.pinnedIds)
        assertEquals(listOf("p"), phone.folder(folder).items.map { it.id })
        assertEquals(listOf("a", "n", folder), zen.record(SPACE)?.data?.strings("children"))
        assertEquals(listOf("p"), zen.record(folder)?.data?.strings("children"))
    }

    @Test
    fun `the phone's own tab in a folder Zen deleted moves out of the folder`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("f")), folderRecord("f", listOf("a")), tabRecord("a", folder = "f"))
        val phone = syncedPhone(zen)

        // The phone files a tab of its own in the folder, and Zen deletes the folder, with its tab, before that syncs.
        phone.model.edit { it.addOpenTab(own("p"), afterId = null).moveToFolder("p", "f") }
        zen.zenUploads(spaceRecord(emptyList()), deleted = listOf("f", "a"))
        phone.syncUntilSettled()

        assertEquals("The folder is gone, and the phone's tab is pinned on its own", listOf("p"), phone.pinnedIds)
        assertTrue("The folder Zen deleted isn't brought back", zen.isDeleted("f"))
        assertNull(zen.record("p")?.data?.string("folderId"))
        assertEquals(listOf("p"), zen.record(SPACE)?.data?.strings("children"))
    }

    @Test
    fun `a pinned tab Zen deleted is not kept`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b")), tabRecord("a"), tabRecord("b"))
        val phone = syncedPhone(zen)

        zen.zenUploads(spaceRecord(listOf("a")), deleted = listOf("b"))
        phone.syncUntilSettled()

        assertEquals(listOf("a"), phone.pinnedIds)
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
    }
}
