package app.enso.browser.spaces

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tab sheet's edits on the spaces model. */
class SpacesEditsTest {
    private val pinned = Tab("p", "https://example.com/p", pinnedUrl = "https://example.com/p")
    private val open = Tab("o", "https://example.com/o")
    private val folder = Folder(id = "f", name = "Folder")
    private val state = SpacesState(
        spaces = listOf(Space(id = "s", name = "Work", icon = "", accent = 0, pinned = listOf(PinnedTab(pinned), folder), tabs = listOf(open))),
        currentSpaceId = "s",
    )

    @Test
    fun `moving a tab into a folder puts it there, pinned`() {
        val moved = state.moveToFolder("o", "f")

        assertEquals(listOf("o"), moved.spaces.single().folders.single().tabs.map { it.id })
        assertEquals(emptyList<Tab>(), moved.spaces.single().tabs)
    }

    // The hold menu can still list a folder that a sync has just taken away. The tab must stay where it is then: it
    // used to leave every list, and the next sync deleted it from Zen.
    @Test
    fun `moving a tab into a folder that's gone changes nothing`() {
        assertEquals(state, state.moveToFolder("p", "gone"))
        assertEquals(state, state.moveToFolder("o", "gone"))
    }
}
