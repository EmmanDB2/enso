package app.enso.browser.sync

import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import app.enso.browser.spaces.pin
import app.enso.browser.spaces.removeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An open tab belongs to the space its own record names, as in Zen; a space's `children` only orders them. Zen with
 * its normal-tab sync off (its default) leaves the phone's kept tabs out of `children`, and that must not take them
 * off the phone.
 */
class KeptTabsTest {
    private val zen = FakeSyncServer()

    /** A phone that kept tab K and sent it up, with Zen's normal-tab sync off: Zen has unlisted K since. */
    private fun phoneWithKeptTabZenLeftOut(): Phone {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        val phone = syncedPhone(zen)
        phone.model.edit { it.addOpenTab(Tab("k", "https://example.com/k"), afterId = null) }
        phone.syncUntilSettled()
        assertEquals(listOf("a", "k"), zen.record(SPACE)?.data?.strings("children"))
        assertTrue("Zen uploads the space without the tab", zen.zenWithoutNormalTabsSyncs())
        zen.phoneUploads.clear()
        phone.syncUntilSettled()
        return phone
    }

    @Test
    fun `a kept tab stays when Zen leaves it out of its space`() {
        val phone = phoneWithKeptTabZenLeftOut()

        assertEquals("The kept tab is still on the phone", listOf("k"), phone.openIds)
        assertEquals(listOf("a"), phone.pinnedIds)
        assertNotNull(zen.record("k"))
        // And the two don't trade the space record back and forth.
        assertEquals("The phone doesn't put the tab back into the space's children", emptyList<List<String>>(), zen.phoneUploads)
        assertFalse(zen.zenWithoutNormalTabsSyncs())
        phone.syncUntilSettled()
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertEquals(listOf("a"), zen.record(SPACE)?.data?.strings("children"))
        phone.assertModelReflectsMirror()
    }

    @Test
    fun `more kept tabs go up while the earlier ones stay`() {
        val phone = phoneWithKeptTabZenLeftOut()

        phone.model.edit { it.addOpenTab(Tab("k2", "https://example.com/k2"), afterId = null) }
        phone.syncUntilSettled()
        assertNotNull(zen.record("k2"))
        assertEquals("The new tab is listed once; the one Zen left out stays out", listOf("a", "k2"), zen.record(SPACE)?.data?.strings("children"))

        assertTrue(zen.zenWithoutNormalTabsSyncs())
        phone.syncUntilSettled()
        assertEquals(listOf("k2", "k"), phone.openIds)
        assertFalse(zen.zenWithoutNormalTabsSyncs())
    }

    @Test
    fun `closing a tab Zen left out deletes its record`() {
        val phone = phoneWithKeptTabZenLeftOut()

        phone.model.edit { it.removeTab("k") }
        phone.syncUntilSettled()

        assertTrue(zen.isDeleted("k"))
        assertEquals(emptyList<String>(), phone.openIds)
        assertEquals(listOf("a"), zen.record(SPACE)?.data?.strings("children"))
    }

    @Test
    fun `pinning a tab Zen left out lists it again`() {
        val phone = phoneWithKeptTabZenLeftOut()

        phone.model.edit { it.pin("k") }
        phone.syncUntilSettled()

        assertEquals(true, zen.record("k")?.data?.bool("pinned"))
        assertEquals(listOf("a", "k"), zen.record(SPACE)?.data?.strings("children"))
        assertEquals(listOf("a", "k"), phone.pinnedIds)
        assertEquals(emptyList<String>(), phone.openIds)
    }

    @Test
    fun `another phone shows the tab Zen left out`() {
        phoneWithKeptTabZenLeftOut()

        val other = Phone(zen)
        other.syncUntilSettled()

        assertEquals(listOf("k"), other.openIds)
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
    }

    @Test
    fun `a tab Zen deletes still goes`() {
        val phone = phoneWithKeptTabZenLeftOut()

        zen.zenUploads(deleted = listOf("k"))
        phone.syncUntilSettled()

        assertEquals(emptyList<String>(), phone.openIds)
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
    }
}
