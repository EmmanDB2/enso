package app.enso.browser.sync

import app.enso.browser.spaces.QuickTab
import app.enso.browser.spaces.pin
import app.enso.browser.spaces.unpin
import app.enso.browser.spaces.updateTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zen's split view can't hold a Quick Tab, or a pinned tab with an open one. When the phone makes one of Zen's splits
 * into that, the split leaves Zen and stays on the phone, as the phone's own.
 */
class SplitSyncTest {
    private val zen = FakeSyncServer()

    /** A phone showing Zen's split view S of the open tabs X and Y. */
    private fun phoneWithZenSplit(): Phone {
        zen.zenUploads(
            layoutRecord(), spaceRecord(listOf("a", "s")), tabRecord("a"),
            tabRecord("x", pinned = false), tabRecord("y", pinned = false), splitRecord("s", listOf("x", "y")),
        )
        val phone = syncedPhone(zen)
        assertEquals(listOf(listOf("x", "y")), phone.space.splits.map { it.tabIds })
        assertTrue(phone.space.splits.single().synced)
        return phone
    }

    @Test
    fun `pinning a tab of Zen's split keeps the split on the phone`() {
        val phone = phoneWithZenSplit()

        phone.model.edit { it.pin("x") }
        phone.syncUntilSettled()

        assertTrue("Zen can't hold it, so it's gone there", zen.isDeleted("s"))
        assertEquals(true, zen.record("x")?.data?.bool("pinned"))
        assertEquals("It stays on the phone", listOf(listOf("x", "y")), phone.space.splits.map { it.tabIds })
        assertFalse("as the phone's own", phone.space.splits.single().synced)

        // And it stays through later syncs, with nothing more to say to Zen.
        zen.phoneUploads.clear()
        phone.syncUntilSettled()
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertEquals(1, phone.space.splits.size)
    }

    @Test
    fun `making a tab of Zen's split a Quick Tab keeps the split on the phone`() {
        val phone = phoneWithZenSplit()

        // The actions sheet's Kept tile.
        phone.model.edit { state -> state.updateTab("x") { it.copy(quick = QuickTab(lastViewedAt = 1), synced = false, zenUrl = null) } }
        phone.syncUntilSettled()

        assertTrue(zen.isDeleted("s"))
        assertTrue(zen.isDeleted("x"))
        assertNotNull(zen.record("y"))
        assertEquals(listOf(listOf("x", "y")), phone.space.splits.map { it.tabIds })
        assertFalse(phone.space.splits.single().synced)
    }

    @Test
    fun `the split goes back to Zen once Zen can hold it again`() {
        val phone = phoneWithZenSplit()
        phone.model.edit { it.pin("x") }
        phone.syncUntilSettled()

        phone.model.edit { it.unpin("x") }
        phone.syncUntilSettled()

        assertEquals(listOf("x", "y"), zen.record("s")?.data?.strings("tabs"))
        assertEquals(listOf("a", "s"), zen.record(SPACE)?.data?.strings("children"))
        assertTrue(phone.space.splits.single().synced)
    }

    @Test
    fun `a split Zen ended is ended on the phone`() {
        val phone = phoneWithZenSplit()

        zen.zenUploads(spaceRecord(listOf("a", "x", "y")), deleted = listOf("s"))
        phone.syncUntilSettled()

        assertEquals(emptyList<List<String>>(), phone.space.splits.map { it.tabIds })
        assertEquals(listOf("x", "y"), phone.openIds)
    }
}
