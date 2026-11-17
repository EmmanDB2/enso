package app.enso.browser.sync

import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import app.enso.browser.spaces.addToEssentials
import app.enso.browser.spaces.moveToFolder
import app.enso.browser.spaces.removeTab
import app.enso.browser.spaces.unpin
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Zen lists a tab that has no page yet among its parent's children without sending a record for it. The phone can't
 * show such a tab, and it must leave the id where it is: writing it out of the list made Zen put it back, and the
 * two traded the record on every sync.
 */
class UnknownChildrenTest {
    private val zen = FakeSyncServer()

    private val spaceChildren get() = zen.record(SPACE)?.data?.strings("children")

    @Test
    fun `a child without a record stays in the space's children`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "blank", "b")), tabRecord("a"), tabRecord("b", pinned = false))

        // This also checks that the phone uploaded nothing.
        val phone = syncedPhone(zen)

        assertEquals(listOf("a"), phone.pinnedIds)
        assertEquals(listOf("b"), phone.openIds)
        assertEquals(listOf("a", "blank", "b"), spaceChildren)
    }

    @Test
    fun `it keeps its place when the phone changes the children around it`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "blank", "b")), tabRecord("a"), tabRecord("b", pinned = false))
        val phone = syncedPhone(zen)

        phone.model.edit { it.addOpenTab(Tab("k", "https://example.com/k"), afterId = null) }
        phone.syncUntilSettled()
        assertEquals("It still follows the tab it followed", listOf("a", "blank", "k", "b"), spaceChildren)

        phone.model.edit { it.removeTab("a") }
        phone.syncUntilSettled()
        assertEquals(listOf("blank", "k", "b"), spaceChildren)
        assertEquals(listOf("k", "b"), phone.openIds)
    }

    @Test
    fun `a child without a record stays in a folder's children`() {
        zen.zenUploads(
            layoutRecord(), spaceRecord(listOf("f", "a")), folderRecord("f", listOf("p", "blank")),
            tabRecord("p", folder = "f"), tabRecord("a"),
        )
        val phone = syncedPhone(zen)
        assertEquals(listOf("p", "blank"), zen.record("f")?.data?.strings("children"))

        phone.model.edit { it.moveToFolder("a", "f") }
        phone.syncUntilSettled()
        assertEquals(listOf("p", "blank", "a"), zen.record("f")?.data?.strings("children"))
        assertEquals(listOf("f"), spaceChildren)
    }

    @Test
    fun `an Essential without a record stays among the Essentials`() {
        fun essentials() = (zen.record(LAYOUT_ID)?.data?.get("essentials") as? JsonObject)?.get(DEFAULT_CONTAINER).strings()
        zen.zenUploads(layoutRecord(essentials = listOf("e", "blank")), spaceRecord(listOf("a")), tabRecord("e", essential = true), tabRecord("a"))
        val phone = syncedPhone(zen)
        assertEquals(listOf("e", "blank"), essentials())

        phone.model.edit { it.addToEssentials("a") }
        phone.syncUntilSettled()
        assertEquals(listOf("e", "blank", "a"), essentials())
    }

    @Test
    fun `a split view with a tab that has no record stays as Zen has it`() {
        zen.zenUploads(
            layoutRecord(), spaceRecord(listOf("two", "three")),
            splitRecord("two", listOf("x", "blank")), tabRecord("x", pinned = false),
            splitRecord("three", listOf("y", "empty", "z")), tabRecord("y", pinned = false), tabRecord("z", pinned = false),
        )

        val phone = syncedPhone(zen)

        assertEquals(listOf("x", "y", "z"), phone.openIds)
        assertEquals("The phone shows the split of the two tabs it has", listOf(listOf("y", "z")), phone.space.splits.map { it.tabIds })
        assertEquals(listOf("two", "three"), spaceChildren)
        assertEquals(listOf("y", "empty", "z"), zen.record("three")?.data?.strings("tabs"))
    }

    // Zen's newer version of a record is on the server, and the phone can't read it. Sending the phone's change
    // would put its older copy over Zen's.
    @Test
    fun `a change to a record whose newer version the phone can't read waits`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b")), tabRecord("a"), tabRecord("b"))
        val phone = syncedPhone(zen)
        zen.zenUploadsUnreadable(tabRecord("b", title = "Renamed in Zen"))
        phone.syncUntilSettled()

        phone.model.edit { it.unpin("b") }
        phone.syncUntilSettled()

        assertEquals("Nothing goes over the version the phone couldn't read", emptyList<List<String>>(), zen.phoneUploads)
        assertEquals("And the tab is pinned on the phone again, as Zen has it", listOf("a", "b"), phone.pinnedIds)
        assertEquals(setOf("b"), phone.mirrors.load().unread)

        // Once it can be read, the phone takes it in. Its time on the server is the same, so only asking for it by
        // name brings it.
        zen.becomesReadable("b")
        phone.syncUntilSettled()
        assertEquals("Renamed in Zen", phone.mirrors.load().records["b"]?.data?.string("title"))
        assertEquals(emptySet<String>(), phone.mirrors.load().unread)
        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
    }
}
