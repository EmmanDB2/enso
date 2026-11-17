package app.enso.browser.sync

import app.enso.browser.spaces.Tab
import app.enso.browser.spaces.addOpenTab
import app.enso.browser.spaces.pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The phone's record in the account's Sync device list goes up at the end of a sync, after the phone's changes to
 * the spaces. Whatever happens to it mustn't lose that sync: the spaces upload is on the server by then.
 */
class DeviceRecordTest {
    private val zen = FakeSyncServer()
    private val device = SyncDevice("device-record", "account-device", "Fold", "0.1.0", "app.enso.browser")

    /** A synced phone whose device record is due, with a kept tab K to send. */
    private fun phoneWithTabToSend(): Phone {
        zen.zenUploads(layoutRecord(), spaceRecord(listOf("a")), tabRecord("a"))
        val synced = syncedPhone(zen)
        val phone = Phone(zen, synced.mirrors, synced.model, synced.snapshots, device)
        phone.model.edit { it.addOpenTab(Tab("k", "https://example.com/k"), afterId = null) }
        return phone
    }

    @Test
    fun `a device record that's lost on the way doesn't fail the sync that sent the phone's changes`() {
        val phone = phoneWithTabToSend()
        zen.duringNextDeviceUpload = { throw IOException("Connection lost") }

        phone.sync()

        assertNotNull(zen.record("k"))
        assertNotNull("The mirror knows the tab went up", phone.mirrors.load().records["k"])
        assertTrue("And the phone shows it as synced", phone.space.tabs.single { it.id == "k" }.synced)
        phone.assertModelReflectsMirror()
        assertEquals(emptyList<String>(), zen.deviceUploads)

        // The record goes up with the next sync instead.
        phone.sync()
        assertEquals(listOf("device-record"), zen.deviceUploads)
    }

    // Before the fix that sync failed with the mirror unsaved. The next one downloaded the phone's own tab as though
    // Zen had changed it, and Zen's version won over whatever the user had done to the tab since.
    @Test
    fun `an edit made right after that sync still reaches Zen`() {
        val phone = phoneWithTabToSend()
        zen.duringNextDeviceUpload = { throw IOException("Connection lost") }
        runCatching { phone.sync() }

        phone.model.edit { it.pin("k") }
        phone.syncUntilSettled()

        assertEquals(listOf("a", "k"), phone.pinnedIds)
        assertEquals(true, zen.record("k")?.data?.bool("pinned"))
    }

    @Test
    fun `a device record the server refuses the token for doesn't lose the sync either`() {
        val phone = phoneWithTabToSend()
        zen.refuseNextDeviceUpload = true

        phone.sync()
        phone.syncUntilSettled()

        assertNotNull(zen.record("k"))
        assertTrue(phone.space.tabs.single { it.id == "k" }.synced)
        assertEquals("The tab went up once", 1, zen.phoneUploads.count { "k" in it })
        assertEquals(listOf("device-record"), zen.deviceUploads)
        phone.assertModelReflectsMirror()
    }
}
