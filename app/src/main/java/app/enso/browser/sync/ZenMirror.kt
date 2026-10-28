package app.enso.browser.sync

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.core.util.readText
import androidx.core.util.writeText
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
    /** When the last sync finished, local milliseconds. */
    val lastSynced: Long? = null,
    val records: Map<String, ZenRecord> = emptyMap(),
)

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

/** Keeps the [ZenMirror] in a file, so syncs only download what changed. */
class ZenMirrorStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "zen-spaces-mirror.json"))
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): ZenMirror =
        try {
            json.decodeFromString(file.readText())
        } catch (_: FileNotFoundException) {
            ZenMirror()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the Zen mirror; downloading again", e)
            ZenMirror()
        }

    @Synchronized
    fun save(mirror: ZenMirror) = file.writeText(json.encodeToString(mirror))

    @Synchronized
    fun clear() = file.delete()

    private companion object {
        const val TAG = "ZenSync"
    }
}
