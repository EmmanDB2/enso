package app.enso.browser.sync

import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import app.enso.browser.spaces.removeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** More than 10 deletions in one sync look like lost data: that sync must upload nothing at all. */
class DeletionGuardTest {
    private val zen = FakeSyncServer()

    private fun phoneWithOpenTabs(count: Int): Pair<Phone, List<String>> {
        val tabs = (1..count).map { "t$it" }
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a") + tabs), tabRecord("a"), *tabs.map { tabRecord(it, pinned = false) }.toTypedArray())
        return syncedPhone(zen) to tabs
    }

    @Test
    fun `more than ten deletions upload nothing`() {
        val (phone, tabs) = phoneWithOpenTabs(12)

        phone.model.edit { state -> tabs.fold(state) { it, id -> it.removeTab(id) } }
        val outcome = phone.syncUntilSettled()

        assertEquals("Nothing goes to Zen, not even the space without the tabs", emptyList<List<String>>(), zen.phoneUploads)
        tabs.forEach { assertNotNull("$it is still in Zen", zen.record(it)) }
        assertEquals(listOf("a") + tabs, zen.record(SPACE)?.data?.strings("children"))
        // Zen still has them, so they come back on the phone.
        assertEquals(tabs, phone.openIds)
        // And the mirror keeps a note for the sync sheet, which the later syncs carry along.
        assertEquals(12, phone.mirrors.load().refused?.count)
        assertEquals(12, (outcome.result as ZenSyncResult.Synced).mirror.refused?.count)
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `a refused sync sends none of its other changes, and the next sends what is left`() {
        val (phone, tabs) = phoneWithOpenTabs(11)

        // Along with the deletions: a tab kept on the phone, which Zen doesn't have yet.
        phone.model.edit { state -> tabs.fold(state.addOpenTab(Tab("kept", "https://example.com/kept"), afterId = null)) { it, id -> it.removeTab(id) } }
        val refused = phone.sync()

        assertEquals(11, (refused.result as ZenSyncResult.Synced).refusedDeletions)
        assertTrue("Another sync follows for what's left", refused.again)
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertNull(zen.record("kept"))

        phone.syncUntilSettled()
        assertNotNull("The kept tab goes up once the deletions are out of the way", zen.record("kept"))
        tabs.forEach { assertNotNull("$it is still in Zen", zen.record(it)) }
        assertEquals(listOf("kept") + tabs, phone.openIds)
        assertEquals(listOf("a", "kept") + tabs, zen.record(SPACE)?.data?.strings("children"))
    }

    @Test
    fun `a dry run with too many deletions keeps the phone as it is`() {
        val (phone, tabs) = phoneWithOpenTabs(12)

        phone.model.edit { state -> tabs.fold(state) { it, id -> it.removeTab(id) } }
        phone.syncUntilSettled(dryRun = true)

        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertEquals("A dry run leaves the test edits alone", emptyList<String>(), phone.openIds)
        assertNull("Nothing was put back, so there's nothing to say", phone.mirrors.load().refused)
    }

    @Test
    fun `ten deletions go through`() {
        val (phone, tabs) = phoneWithOpenTabs(10)

        phone.model.edit { state -> tabs.fold(state) { it, id -> it.removeTab(id) } }
        phone.syncUntilSettled()

        tabs.forEach { assertTrue("$it is deleted in Zen", zen.isDeleted(it)) }
        assertEquals(listOf("a"), zen.record(SPACE)?.data?.strings("children"))
        assertEquals(emptyList<String>(), phone.openIds)
        assertNull(phone.mirrors.load().refused)
    }
}
