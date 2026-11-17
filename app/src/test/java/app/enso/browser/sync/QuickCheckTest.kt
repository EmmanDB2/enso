package app.enso.browser.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While the app is open it asks every half minute whether Zen changed anything, and syncs when it did. With nothing
 * to compare, that check used to say yes every time, which made it a full sync every half minute.
 */
class QuickCheckTest {
    private val zen = FakeSyncServer()

    @Test
    fun `a collection with nothing in it yet isn't news every time`() {
        // Zen's sync is on for the account, and it hasn't uploaded anything yet.
        val phone = Phone(zen)
        phone.sync()

        assertFalse(phone.engine.serverChanged(zen.auth))

        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        assertTrue(phone.engine.serverChanged(zen.auth))
        phone.syncUntilSettled()
        assertEquals(listOf("a"), phone.pinnedIds)
        assertFalse(phone.engine.serverChanged(zen.auth))
    }

    // With Zen's spaces sync off the phone never gets a collection time to compare, so the check says "changed" for
    // as long as the collection exists. It mustn't run then, or after a failed sync.
    @Test
    fun `the check waits while a sync can't get anywhere by itself`() {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        zen.turnedOn = false
        val phone = Phone(zen)
        assertEquals(ZenSyncResult.NotTurnedOn, phone.sync().result)
        assertTrue("Asking would mean a full sync every time", phone.engine.serverChanged(zen.auth))

        assertFalse(ZenSyncStatus.State.ZenSyncOff.allowsQuickCheck())
        assertFalse(ZenSyncStatus.State.Failed("Network error").allowsQuickCheck())
        assertFalse(ZenSyncStatus.State.NeedsSignIn.allowsQuickCheck())
        assertFalse(ZenSyncStatus.State.NeedsUpdate.allowsQuickCheck())
        assertTrue(ZenSyncStatus.State.Synced(at = 1).allowsQuickCheck())
    }
}
