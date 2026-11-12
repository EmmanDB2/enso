package app.enso.browser.sync

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.core.util.readText
import androidx.core.util.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.URLEncoder

/** How a sync ended when it didn't throw. */
sealed interface ZenSyncResult {
    /**
     * [received] records arrived or went, [sent] phone changes were uploaded, and [unsent] weren't (a dry run, or
     * the server refused them). [held] phone changes waited because Zen changed the same records: they go once
     * Zen's versions are applied.
     */
    data class Synced(val mirror: ZenMirror, val received: Int, val sent: Int, val unsent: Int, val held: Int = 0) :
        ZenSyncResult

    /** Firefox Sync hasn't set up this account's storage yet. */
    data object NotSetUp : ZenSyncResult

    /** No device has turned on Zen's spaces sync for this account, or one turned it off. */
    data object NotTurnedOn : ZenSyncResult

    /** Zen writes a newer version of the collection than Ensō reads. */
    data class NeedsUpdate(val serverVersion: Int) : ZenSyncResult
}

/**
 * This phone as a device in the account's Sync device list (the "clients" collection), where Firefox and Zen count
 * the devices they sync with. Zen syncs every 10 minutes, and when you come back to the computer or wake it, only
 * while it counts another device; on its own it waits an hour.
 *
 * @property recordId The record's id, made once per install.
 * @property fxaDeviceId The phone's device in the Mozilla account. Firefox hides device records whose account device
 *   is gone, so a signed-out phone drops out by itself.
 */
internal class SyncDevice(
    val recordId: String,
    val fxaDeviceId: String,
    val name: String,
    val version: String,
    val appPackage: String,
) {
    /** What the record says; a change uploads it again. */
    val fingerprint: String get() = listOf(recordId, fxaDeviceId, name, version).joinToString("|")
}

/**
 * Syncs Zen's "spaces" collection both ways, like Firefox's sync engines: meta/global says whether the collection is
 * in use, crypto/keys holds its key, and only records changed since the last sync are downloaded. Phone changes are
 * worked out against the [ZenMirror] (the last known server state), and Zen's version wins where both sides changed
 * the same record. Blocking; call off the main thread.
 */
internal class ZenSpacesEngine(
    private val http: SyncHttp,
    private val store: ZenMirrorStore,
    private val snapshots: ZenSnapshotStore,
    private val clock: SyncClock = SyncClock(),
) {
    @Volatile
    private var token: SyncToken? = null

    /** Local time before which the servers asked not to sync again. */
    @Volatile
    var backoffUntil: Long = 0L
        private set

    /** Forgets everything about the server, for a sign out. The snapshot stays. */
    fun reset() {
        token = null
        known = null
        store.clear()
    }

    /**
     * Syncs. [localChanges] works out the phone's changes against the server records it's given. With [dryRun] they're
     * only logged, never uploaded.
     */
    fun sync(
        auth: SyncAuth,
        dryRun: Boolean,
        device: SyncDevice?,
        localChanges: (Map<String, ZenRecord>) -> List<ZenChange>,
    ): ZenSyncResult = retryingAuth { run(auth, dryRun, device, localChanges) }

    /** The storage user and the collection's server time that the last sync left, for [serverChanged]. */
    @Volatile
    private var known: Pair<String?, String?>? = null

    /**
     * Whether the spaces collection changed on the server since the last sync: one small request (and a token now
     * and then), cheap enough to ask every few seconds while the app is open.
     */
    fun serverChanged(auth: SyncAuth): Boolean = retryingAuth {
        val token = currentToken(auth)
        val client = StorageClient(http, token, clock) { seconds -> backoffUntil = clock.millis() + seconds * MILLIS }
        val (uid, lastModified) = known ?: store.load().let { (it.storageUid to it.lastModified).also { pair -> known = pair } }
        if (uid != token.uid || lastModified == null) return@retryingAuth true
        val info = JSONObject(client.expect("GET", "info/collections").body)
        val server = timestampOf(info.opt(COLLECTION)) ?: return@retryingAuth true
        isNewerTimestamp(server, lastModified) || isNewerTimestamp(lastModified, server)
    }

    /** Puts the collection back as the snapshot taken before the first write. Returns the records written. */
    fun restoreSnapshot(auth: SyncAuth): Int = retryingAuth {
        val snapshot = snapshots.load() ?: error("There's no snapshot to restore")
        val session = when (val opened = open(auth)) {
            is Opened.Ready -> opened.session
            is Opened.Stopped -> error("Can't restore: $opened")
        }
        val (current, modified) = downloadRaw(session.client)
        val kept = snapshot.records.map { it.getValue("id").jsonPrimitive.content }.toSet()
        val bodies = snapshot.records.map { record ->
            val id = record.getValue("id").jsonPrimitive.content
            id to JSONObject().put("id", id).put("payload", record.getValue("payload").jsonPrimitive.content).toString()
        } + current.map { it.getString("id") }.filterNot { it in kept }.map { id -> id to tombstone(session.keys, id) }
        val uploaded = upload(session.client, bodies, modified, limits(session.client))
        store.clear() // Download everything again on the next sync.
        known = null
        Log.w(TAG, "Restored the spaces collection from the snapshot of ${snapshot.savedAt}: ${uploaded.succeeded.size} records")
        uploaded.succeeded.size
    }

    /** Replaces the snapshot with the collection as it is now (debug builds), say before another device's big sync. */
    fun saveSnapshot(auth: SyncAuth): Int = retryingAuth {
        val session = when (val opened = open(auth)) {
            is Opened.Ready -> opened.session
            is Opened.Stopped -> error("Can't take a snapshot: $opened")
        }
        snapshots.replace(downloadRaw(session.client).first)
    }

    private fun <T> retryingAuth(block: () -> T): T = try {
        block()
    } catch (e: SyncAuthException) {
        // Tokens sometimes expire before their stated end; one retry with a new one is enough.
        token = null
        Log.i(TAG, "Token refused, retrying once: ${e.message}")
        block()
    }

    private fun run(
        auth: SyncAuth,
        dryRun: Boolean,
        device: SyncDevice?,
        localChanges: (Map<String, ZenRecord>) -> List<ZenChange>,
    ): ZenSyncResult {
        val session = when (val opened = open(auth)) {
            is Opened.Ready -> opened.session
            is Opened.Stopped -> return opened.result
        }
        var mirror = session.mirror
        val outgoing = localChanges(mirror.records)

        // Download what changed on the server.
        val records = mirror.records.toMutableMap()
        val incoming = mutableSetOf<String>()
        var lastModified = mirror.lastModified
        val serverModified = session.serverModified
        if (serverModified == null) {
            incoming += records.keys
            records.clear()
            lastModified = null
        } else {
            if (lastModified != null && isNewerTimestamp(lastModified, serverModified)) {
                Log.i(TAG, "The spaces collection was emptied on the server; downloading everything")
                incoming += records.keys
                records.clear()
                lastModified = null
            }
            if (lastModified == null || isNewerTimestamp(serverModified, lastModified)) {
                val modified = download(session.client, session.keys, since = lastModified) { id, record ->
                    incoming += id
                    if (record == null) records.remove(id) else records[id] = record
                }
                lastModified = modified ?: serverModified
            }
        }

        // Upload the phone's changes, except where Zen changed the same record.
        val held = outgoing.count { it.id in incoming }
        var toSend = outgoing.filterNot { it.id in incoming }
        val deletions = toSend.count { it.record == null }
        if (deletions > MAX_DELETIONS_PER_SYNC) {
            Log.w(TAG, "Not deleting $deletions records in one sync; that looks like lost data, not a choice")
            toSend = toSend.filter { it.record != null }
        }
        var sent = 0
        var unsent = toSend.size
        if (toSend.isNotEmpty()) {
            toSend.forEach { Log.i(TAG, "${if (dryRun) "Would send" else "Sending"} ${describe(it, mirror.records)}") }
            if (!dryRun) {
                snapshots.saveIfMissing { downloadRaw(session.client).first }
                val bodies = toSend.map { change ->
                    change.id to (change.record?.let { record(session.keys, it) } ?: tombstone(session.keys, change.id))
                }
                val uploaded = upload(session.client, bodies, lastModified, limits(session.client))
                for (change in toSend.filter { it.id in uploaded.succeeded }) {
                    if (change.record == null) records.remove(change.id) else records[change.id] = change.record
                }
                lastModified = uploaded.lastModified ?: lastModified
                sent = uploaded.succeeded.size
                unsent = toSend.size - sent
            }
        }

        mirror = mirror.copy(records = records, lastModified = lastModified, lastSynced = clock.millis())
        if (device != null) mirror = withDeviceRecord(session, mirror, device, dryRun)
        store.save(mirror)
        known = mirror.storageUid to mirror.lastModified
        Log.i(TAG, "Synced: ${records.size} records, ${incoming.size} received, $sent sent" + if (held > 0) ", $held held" else "")
        return ZenSyncResult.Synced(mirror, received = incoming.size, sent = sent, unsent = unsent, held = held)
    }

    private class Session(
        val client: StorageClient,
        val keys: KeyBundle,
        val mirror: ZenMirror,
        val serverModified: String?,
        /** crypto/keys decrypted, for other collections' keys. */
        val allKeys: JSONObject,
    )

    /**
     * Puts this phone's record in the account's Sync device list when it's new, changed, or due again (Firefox
     * ignores device records older than a week), and notes it in [mirror]. A failure only waits for the next sync.
     */
    private fun withDeviceRecord(session: Session, mirror: ZenMirror, device: SyncDevice, dryRun: Boolean): ZenMirror {
        val uploadedAt = mirror.deviceUploadedAt
        val due = mirror.deviceRecord != device.fingerprint || uploadedAt == null ||
            clock.millis() - uploadedAt > DEVICE_REFRESH_MS
        if (!due) return mirror
        if (dryRun) {
            Log.i(TAG, "Would add this phone to the Sync device list as ${device.recordId}")
            return mirror
        }
        return try {
            val bundle = KeyBundle.fromPair(session.allKeys.optJSONObject("collections")?.optJSONArray(CLIENTS))
                ?: KeyBundle.fromPair(session.allKeys.optJSONArray("default"))
                ?: throw SyncCryptoException("crypto/keys has no default key")
            val cleartext = JSONObject()
                .put("id", device.recordId)
                .put("name", device.name)
                .put("type", "mobile")
                .put("commands", JSONArray())
                .put("version", device.version)
                .put("protocols", JSONArray().put(SYNC_PROTOCOL))
                .put("os", "Android")
                .put("appPackage", device.appPackage)
                .put("application", "Ensō")
                .put("fxaDeviceId", device.fxaDeviceId)
            val body = JSONObject()
                .put("id", device.recordId)
                .put("payload", bundle.encrypt(cleartext.toString()).toString())
                .put("ttl", DEVICE_RECORD_TTL_SECONDS)
            val response = session.client.expect("POST", "storage/$CLIENTS", "[$body]")
            val succeeded = runCatching { JSONObject(response.body).optJSONArray("success") }.getOrNull()
            if (succeeded == null || (0 until succeeded.length()).none { succeeded.optString(it) == device.recordId }) {
                Log.w(TAG, "The server didn't take this phone's Sync device record")
                return mirror
            }
            Log.i(TAG, "Added this phone to the Sync device list as ${device.recordId}")
            mirror.copy(deviceRecord = device.fingerprint, deviceUploadedAt = clock.millis())
        } catch (e: SyncServerException) {
            Log.w(TAG, "Couldn't add this phone to the Sync device list", e)
            mirror
        }
    }

    private sealed interface Opened {
        class Ready(val session: Session) : Opened

        data class Stopped(val result: ZenSyncResult) : Opened
    }

    /** Gets a token, checks meta/global and crypto/keys, and loads the mirror (reset if the server was reset). */
    @Suppress("ReturnCount")
    private fun open(auth: SyncAuth): Opened {
        val syncKeys = KeyBundle.fromSyncKey(auth.syncKey)
        val token = currentToken(auth)
        val client = StorageClient(http, token, clock) { seconds -> backoffUntil = clock.millis() + seconds * MILLIS }
        var mirror = store.load()
        if (mirror.storageUid != token.uid) mirror = ZenMirror(storageUid = token.uid)

        val info = JSONObject(client.expect("GET", "info/collections").body)
        if (!info.has("meta") || !info.has("crypto")) return Opened.Stopped(ZenSyncResult.NotSetUp)

        val meta = client.expect("GET", "storage/meta/global", allowed = setOf(HTTP_NOT_FOUND))
        if (meta.status == HTTP_NOT_FOUND) return Opened.Stopped(ZenSyncResult.NotSetUp)
        val metaPayload = JSONObject(JSONObject(meta.body).getString("payload"))
        if (metaPayload.optInt("storageVersion") != STORAGE_VERSION) return Opened.Stopped(ZenSyncResult.NotSetUp)
        val entry = metaPayload.optJSONObject("engines")?.optJSONObject(COLLECTION)
        val version = entry?.optInt("version") ?: 0
        val syncId = entry?.optString("syncID")?.ifEmpty { null }
        if (version > ENGINE_VERSION) return Opened.Stopped(ZenSyncResult.NeedsUpdate(version))
        if (version < ENGINE_VERSION || syncId == null) return Opened.Stopped(ZenSyncResult.NotTurnedOn)
        if (syncId != mirror.syncId) {
            Log.i(TAG, "The spaces collection was set up or reset elsewhere; downloading everything")
            mirror = ZenMirror(storageUid = token.uid, syncId = syncId)
        }

        val keysResponse = client.expect("GET", "storage/crypto/keys", allowed = setOf(HTTP_NOT_FOUND))
        if (keysResponse.status == HTTP_NOT_FOUND) return Opened.Stopped(ZenSyncResult.NotSetUp)
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
        return Opened.Ready(Session(client, bundle, mirror, timestampOf(info.opt(COLLECTION)), keys))
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
        var skipped = 0
        val modified = pages(client, since) { bso ->
            val id = bso.optString("id").ifEmpty { return@pages }
            runCatching { parse(id, keys.decrypt(JSONObject(bso.getString("payload")))) }
                .onSuccess { onRecord(id, it) }
                .onFailure { skipped++ }
        }
        if (skipped > 0) Log.w(TAG, "Skipped $skipped records that couldn't be read")
        return modified
    }

    /** Every record of the collection as the server stores it (still encrypted), and the collection's time. */
    private fun downloadRaw(client: StorageClient): Pair<List<JSONObject>, String?> {
        val all = mutableListOf<JSONObject>()
        val modified = pages(client, since = null) { all += it }
        return all to modified
    }

    /** Pages through the collection's records newer than [since]; returns the collection's server time. */
    private fun pages(client: StorageClient, since: String?, onRecord: (JSONObject) -> Unit): String? {
        var modified: String? = null
        var offset: String? = null
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
            for (index in 0 until items.length()) items.optJSONObject(index)?.let(onRecord)
            offset = response.header("X-Weave-Next-Offset")
        } while (offset != null)
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

    /** The upload body of a record: `{id, payload}` with the encrypted `{id, kind, data}`. */
    private fun record(keys: KeyBundle, record: ZenRecord): String {
        val cleartext = JsonObject(mapOf("id" to json(record.id), "kind" to json(record.kind), "data" to record.data))
        return JSONObject().put("id", record.id).put("payload", keys.encrypt(cleartext.toString()).toString()).toString()
    }

    private fun tombstone(keys: KeyBundle, id: String): String {
        val cleartext = JSONObject().put("id", id).put("deleted", true).toString()
        return JSONObject().put("id", id).put("payload", keys.encrypt(cleartext).toString()).toString()
    }

    private class Uploaded(val succeeded: Set<String>, val lastModified: String?)

    /**
     * Uploads [bodies] (id to upload JSON) in as few requests as the server allows. Several requests use the server's
     * batch mode, so other devices see all of them or none. [since] guards against changes made meanwhile: the server
     * answers 412 ([SyncConflictException]) and the next sync downloads them first.
     */
    private fun upload(client: StorageClient, bodies: List<Pair<String, String>>, since: String?, limits: UploadLimits): Uploaded {
        val chunks = chunks(bodies.filter { (id, body) ->
            (body.length <= limits.maxRecordPayloadBytes).also { if (!it) Log.w(TAG, "Record $id is too large to upload") }
        }, limits)
        val succeeded = mutableSetOf<String>()
        var lastModified: String? = null
        var ifUnmodifiedSince = since
        var batchId: String? = null
        var batched = chunks.size > 1
        chunks.forEachIndexed { index, chunk ->
            val path = when {
                !batched -> "storage/$COLLECTION"
                batchId == null -> "storage/$COLLECTION?batch=true"
                else -> "storage/$COLLECTION?batch=${URLEncoder.encode(batchId, "UTF-8")}" +
                    if (index == chunks.lastIndex) "&commit=true" else ""
            }
            val response = client.expect("POST", path, chunk.joinToString(",", "[", "]") { it.second }, ifUnmodifiedSince)
            val result = runCatching { JSONObject(response.body) }.getOrDefault(JSONObject())
            result.optJSONArray("success")?.let { array -> (0 until array.length()).forEach { succeeded += array.getString(it) } }
            result.optJSONObject("failed")?.keys()?.forEach { Log.w(TAG, "The server refused $it") }
            if (batched && response.status == HTTP_ACCEPTED) {
                batchId = batchId ?: result.optString("batch").ifEmpty { null }
            } else {
                // Committed, or a server without batches: each request is applied on its own.
                batched = false
                lastModified = response.header("X-Last-Modified") ?: timestampOf(result.opt("modified"))
                ifUnmodifiedSince = lastModified
            }
        }
        return Uploaded(succeeded, lastModified)
    }

    private fun chunks(items: List<Pair<String, String>>, limits: UploadLimits): List<List<Pair<String, String>>> {
        val chunks = mutableListOf<MutableList<Pair<String, String>>>()
        var bytes = 0
        for (item in items) {
            val current = chunks.lastOrNull()
            val size = item.second.length
            if (current == null || current.size >= limits.maxPostRecords || bytes + size > limits.maxPostBytes) {
                chunks += mutableListOf(item)
                bytes = size
            } else {
                current += item
                bytes += size
            }
        }
        return chunks
    }

    private class UploadLimits(val maxPostRecords: Int, val maxPostBytes: Int, val maxRecordPayloadBytes: Int)

    private fun limits(client: StorageClient): UploadLimits {
        val config = client.request("GET", "info/configuration").takeIf { it.isSuccess }
            ?.let { runCatching { JSONObject(it.body) }.getOrNull() }
        return UploadLimits(
            maxPostRecords = config?.optInt("max_post_records", DEFAULT_POST_RECORDS) ?: DEFAULT_POST_RECORDS,
            maxPostBytes = minOf(
                config?.optInt("max_post_bytes", DEFAULT_POST_BYTES) ?: DEFAULT_POST_BYTES,
                config?.optInt("max_request_bytes", DEFAULT_POST_BYTES) ?: DEFAULT_POST_BYTES,
            ),
            maxRecordPayloadBytes = config?.optInt("max_record_payload_bytes", DEFAULT_RECORD_BYTES)
                ?: DEFAULT_RECORD_BYTES,
        )
    }

    /** One log line about a change: what kind of record, and which fields differ from the server's copy. */
    private fun describe(change: ZenChange, base: Map<String, ZenRecord>): String {
        val before = base[change.id]
        val after = change.record ?: return "deletion of ${before?.kind ?: "record"} ${change.id}"
        if (before == null) return "new ${after.kind} ${change.id}"
        val fields = (before.data.keys + after.data.keys).filter { before.data[it] != after.data[it] }
        return "${after.kind} ${change.id}: ${fields.joinToString()}"
    }

    companion object {
        private const val TAG = "ZenSync"

        /** Name of the collection, and of Zen's sync engine in meta/global. */
        const val COLLECTION = "spaces"

        /** Version of the records, matching Zen's `ZenSpacesSyncEngine.version`. */
        const val ENGINE_VERSION = 3

        /** Firefox's device list, where this phone keeps a record so Zen counts it. */
        private const val CLIENTS = "clients"

        /** Sync's storage protocol, which device records list. */
        private const val SYNC_PROTOCOL = "1.5"

        /** How long the server keeps the device record without a refresh: 180 days, as Firefox's own. */
        private const val DEVICE_RECORD_TTL_SECONDS = 15_552_000L

        /** Firefox treats device records older than 7 days as gone, so this refreshes after 6. */
        private const val DEVICE_REFRESH_MS = 6 * 24 * 60 * 60 * 1000L

        /** More deletions than this in one sync look like lost data rather than closed tabs. */
        private const val MAX_DELETIONS_PER_SYNC = 10

        private const val STORAGE_VERSION = 5
        private const val PAGE_SIZE = 1000
        private const val MILLIS = 1000L
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_NOT_FOUND = 404
        private const val DEFAULT_POST_RECORDS = 100
        private const val DEFAULT_POST_BYTES = 2 * 1024 * 1024
        private const val DEFAULT_RECORD_BYTES = 256 * 1024
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** The whole encrypted collection as it was before the phone first wrote to it. */
@Serializable
data class ZenSnapshot(val savedAt: Long, val records: List<JsonObject>)

/** Keeps the [ZenSnapshot], taken once, before the first write, so the collection can be put back. */
class ZenSnapshotStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "zen-spaces-snapshot.json"))
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): ZenSnapshot? =
        try {
            json.decodeFromString<ZenSnapshot>(file.readText())
        } catch (_: FileNotFoundException) {
            null
        }

    @Synchronized
    internal fun saveIfMissing(records: () -> List<JSONObject>) {
        if (file.baseFile.exists()) return
        val snapshot = ZenSnapshot(System.currentTimeMillis(), records().map { Json.parseToJsonElement(it.toString()).jsonObject })
        file.writeText(json.encodeToString(snapshot))
        Log.i("ZenSync", "Saved a snapshot of ${snapshot.records.size} records before the first write")
    }

    /** Replaces the snapshot with [records], the whole collection as it is now. Returns how many it holds. */
    @Synchronized
    internal fun replace(records: List<JSONObject>): Int {
        val snapshot = ZenSnapshot(System.currentTimeMillis(), records.map { Json.parseToJsonElement(it.toString()).jsonObject })
        file.writeText(json.encodeToString(snapshot))
        Log.i("ZenSync", "Saved a new snapshot of ${snapshot.records.size} records")
        return snapshot.records.size
    }
}
