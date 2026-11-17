package app.enso.browser.sync

import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot is the whole collection as it was before the phone first wrote to it, and a restore puts the
 * collection back to it: it uploads the snapshot's records and deletes every other record.
 */
class SnapshotTest {
    private val zen = FakeSyncServer()

    /** A phone that has sent Zen a kept tab K, which took the snapshot first. */
    private fun phoneThatWrote(): Phone {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        val phone = syncedPhone(zen)
        phone.model.edit { it.addOpenTab(Tab("k", "https://example.com/k"), afterId = null) }
        phone.syncUntilSettled()
        assertNotNull("The first write saved a snapshot", phone.snapshots.snapshot)
        zen.phoneUploads.clear()
        return phone
    }

    @Test
    fun `a restore puts the collection back as it was before the phone's first write`() {
        val phone = phoneThatWrote()

        phone.engine.restoreSnapshot(zen.auth)

        assertTrue(zen.isDeleted("k"))
        assertEquals(listOf("a"), zen.record(SPACE)?.data?.strings("children"))
        phone.syncUntilSettled()
        assertEquals(emptyList<String>(), phone.openIds)
    }

    // After a password reset the account has new keys, and Zen has uploaded everything again under them. The old
    // snapshot's records can't be read with those keys: restoring it would put unreadable records on the server and
    // delete every record Zen has now.
    @Test
    fun `a snapshot from before the account's keys changed isn't restored`() {
        val phone = phoneThatWrote()
        zen.passwordReset()
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "n")), tabRecord("a"), tabRecord("n"))

        assertThrows(IllegalStateException::class.java) { phone.engine.restoreSnapshot(zen.auth) }

        assertEquals("Nothing was written", emptyList<List<String>>(), zen.phoneUploads)
        assertNotNull(zen.record("n"))
        assertEquals(listOf("a", "n"), zen.record(SPACE)?.data?.strings("children"))
    }

    @Test
    fun `the phone's first write under new keys takes a snapshot of its own`() {
        val phone = phoneThatWrote()
        val old = phone.snapshots.snapshot
        zen.passwordReset()
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "n")), tabRecord("a"), tabRecord("n"))
        phone.syncUntilSettled()
        assertEquals("Nothing was written yet, so the snapshot is still the old one", old, phone.snapshots.snapshot)

        phone.model.edit { it.addOpenTab(Tab("k2", "https://example.com/k2"), afterId = null) }
        phone.syncUntilSettled()

        val snapshot = phone.snapshots.snapshot!!
        assertEquals("It's the collection from before that write", setOf(LAYOUT_ID, SPACE, "a", "n"), snapshot.ids)
        assertEquals("The old one is kept aside", listOf(old), phone.snapshots.kept.filterKeys { it.startsWith("replaced-") }.values.toList())

        // And it restores.
        phone.engine.restoreSnapshot(zen.auth)
        assertTrue(zen.isDeleted("k2"))
        assertNotNull(zen.record("n"))
    }

    @Test
    fun `a snapshot of another account isn't restored`() {
        val first = phoneThatWrote()
        // The phone signs out and into another account. The snapshot file stays.
        zen.uid = 2
        val phone = Phone(zen, snapshots = first.snapshots)
        phone.syncUntilSettled()
        zen.phoneUploads.clear()

        assertThrows(IllegalStateException::class.java) { phone.engine.restoreSnapshot(zen.auth) }

        assertEquals(emptyList<List<String>>(), zen.phoneUploads)
    }

    @Test
    fun `a restore keeps what it replaces, so it can be put back`() {
        val phone = phoneThatWrote()

        phone.engine.restoreSnapshot(zen.auth)
        assertTrue(zen.isDeleted("k"))
        val name = phone.snapshots.kept.keys.single { it.startsWith("before-restore-") }
        assertTrue("k" in phone.snapshots.kept.getValue(name).ids)

        phone.engine.restoreSnapshot(zen.auth, name)
        assertNotNull("The tab the first restore deleted is back", zen.record("k"))
        assertEquals(listOf("a", "k"), zen.record(SPACE)?.data?.strings("children"))
    }

    @Test
    fun `several deletions arriving from Zen keep a snapshot from before them`() {
        val tabs = (1..6).map { "t$it" }
        zen.zenUploads(layoutRecord(), spaceRecord(tabs), *tabs.map { tabRecord(it) }.toTypedArray())
        val phone = syncedPhone(zen)
        assertEquals(emptyMap<String, ZenSnapshot>(), phone.snapshots.kept)

        // Zen deletes five of the six: its user closed them, or a bug emptied the space.
        zen.zenUploads(spaceRecord(listOf("t6")), deleted = tabs.dropLast(1))
        phone.syncUntilSettled()
        assertEquals(listOf("t6"), phone.pinnedIds)

        val name = phone.snapshots.kept.keys.single()
        assertTrue(name.startsWith("before-deletions-"))
        assertEquals((tabs + listOf(LAYOUT_ID, SPACE)).toSet(), phone.snapshots.kept.getValue(name).ids)

        // Restoring it puts the tabs back, for Zen and for the phone.
        phone.engine.restoreSnapshot(zen.auth, name)
        assertEquals(tabs, zen.record(SPACE)?.data?.strings("children"))
        phone.syncUntilSettled()
        assertEquals(tabs, phone.pinnedIds)
    }

    @Test
    fun `fewer deletions keep no snapshot`() {
        val tabs = (1..6).map { "t$it" }
        zen.zenUploads(layoutRecord(), spaceRecord(tabs), *tabs.map { tabRecord(it) }.toTypedArray())
        val phone = syncedPhone(zen)

        zen.zenUploads(spaceRecord(tabs.drop(4)), deleted = tabs.take(4))
        phone.syncUntilSettled()

        assertEquals(emptyMap<String, ZenSnapshot>(), phone.snapshots.kept)
    }

    @Test
    fun `syncs that see the same deletions again keep one snapshot`() {
        val tabs = (1..6).map { "t$it" }
        zen.zenUploads(layoutRecord(), spaceRecord(tabs), *tabs.map { tabRecord(it) }.toTypedArray())
        val phone = syncedPhone(zen)
        // A dry run with something to send doesn't take Zen's state in, so every sync downloads the deletions again.
        phone.model.edit { it.addOpenTab(Tab("k", "https://example.com/k"), afterId = null) }
        zen.zenUploads(spaceRecord(listOf("t6")), deleted = tabs.dropLast(1))

        repeat(3) { phone.sync(dryRun = true) }

        assertEquals(1, phone.snapshots.kept.size)
    }

    @Test
    fun `a collection reset on the server keeps a snapshot of what the phone knew`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a", "b")), tabRecord("a"), tabRecord("b"))
        val phone = syncedPhone(zen)

        // Another device starts the collection over, with less in it.
        zen.syncId = "another-sync-id"
        zen.zenUploads(spaceRecord(listOf("a")), deleted = listOf("b"))
        phone.syncUntilSettled()
        assertEquals(listOf("a"), phone.pinnedIds)

        val name = phone.snapshots.kept.keys.single()
        assertTrue(name.startsWith("before-reset-"))
        assertTrue("b" in phone.snapshots.kept.getValue(name).ids)
        phone.engine.restoreSnapshot(zen.auth, name)
        assertNotNull(zen.record("b"))
    }

    private val ZenSnapshot.ids: Set<String> get() = records.mapTo(mutableSetOf()) { it.getValue("id").jsonPrimitive.content }
}
