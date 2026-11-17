package app.enso.browser.sync

import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import app.enso.browser.spaces.updateTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Zen doesn't sync blank tabs. A tab Zen already has must not be deleted for being blank on the phone. */
class BlankTabTest {
    private val zen = FakeSyncServer()

    @Test
    fun `a synced tab on a blank page stays in Zen`() {
        zen.zenUploads(
            layoutRecord(essentials = listOf("e")),
            spaceRecord(listOf("a", "b", "c")),
            tabRecord("a"),
            tabRecord("b"),
            tabRecord("c", pinned = false),
            tabRecord("e", essential = true),
        )
        val phone = syncedPhone(zen)

        // Their pages go to about:blank by themselves: a pinned tab, an open tab and an Essential.
        phone.model.change { state ->
            listOf("a", "c", "e").fold(state) { it, id -> it.updateTab(id) { tab -> tab.copy(url = "about:blank", title = "") } }
        }
        phone.syncUntilSettled()

        assertEquals("A blank page on the phone is nothing to tell Zen", emptyList<List<String>>(), zen.phoneUploads)
        for (id in listOf("a", "c", "e")) {
            assertEquals("https://example.com/$id", zen.record(id)?.data?.string("url"))
        }
        assertEquals(listOf("a", "b"), phone.pinnedIds)
        assertEquals(listOf("c"), phone.openIds)
        assertEquals(listOf("e"), phone.model.state.essentials.map { it.id })
    }

    @Test
    fun `a new blank tab waits for a page`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        val phone = syncedPhone(zen)

        phone.model.edit { it.addOpenTab(Tab("blank", "about:blank"), afterId = null) }
        phone.syncUntilSettled()

        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
        assertNull(zen.record("blank"))
        assertEquals("The blank tab stays on the phone", listOf("blank"), phone.openIds)
    }
}
