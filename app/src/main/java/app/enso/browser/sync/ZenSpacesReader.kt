package app.enso.browser.sync

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** How a read ended when it didn't throw. */
sealed interface ZenReadResult {
    /** The mirror is up to date; [changed] records arrived or went. */
    data class Synced(val mirror: ZenMirror, val changed: Int) : ZenReadResult

    /** Firefox Sync hasn't set up this account's storage yet. */
    data object NotSetUp : ZenReadResult

    /** No device has turned on Zen's spaces sync for this account, or one turned it off. */
    data object NotTurnedOn : ZenReadResult

    /** Zen writes a newer version of the collection than Ensō reads. */
    data class NeedsUpdate(val serverVersion: Int) : ZenReadResult
}

/**
 * Downloads Zen's "spaces" collection into the [ZenMirror], read-only. Follows Firefox's sync engine startup:
 * meta/global decides whether the collection is in use, crypto/keys holds its key, and only records newer than
 * the last download are fetched. Blocking; call off the main thread.
 */
internal class ZenSpacesReader(
    private val http: SyncHttp,
    private val store: ZenMirrorStore,
    private val clock: SyncClock = SyncClock(),
) {
    @Volatile
    private var token: SyncToken? = null

    /** Local time before which the servers asked not to sync again. */
    @Volatile
    var backoffUntil: Long = 0L
        private set

    /** Forgets everything about the server, for a sign out. */
    fun reset() {
        token = null
        store.clear()
    }

    fun read(auth: SyncAuth): ZenReadResult = try {
        run(auth)
    } catch (e: SyncAuthException) {
        // Tokens sometimes expire before their stated end; one retry with a new one is enough.
        token = null
        Log.i(TAG, "Token refused, retrying once: ${e.message}")
        run(auth)
    }

    @Suppress("ReturnCount")
    private fun run(auth: SyncAuth): ZenReadResult {
        val syncKeys = KeyBundle.fromSyncKey(auth.syncKey)
        val token = currentToken(auth)
        val client = StorageClient(http, token, clock) { seconds -> backoffUntil = clock.millis() + seconds * MILLIS }
        var mirror = store.load()
        if (mirror.storageUid != token.uid) mirror = ZenMirror(storageUid = token.uid)

        val info = JSONObject(client.expect("GET", "info/collections").body)
        if (!info.has("meta") || !info.has("crypto")) return ZenReadResult.NotSetUp

        val meta = client.expect("GET", "storage/meta/global", allowed = setOf(HTTP_NOT_FOUND))
        if (meta.status == HTTP_NOT_FOUND) return ZenReadResult.NotSetUp
        val metaPayload = JSONObject(JSONObject(meta.body).getString("payload"))
        if (metaPayload.optInt("storageVersion") != STORAGE_VERSION) return ZenReadResult.NotSetUp
        val entry = metaPayload.optJSONObject("engines")?.optJSONObject(COLLECTION)
        val version = entry?.optInt("version") ?: 0
        val syncId = entry?.optString("syncID")?.ifEmpty { null }
        if (version > ENGINE_VERSION) return ZenReadResult.NeedsUpdate(version)
        if (version < ENGINE_VERSION || syncId == null) return ZenReadResult.NotTurnedOn
        if (syncId != mirror.syncId) {
            Log.i(TAG, "The spaces collection was set up or reset elsewhere; downloading everything")
            mirror = ZenMirror(storageUid = token.uid, syncId = syncId)
        }

        val keysResponse = client.expect("GET", "storage/crypto/keys", allowed = setOf(HTTP_NOT_FOUND))
        if (keysResponse.status == HTTP_NOT_FOUND) return ZenReadResult.NotSetUp
        val keys = JSONObject(syncKeys.decrypt(JSONObject(JSONObject(keysResponse.body).getString("payload"))))
        val bundle = KeyBundle.fromPair(keys.optJSONObject("collections")?.optJSONArray(COLLECTION))
            ?: KeyBundle.fromPair(keys.optJSONArray("default"))
            ?: throw SyncCryptoException("crypto/keys has no default key")
        val keysModified = keysResponse.header("X-Last-Modified")
        if (mirror.keysModified != null && keysModified != mirror.keysModified) {
            Log.i(TAG, "The collection keys changed; downloading everything")
            mirror = ZenMirror(storageUid = token.uid, syncId = syncId)
        }
        mirror = mirror.copy(keysModified = keysModified)

        val serverModified = timestampOf(info.opt(COLLECTION))
        var changed = 0
        if (serverModified == null) {
            changed = mirror.records.size
            mirror = mirror.copy(records = emptyMap(), lastModified = null)
        } else {
            if (mirror.lastModified != null && isNewerTimestamp(mirror.lastModified, serverModified)) {
                Log.i(TAG, "The spaces collection was emptied on the server; downloading everything")
                mirror = mirror.copy(records = emptyMap(), lastModified = null)
            }
            if (mirror.lastModified == null || isNewerTimestamp(serverModified, mirror.lastModified)) {
                val records = mirror.records.toMutableMap()
                val modified = download(client, bundle, since = mirror.lastModified) { id, record ->
                    changed++
                    if (record == null) records.remove(id) else records[id] = record
                }
                mirror = mirror.copy(records = records, lastModified = modified ?: serverModified)
            }
        }
        mirror = mirror.copy(lastSynced = clock.millis())
        store.save(mirror)
        Log.i(TAG, "Read ${mirror.records.size} records, $changed changed")
        return ZenReadResult.Synced(mirror, changed)
    }

    private fun currentToken(auth: SyncAuth): SyncToken =
        token?.takeIf { it.expiresAt > clock.millis() } ?: TokenServerClient(http, clock).fetch(auth).also { token = it }

    /** Downloads records changed since [since], passing each to [onRecord] (null for a deletion). */
    private fun download(
        client: StorageClient,
        keys: KeyBundle,
        since: String?,
        onRecord: (String, ZenRecord?) -> Unit,
    ): String? {
        var modified: String? = null
        var offset: String? = null
        var skipped = 0
        do {
            val query = buildString {
                append("full=1&sort=oldest&limit=").append(PAGE_SIZE)
                since?.let { append("&newer=").append(it) }
                offset?.let { append("&offset=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val response = client.expect(
                "GET",
                "storage/$COLLECTION?$query",
                ifUnmodifiedSince = modified.takeIf { offset != null },
                allowed = setOf(HTTP_NOT_FOUND),
            )
            if (response.status == HTTP_NOT_FOUND) break
            if (modified == null) modified = response.header("X-Last-Modified")
            val items = JSONArray(response.body)
            for (index in 0 until items.length()) {
                val bso = items.optJSONObject(index) ?: continue
                val id = bso.optString("id").ifEmpty { continue }
                val parsed = runCatching { parse(id, keys.decrypt(JSONObject(bso.getString("payload")))) }
                parsed.onSuccess { onRecord(id, it) }.onFailure { skipped++ }
            }
            offset = response.header("X-Weave-Next-Offset")
        } while (offset != null)
        if (skipped > 0) Log.w(TAG, "Skipped $skipped records that couldn't be read")
        return modified
    }

    /** A record, or null for a deletion (Firefox Sync's `{id, deleted: true}`). */
    private fun parse(id: String, cleartext: String): ZenRecord? {
        val obj = json.parseToJsonElement(cleartext).jsonObject
        obj["id"]?.jsonPrimitive?.contentOrNull?.let { check(it == id) { "Record id mismatch" } }
        if (obj["deleted"]?.jsonPrimitive?.booleanOrNull == true) return null
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: error("Record without kind")
        val data = obj["data"] as? JsonObject ?: error("Record without data")
        return ZenRecord(id, kind, data)
    }

    companion object {
        private const val TAG = "ZenSync"

        /** Name of the collection, and of Zen's sync engine in meta/global. */
        const val COLLECTION = "spaces"

        /** Version of the records, matching Zen's `ZenSpacesSyncEngine.version`. */
        const val ENGINE_VERSION = 3

        private const val STORAGE_VERSION = 5
        private const val PAGE_SIZE = 1000
        private const val MILLIS = 1000L
        private const val HTTP_NOT_FOUND = 404
        private val json = Json { ignoreUnknownKeys = true }
    }
}
