package app.enso.browser.sync

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.core.util.readText
import androidx.core.util.writeText
import app.enso.browser.spaces.ZenStamp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileNotFoundException

/**
 * One decrypted record of Zen's "spaces" collection, `{id, kind, data}`. Kinds are space, folder, tab, split,
 * container and layout (see Zen's ZenSpacesSyncModel.sys.mjs). [data] is kept whole, so fields Ensō doesn't use
 * survive a later write back.
 */
@Serializable
data class ZenRecord(val id: String, val kind: String, val data: JsonObject)

/** What Ensō last downloaded from the account's "spaces" collection, and how far the download got. */
@Serializable
data class ZenMirror(
    /** The storage user; a different one means another account, or storage that was reset or moved. */
    val storageUid: String? = null,
    /** The collection's sync id in meta/global; it changes when a device resets the collection. */
    val syncId: String? = null,
    val keysModified: String? = null,
    /** Server time of the collection at the last download, in decimal seconds. */
    val lastModified: String? = null,
    /**
     * Set while the mirror holds uploads of the phone's from after [lastModified]: the server time of the latest.
     * That's the mirror saved after a sync the phone's model didn't take in (see [ZenSyncResult.Synced.unapplied]).
     */
    val ownUploadAt: String? = null,
    /** When the last sync finished, local milliseconds. */
    val lastSynced: Long? = null,
    val records: Map<String, ZenRecord> = emptyMap(),
    /** What this phone's Sync device record last said ([SyncDevice.fingerprint]), and when it went up. */
    val deviceRecord: String? = null,
    val deviceUploadedAt: Long? = null,
    /** The last sync that held too many deletions to send, kept here for the sync sheet to tell the user. */
    val refused: RefusedDeletions? = null,
    /**
     * Ids whose newest version on the server couldn't be read. Each sync asks for them again, and until one reads,
     * none of the phone's changes to it goes up: [records] has an older copy of it, or none, and Zen's version wins.
     */
    val unread: Set<String> = emptySet(),
    /**
     * Ids the server holds a deletion for, as far as the phone has seen. When a list in [records] names an id that
     * has no record, this tells one that was deleted, which the phone writes out of the list when it next writes it,
     * from one the server never sent a record for, which stays.
     */
    val gone: Set<String> = emptySet(),
)

/** What a model that reflects this mirror says of it (see [ZenStamp]). */
internal val ZenMirror.stamp: ZenStamp get() = ZenStamp(lastModified, syncId, ownUploadAt)

/**
 * A sync whose changes held [count] deletions, more than one sync may send, at [at] (local milliseconds). Nothing of
 * it was sent, and Zen's state went over the phone's model, which put back what had been removed.
 */
@Serializable
data class RefusedDeletions(val count: Int, val at: Long)

/**
 * Each synced tab's icon as Zen's record has it, by tab id: mostly `data:` images, sometimes a web address. Zen's
 * own `chrome:` icons mean nothing outside Zen and are left out.
 */
fun ZenMirror.tabIcons(): Map<String, String> = records.values
    .filter { it.kind == KIND_TAB }
    .mapNotNull { record ->
        record.data.string("icon")
            ?.takeIf { it.startsWith("data:image/") || it.startsWith("https://") || it.startsWith("http://") }
            ?.let { record.id to it }
    }
    .toMap()

/** Where the engine keeps the [ZenMirror] between syncs. */
internal interface MirrorStore {
    fun load(): ZenMirror

    fun save(mirror: ZenMirror)

    fun clear()
}

/** Keeps the [ZenMirror] in a file, so syncs only download what changed. */
internal class ZenMirrorStore(context: Context) : MirrorStore {
    private val file = AtomicFile(File(context.filesDir, "zen-spaces-mirror.json"))
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun load(): ZenMirror =
        try {
            json.decodeFromString(file.readText())
        } catch (_: FileNotFoundException) {
            ZenMirror()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the Zen mirror; downloading again", e)
            ZenMirror()
        }

    @Synchronized
    override fun save(mirror: ZenMirror) = file.writeText(json.encodeToString(mirror))

    @Synchronized
    override fun clear() = file.delete()

    private companion object {
        const val TAG = "ZenSync"
    }
}
