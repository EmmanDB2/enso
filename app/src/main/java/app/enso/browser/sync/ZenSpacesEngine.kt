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
     *
     * The engine saves neither mirror itself, because the mirror must never get ahead of the phone's model: the next
     * sync would compare an old model with a new mirror and upload the difference, undoing what Zen changed.
     *
     * @property mirror Zen's collection after this sync. Save it once the phone's model has taken it in.
     * @property unapplied What to save when the model doesn't take [mirror] in: the mirror the model reflects, plus
     *   only what this sync uploaded, still at the old server time. The next sync then downloads Zen's changes again
     *   and still counts them as Zen's.
     * @property refusedDeletions Above 0 when the phone's changes held more deletions than one sync may send. None
     *   of its changes went then; this is how many deletions there were.
     */
    data class Synced(
        val mirror: ZenMirror,
        val unapplied: ZenMirror,
        val received: Int,
        val sent: Int,
        val unsent: Int,
        val held: Int = 0,
        val refusedDeletions: Int = 0,
    ) : ZenSyncResult

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
 * worked out against the [ZenMirror] (the server state the phone's model last took in), and Zen's version wins where
 * both sides changed the same record. A sync returns the new mirror for the caller to [save] once the model reflects
 * it. Blocking; call off the main thread.
 */
internal class ZenSpacesEngine(
    private val http: SyncHttp,
    private val store: MirrorStore,
    private val snapshots: SnapshotStore,
    private val clock: SyncClock = SyncClock(),
) {
    @Volatile
    private var token: SyncToken? = null

    /** Local time before which the servers asked not to sync again. */
    @Volatile
    var backoffUntil: Long = 0L
        private set

    /** Forgets everything about the server, for a sign out. The snapshots stay. */
    fun reset() {
        token = null
        known = null
        snapshotChecked = null
        store.clear()
    }

    /** The account, sync id and keys that the snapshot from before the first write is known to be of. */
    @Volatile
    private var snapshotChecked: List<String?>? = null

    /**
     * Syncs. [localChanges] works out the phone's changes against the mirror it's given. With [dryRun] they're only
     * logged, never uploaded.
     *
     * [applied] is the state of the collection the phone's model reflects. When the saved mirror is of another state
     * (the model was put back from a backup, or one of the two files was saved and the other wasn't), the mirror is
     * dropped: this sync downloads everything and uploads nothing.
     */
    fun sync(
        auth: SyncAuth,
        dryRun: Boolean,
        device: SyncDevice?,
        applied: ZenStamp?,
        localChanges: (ZenMirror) -> List<ZenChange>,
    ): ZenSyncResult = retryingAuth { run(auth, dryRun, device, applied, localChanges) }

    /**
     * Saves what a sync returned: [ZenSyncResult.Synced.mirror] once the phone's model reflects it and is saved
     * itself, or [ZenSyncResult.Synced.unapplied] when the model didn't take it in.
     */
    fun save(mirror: ZenMirror) = store.save(mirror)

    /** The storage user and the collection's server time that the last sync left, for [serverChanged]. */
    @Volatile
    private var known: Pair<String?, String?>? = null

    /**
     * Whether the spaces collection changed on the server since the last sync: one small request (and a token now
     * and then), cheap enough to ask every few seconds while the app is open. A collection that has nothing in it,
     * on the server and on the phone, hasn't changed.
     */
    fun serverChanged(auth: SyncAuth): Boolean = retryingAuth {
        val token = currentToken(auth)
        val client = StorageClient(http, token, clock) { seconds -> backoffUntil = clock.millis() + seconds * MILLIS }
        val (uid, lastModified) = known ?: store.load().let { (it.storageUid to it.lastModified).also { pair -> known = pair } }
        if (uid != token.uid) return@retryingAuth true
        val info = JSONObject(client.expect("GET", "info/collections").body)
        val server = timestampOf(info.opt(COLLECTION))
        if (server == null || lastModified == null) return@retryingAuth server != lastModified
        isNewerTimestamp(server, lastModified) || isNewerTimestamp(lastModified, server)
    }

    /**
     * Puts the collection back as a snapshot has it: the one from before the first write, or the kept one called
     * [name]. It uploads the snapshot's records as they are and deletes every other record, so it only goes ahead
     * with a snapshot of this account that the account's keys can read: records of another account, or from before
     * a password reset, would arrive unreadable while everything readable was deleted. What's there now is kept
     * first, so a restore can itself be put back. Returns the records written.
     */
    fun restoreSnapshot(auth: SyncAuth, name: String? = null): Int = retryingAuth {
        val snapshot = snapshots.load(name) ?: error("There's no snapshot to restore")
        val session = when (val opened = open(auth)) {
            is Opened.Ready -> opened.session
            is Opened.Stopped -> error("Can't restore: $opened")
        }
        check(snapshot.records.isNotEmpty()) { "Can't restore: the snapshot is empty" }
        check(snapshot.storageUid == null || snapshot.storageUid == session.mirror.storageUid) {
            "Can't restore: the snapshot is of another account, or from before this one's storage was reset"
        }
        check(snapshot.readsWith(session.keys)) { "Can't restore: the account's keys can't read the snapshot's records" }
        if (snapshot.storageUid != null && !snapshot.isOf(session.mirror)) {
            Log.w(TAG, "The snapshot is from before the collection was reset; its records still read, so restoring it")
        }
        val (current, modified) = downloadRaw(session.client)
        snapshots.keep("before-restore-${modified ?: clock.millis()}") { snapshotOf(session, current, "before a restore") }
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

    /**
     * Replaces the snapshot with the collection as it is now (debug builds), say before another device's big sync.
     * The one it replaces is kept aside.
     */
    fun saveSnapshot(auth: SyncAuth): Int = retryingAuth {
        val session = when (val opened = open(auth)) {
            is Opened.Ready -> opened.session
            is Opened.Stopped -> error("Can't take a snapshot: $opened")
        }
        val snapshot = snapshotOf(session, downloadRaw(session.client).first, "saved by hand")
        snapshots.load()?.let { replaced -> snapshots.keep("replaced-${replaced.savedAt}") { replaced } }
        snapshots.save(snapshot)
        snapshotChecked = session.mirror.identity
        Log.i(TAG, "Saved a new snapshot of ${snapshot.records.size} records")
        snapshot.records.size
    }

    /**
     * Makes sure the snapshot from before the first write is of this account's collection under the keys it has now,
     * taking one if not, before the phone writes. One of another account, or from before the collection or its keys
     * were reset, is kept aside: it couldn't be restored here, and this account needs its own.
     */
    private fun snapshotBeforeWriting(session: Session) {
        val identity = session.mirror.identity
        if (snapshotChecked == identity) return
        val saved = snapshots.load()
        when {
            saved != null && saved.isOf(session.mirror) -> Unit
            // From before snapshots said whose they were: it's this account's if the account's keys read it.
            saved != null && saved.storageUid == null && saved.readsWith(session.keys) ->
                snapshots.save(saved.copy(storageUid = identity[0], syncId = identity[1], keysModified = identity[2]))
            else -> {
                val snapshot = snapshotOf(session, downloadRaw(session.client).first, "before the first write")
                if (saved != null) snapshots.keep("replaced-${saved.savedAt}") { saved }
                snapshots.save(snapshot)
                Log.i(TAG, "Saved a snapshot of ${snapshot.records.size} records before the first write")
            }
        }
        snapshotChecked = identity
    }

    /**
     * Keeps [mirror], the collection as the phone knew it, as a snapshot called [name], before [what] happens to it.
     * The records are encrypted again as the server holds them, so it restores like any other. Failing to keep one
     * never stops a sync.
     */
    private fun keepSnapshotOf(session: Session, mirror: ZenMirror, name: String, what: String) {
        try {
            val kept = snapshots.keep(name) {
                snapshotOf(session, mirror.records.values.map { JSONObject(record(session.keys, it)) }, "before $what")
            }
            if (kept) Log.i(TAG, "Kept a snapshot of the ${mirror.records.size} records from before $what, as $name")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't keep a snapshot from before $what", e)
        }
    }

    private fun snapshotOf(session: Session, records: List<JSONObject>, reason: String) = ZenSnapshot(
        savedAt = clock.millis(),
        records = records.map { json.parseToJsonElement(it.toString()).jsonObject },
        storageUid = session.mirror.storageUid,
        syncId = session.mirror.syncId,
        keysModified = session.mirror.keysModified,
        reason = reason,
    )

    private val ZenMirror.identity: List<String?> get() = listOf(storageUid, syncId, keysModified)

    private fun ZenSnapshot.isOf(mirror: ZenMirror): Boolean = listOf(storageUid, syncId, keysModified) == mirror.identity

    /** Whether [keys] read every record of this snapshot. */
    private fun ZenSnapshot.readsWith(keys: KeyBundle): Boolean = records.all { record ->
        runCatching { keys.decrypt(JSONObject(record.getValue("payload").jsonPrimitive.content)) }.isSuccess
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
        applied: ZenStamp?,
        localChanges: (ZenMirror) -> List<ZenChange>,
    ): ZenSyncResult {
        val session = when (val opened = open(auth) { it.stamp == (applied ?: ZenStamp(null, null)) }) {
            is Opened.Ready -> opened.session
            is Opened.Stopped -> return opened.result
        }
        // The server state the phone's model reflects: what its changes are worked out against.
        val base = session.mirror
        val outgoing = localChanges(base)

        // Download what changed on the server.
        val records = base.records.toMutableMap()
        val unread = base.unread.toMutableSet()
        val gone = base.gone.toMutableSet()
        var lastModified = base.lastModified
        val serverModified = session.serverModified
        if (serverModified == null) {
            records.clear()
            unread.clear()
            gone.clear()
            lastModified = null
        } else {
            if (lastModified != null && isNewerTimestamp(lastModified, serverModified)) {
                Log.i(TAG, "The spaces collection was emptied on the server; downloading everything")
                records.clear()
                unread.clear()
                gone.clear()
                lastModified = null
            }
            val take: (String, ZenRecord?) -> Unit = { id, record ->
                unread -= id
                if (record == null) {
                    records.remove(id)
                    gone += id
                } else {
                    records[id] = record
                    gone -= id
                }
            }
            // First what couldn't be read before, in case it can be now; a newer version comes with the rest.
            if (unread.isNotEmpty()) unread -= downloadAgain(session.client, session.keys, unread.toList(), take)
            if (lastModified == null || isNewerTimestamp(serverModified, lastModified)) {
                val modified = download(session.client, session.keys, since = lastModified, onUnread = { unread += it }, take)
                lastModified = modified ?: serverModified
            }
        }
        // What Zen changed: the records that now differ from the mirror's copies. A download can also bring back
        // what the phone itself uploaded (after a sync its model didn't take in, the mirror stays at the old server
        // time); the mirror has those already, so they don't count.
        val incoming = (base.records.keys + records.keys).filterTo(mutableSetOf()) { base.records[it] != records[it] }

        // Upload the phone's changes, except where Zen changed the same record, whether or not that could be read.
        // A folder's deletion also waits while anything could be in the folder, because Zen deletes the tabs it has
        // in a folder along with it: a record this download brought says it's in there, or a record couldn't be
        // read, and that one could be anywhere.
        val waits = { change: ZenChange ->
            change.id in incoming || change.id in unread ||
                (change.record == null && base.records[change.id]?.kind == KIND_FOLDER &&
                    (unread.isNotEmpty() || records.values.any { it.isIn(change.id) }))
        }
        val held = outgoing.count(waits)
        val toSend = outgoing.filterNot(waits)
        // More deletions than a person makes between two syncs look like a model that lost its tabs. Then nothing of
        // this sync goes up: not the deletions, and not the parents that no longer list what would be deleted.
        val refusedDeletions = toSend.count { it.record == null }.takeIf { it > MAX_DELETIONS_PER_SYNC } ?: 0
        if (refusedDeletions > 0) {
            Log.w(TAG, "Not sending anything: $refusedDeletions deletions in one sync look like lost data, not a choice")
        }
        val sending = !dryRun && refusedDeletions == 0
        // Several records going at once is when a copy of the collection is worth having, whoever deletes them. The
        // phone's own deletions have their guard; nothing guards the ones that arrive, and Zen has had bugs that
        // emptied spaces. The mirror is the collection as it was just before.
        val deletions = maxOf(base.records.keys.count { it !in records }, toSend.count { it.record == null })
        if (deletions >= SNAPSHOT_AT_DELETIONS) {
            keepSnapshotOf(session, base, "before-deletions-${base.lastModified}", "$deletions deletions")
        }
        var sent = 0
        var unsent = toSend.size
        var uploadedChanges = emptyList<ZenChange>()
        var uploadedAt: String? = null
        if (toSend.isNotEmpty()) {
            toSend.forEach { Log.i(TAG, "${if (sending) "Sending" else "Would send"} ${describe(it, base.records)}") }
            if (sending) {
                snapshotBeforeWriting(session)
                val bodies = toSend.map { change ->
                    change.id to (change.record?.let { record(session.keys, it) } ?: tombstone(session.keys, change.id))
                }
                val uploaded = upload(session.client, bodies, lastModified, limits(session.client))
                uploadedChanges = toSend.filter { it.id in uploaded.succeeded }
                // A server that gave no time for the upload still has to leave a mark the stamp can tell apart.
                if (uploadedChanges.isNotEmpty()) uploadedAt = uploaded.lastModified ?: "at ${clock.millis()}"
                lastModified = uploaded.lastModified ?: lastModified
                sent = uploaded.succeeded.size
                unsent = toSend.size - sent
            }
        }

        val now = clock.millis()
        var mirror = base.copy(
            records = records.withChanges(uploadedChanges),
            lastModified = lastModified,
            ownUploadAt = null,
            lastSynced = now,
            unread = unread,
        ).withGone(gone, uploadedChanges)
        var unapplied = base.copy(
            records = base.records.withChanges(uploadedChanges),
            ownUploadAt = uploadedAt ?: base.ownUploadAt,
            lastSynced = now,
        ).withGone(base.gone, uploadedChanges)
        if (device != null) {
            mirror = withDeviceRecord(session, mirror, device, dryRun)
            unapplied = unapplied.copy(deviceRecord = mirror.deviceRecord, deviceUploadedAt = mirror.deviceUploadedAt)
        }
        // The server time this sync saw, whichever mirror ends up saved: only a later change is news to the check.
        known = mirror.storageUid to mirror.lastModified
        Log.i(TAG, "Synced: ${mirror.records.size} records, ${incoming.size} received, $sent sent" + if (held > 0) ", $held held" else "")
        return ZenSyncResult.Synced(mirror, unapplied, received = incoming.size, sent, unsent, held, refusedDeletions)
    }

    /** These records with [changes] put over them. */
    private fun Map<String, ZenRecord>.withChanges(changes: List<ZenChange>): Map<String, ZenRecord> {
        if (changes.isEmpty()) return this
        val result = toMutableMap()
        for (change in changes) {
            if (change.record == null) result.remove(change.id) else result[change.id] = change.record
        }
        return result
    }

    /** This mirror noting the deleted ids: [gone], and the deletions among the uploaded [changes]. */
    private fun ZenMirror.withGone(gone: Set<String>, changes: List<ZenChange>): ZenMirror =
        copy(gone = (gone + changes.filter { it.record == null }.map { it.id }).filterTo(mutableSetOf()) { it !in records })

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
     * ignores device records older than a week), and notes it in [mirror]. A failure only waits for the next sync,
     * whatever it is: the phone's changes to the spaces are on the server by now, and a sync that failed here would
     * leave the mirror without them.
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
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't add this phone to the Sync device list", e)
            // The next sync asks for a new token, as it would have if this had failed the sync.
            if (e is SyncAuthException) token = null
            mirror
        }
    }

    private sealed interface Opened {
        class Ready(val session: Session) : Opened

        data class Stopped(val result: ZenSyncResult) : Opened
    }

    /**
     * Gets a token, checks meta/global and crypto/keys, and loads the mirror (reset if the server was reset). A saved
     * mirror that isn't the one the phone's model [reflects] is dropped: it only keeps what it says of the device
     * record, which has nothing to do with the model.
     */
    @Suppress("ReturnCount")
    private fun open(auth: SyncAuth, reflects: (ZenMirror) -> Boolean = { true }): Opened {
        val syncKeys = KeyBundle.fromSyncKey(auth.syncKey)
        val token = currentToken(auth)
        val client = StorageClient(http, token, clock) { seconds -> backoffUntil = clock.millis() + seconds * MILLIS }
        var mirror = store.load()
        // What the phone knew of the collection, when it's found to have been reset on the server.
        var reset: ZenMirror? = null
        if (!reflects(mirror)) {
            Log.w(TAG, "The phone's spaces don't reflect the saved mirror; downloading everything and uploading nothing")
            mirror = ZenMirror(storageUid = mirror.storageUid, deviceRecord = mirror.deviceRecord, deviceUploadedAt = mirror.deviceUploadedAt)
        }
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
            // A mirror that starts over (dropped above, or new) has no sync id yet either. The device record is in
            // another collection, so what the mirror says of it still holds.
            if (mirror.syncId != null) {
                Log.i(TAG, "The spaces collection was set up or reset elsewhere; downloading everything")
                reset = mirror
            }
            mirror = ZenMirror(
                storageUid = token.uid,
                syncId = syncId,
                deviceRecord = mirror.deviceRecord,
                deviceUploadedAt = mirror.deviceUploadedAt,
            )
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
            reset = reset ?: mirror
            mirror = ZenMirror(storageUid = token.uid, syncId = syncId)
        }
        mirror = mirror.copy(keysModified = keysModified)
        val session = Session(client, bundle, mirror, timestampOf(info.opt(COLLECTION)), keys)
        // A reset can be another device starting the collection over with less in it, so what was there is kept.
        reset?.takeIf { it.records.isNotEmpty() }?.let { keepSnapshotOf(session, it, "before-reset-${it.lastModified}", "the collection was reset") }
        return Opened.Ready(session)
    }

    private fun currentToken(auth: SyncAuth): SyncToken =
        token?.takeIf { it.expiresAt > clock.millis() } ?: TokenServerClient(http, clock).fetch(auth).also { token = it }

    /**
     * Downloads records changed since [since], passing each to [onRecord] (null for a deletion), and the id of each
     * one that couldn't be read to [onUnread].
     */
    private fun download(
        client: StorageClient,
        keys: KeyBundle,
        since: String?,
        onUnread: (String) -> Unit,
        onRecord: (String, ZenRecord?) -> Unit,
    ): String? {
        var skipped = 0
        val modified = pages(client, since) { bso ->
            val id = bso.optString("id").ifEmpty { return@pages }
            runCatching { parse(id, keys.decrypt(JSONObject(bso.getString("payload")))) }
                .onSuccess { onRecord(id, it) }
                .onFailure {
                    skipped++
                    onUnread(id)
                }
        }
        if (skipped > 0) Log.w(TAG, "Skipped $skipped records that couldn't be read")
        return modified
    }

    /**
     * Asks for the records [ids] again, which couldn't be read at an earlier sync, and passes the ones that can be
     * now to [onRecord]. Returns the ids the server no longer holds anything for, which are done with: the phone
     * keeps what it had of them. If the request fails, they all wait for the next sync.
     */
    private fun downloadAgain(
        client: StorageClient,
        keys: KeyBundle,
        ids: List<String>,
        onRecord: (String, ZenRecord?) -> Unit,
    ): Set<String> {
        val asked = ids.take(MAX_IDS_PER_REQUEST)
        val query = "full=1&ids=" + asked.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
        val items = try {
            JSONArray(client.expect("GET", "storage/$COLLECTION?$query").body)
        } catch (e: SyncServerException) {
            Log.w(TAG, "Couldn't ask again for ${asked.size} records that couldn't be read", e)
            return emptySet()
        }
        val gone = asked.toMutableSet()
        for (index in 0 until items.length()) {
            val bso = items.optJSONObject(index) ?: continue
            val id = bso.optString("id").ifEmpty { continue }
            gone -= id
            runCatching { parse(id, keys.decrypt(JSONObject(bso.getString("payload")))) }.onSuccess { onRecord(id, it) }
        }
        return gone
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
        const val MAX_DELETIONS_PER_SYNC = 10

        /** A sync that brings or sends this many deletions keeps a snapshot of the collection from before them. */
        const val SNAPSHOT_AT_DELETIONS = 5

        /** The most ids the storage server takes in one request. */
        private const val MAX_IDS_PER_REQUEST = 100

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

/**
 * The whole encrypted collection at one moment, as the server held it, so it can be put back.
 *
 * @property storageUid The storage user it's of, with [syncId] and [keysModified] (the collection's sync id, and the
 *   time of the account's keys). Null in a snapshot from before snapshots said whose they were.
 * @property reason Why it was taken, for whoever looks at the file.
 */
@Serializable
data class ZenSnapshot(
    val savedAt: Long,
    val records: List<JsonObject>,
    val storageUid: String? = null,
    val syncId: String? = null,
    val keysModified: String? = null,
    val reason: String? = null,
)

/**
 * Where the engine keeps its [ZenSnapshot]s: the one from before the phone's first write to the account's collection,
 * and a few more kept aside by name.
 */
internal interface SnapshotStore {
    /** The snapshot from before the first write, or the kept one called [name]. */
    fun load(name: String? = null): ZenSnapshot?

    /** Saves [snapshot] as the one from before the first write. */
    fun save(snapshot: ZenSnapshot)

    /**
     * Keeps [snapshot] aside as [name] unless one of that name is there, and drops the oldest beyond a few. Returns
     * whether it was kept.
     */
    fun keep(name: String, snapshot: () -> ZenSnapshot): Boolean
}

/**
 * Keeps the snapshots in files: `zen-spaces-snapshot.json` from before the first write, and the ones kept aside in
 * `zen-snapshots/`, the latest [MAX_KEPT] of them.
 */
internal class ZenSnapshotStore(context: Context) : SnapshotStore {
    private val first = AtomicFile(File(context.filesDir, "zen-spaces-snapshot.json"))
    private val keptDir = File(context.filesDir, "zen-snapshots")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun load(name: String?): ZenSnapshot? {
        val file = if (name == null) first else AtomicFile(keptFile(name) ?: return null)
        return try {
            json.decodeFromString<ZenSnapshot>(file.readText())
        } catch (_: FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w("ZenSync", "Couldn't read the snapshot ${name.orEmpty()}", e)
            null
        }
    }

    @Synchronized
    override fun save(snapshot: ZenSnapshot) = first.writeText(json.encodeToString(snapshot))

    @Synchronized
    override fun keep(name: String, snapshot: () -> ZenSnapshot): Boolean {
        val file = keptFile(name) ?: return false
        if (file.exists()) return false
        keptDir.mkdirs()
        AtomicFile(file).writeText(json.encodeToString(snapshot()))
        keptDir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MAX_KEPT).forEach { it.delete() }
        return true
    }

    /** The file of the kept snapshot [name], or null for a name that can't be one (it can come from a command). */
    private fun keptFile(name: String): File? = name.takeIf { NAME.matches(it) }?.let { File(keptDir, "$it.json") }

    private companion object {
        const val MAX_KEPT = 5
        val NAME = Regex("[A-Za-z0-9._-]+")
    }
}
