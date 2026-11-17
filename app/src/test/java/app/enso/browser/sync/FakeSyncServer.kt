package app.enso.browser.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.net.URLDecoder
import java.util.Base64

/**
 * A Mozilla account's sync storage in memory, with a "spaces" collection: the token server and the storage requests
 * [ZenSpacesEngine] makes, with real encryption. The `zen…` functions change the collection as Zen's desktop would.
 */
internal class FakeSyncServer : SyncHttp {
    /** Counts the account's sets of collection keys; a password reset makes new ones. */
    private var keyGeneration = 0
    private val encryptionKey get() = ByteArray(KEY_SIZE) { (it + 11 + keyGeneration).toByte() }
    private val hmacKey get() = ByteArray(KEY_SIZE) { (it * 3 + 5 + keyGeneration).toByte() }
    private val collectionKeys get() = KeyBundle(encryptionKey, hmacKey)

    /** What the account hands the phone to sync with. */
    val auth = SyncAuth(
        kid = "kid",
        accessToken = "access-token",
        syncKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(2 * KEY_SIZE) { (it * 7 + 3).toByte() }),
        tokenServerUrl = TOKEN_SERVER,
    )

    /** [hidden] is the record of a payload the phone can't read. */
    private class Stored(val payload: String, val modified: BigDecimal, val hidden: ZenRecord? = null)

    private val stored = LinkedHashMap<String, Stored>()
    private var clock = BigDecimal("1700000000.00")
    private val setUpAt = clock
    private var keysModified = clock
    private var spacesModified: BigDecimal? = null

    /** The storage user the token server names. Another one means another account, or storage that was moved. */
    var uid = 1

    /** Whether a device has turned Zen's spaces sync on for the account: its engine is in meta/global. */
    var turnedOn = true

    /** How many requests the phone has made. */
    var requests = 0

    /** The ids of the device records the phone put in the account's Sync device list. */
    val deviceUploads = mutableListOf<String>()

    /** Runs once, as the phone's next device record arrives and before it's stored: throw from it to lose the request. */
    var duringNextDeviceUpload: (() -> Unit)? = null

    /** The storage server refuses the token on the phone's next device record. */
    var refuseNextDeviceUpload = false

    /** The collection's sync id in meta/global; a new one means a device reset the collection. */
    var syncId = "spaces-sync-id"

    /** The ids the phone uploaded, one list per request (deletions included). */
    val phoneUploads = mutableListOf<List<String>>()

    /** Runs once, while the phone next downloads records: something happening on the phone as a sync runs. */
    var duringNextDownload: (() -> Unit)? = null

    /** Runs once, just before the phone's next upload arrives: Zen getting its own upload in first, say. */
    var beforeNextUpload: (() -> Unit)? = null

    /** Runs once, while the phone next uploads records. */
    var duringNextUpload: (() -> Unit)? = null

    /** The connection drops once the next upload is stored, so the phone never hears that it went through. */
    var loseNextUploadAnswer = false

    /** Runs after each of the phone's uploads has been stored, to check what the server holds at that moment. */
    var afterEachUpload: (() -> Unit)? = null

    /** Zen uploads [records] and deletes [deleted], all in one sync. */
    fun zenUploads(vararg records: ZenRecord, deleted: List<String> = emptyList()) {
        val at = tick()
        for (record in records) stored[record.id] = Stored(encrypted(record), at)
        for (id in deleted) stored[id] = Stored(tombstone(id), at)
        spacesModified = at
    }

    /** Zen uploads [records] in a way the phone can't read: the payload doesn't match the account's keys. */
    fun zenUploadsUnreadable(vararg records: ZenRecord) {
        val at = tick()
        val otherKeys = KeyBundle(ByteArray(KEY_SIZE) { (it + 99).toByte() }, ByteArray(KEY_SIZE) { (it * 5 + 1).toByte() })
        for (record in records) stored[record.id] = Stored(encrypted(record, otherKeys), at, hidden = record)
        spacesModified = at
    }

    /** A record uploaded with [zenUploadsUnreadable] can be read after all, with nothing else about it changed. */
    fun becomesReadable(id: String) {
        val unreadable = stored.getValue(id)
        stored[id] = Stored(encrypted(unreadable.hidden!!), unreadable.modified)
    }

    /** The ids whose newest version the phone can't read. */
    fun unreadableIds(): List<String> = stored.filterValues { it.hidden != null }.keys.sorted()

    /** The unreadable records the phone wrote over, which it never should. */
    val overwrittenUnreadable = mutableListOf<String>()

    /**
     * The account's password was reset: new keys, and the storage wiped, as Firefox does. Zen then uploads its
     * collection again under the new keys, which [zenUploads] stands for.
     */
    fun passwordReset() {
        keyGeneration++
        keysModified = tick()
        syncId = "spaces-sync-id-$keyGeneration"
        stored.clear()
        spacesModified = null
    }

    /** The record the server holds for [id], or null when there's none or it's deleted. */
    fun record(id: String): ZenRecord? = stored[id]?.takeIf { it.hidden == null }?.let { read(id, it.payload) }

    /** Every record the server holds that isn't deleted. */
    fun records(): Map<String, ZenRecord> = stored.keys.mapNotNull { id -> record(id)?.let { id to it } }.toMap()

    /** Whether the server holds a deletion for [id]. */
    fun isDeleted(id: String): Boolean = stored[id]?.let { it.hidden == null && read(id, it.payload) == null } ?: false

    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): SyncResponse {
        requests++
        if (url == "$TOKEN_SERVER/1.0/sync/1.5") {
            val token = JSONObject().put("id", "hawk-id").put("key", "hawk-key").put("api_endpoint", STORAGE)
                .put("uid", uid).put("duration", TOKEN_SECONDS)
            return respond(OK, token.toString())
        }
        check(url.startsWith("$STORAGE/")) { "Unexpected request to $url" }
        val path = url.removePrefix("$STORAGE/").substringBefore('?')
        val query = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        return when {
            method == "GET" && path == "info/collections" -> {
                val info = JSONObject().put("meta", setUpAt).put("crypto", setUpAt)
                spacesModified?.let { info.put("spaces", it) }
                respond(OK, info.toString())
            }
            method == "GET" && path == "info/configuration" -> respond(OK, "{}")
            method == "GET" && path == "storage/meta/global" -> {
                val engines = JSONObject()
                if (turnedOn) engines.put("spaces", JSONObject().put("version", ZenSpacesEngine.ENGINE_VERSION).put("syncID", syncId))
                val payload = JSONObject().put("storageVersion", STORAGE_VERSION).put("syncID", "global-sync-id").put("engines", engines)
                respond(OK, JSONObject().put("id", "global").put("payload", payload.toString()).toString())
            }
            method == "GET" && path == "storage/crypto/keys" -> {
                val encoder = Base64.getEncoder()
                val keys = JSONObject()
                    .put("default", JSONArray().put(encoder.encodeToString(encryptionKey)).put(encoder.encodeToString(hmacKey)))
                    .put("collections", JSONObject())
                val payload = KeyBundle.fromSyncKey(auth.syncKey).encrypt(keys.toString()).toString()
                respond(OK, JSONObject().put("id", "keys").put("payload", payload).toString(), lastModified = keysModified)
            }
            method == "GET" && path == "storage/spaces" -> {
                duringNextDownload?.also { duringNextDownload = null }?.invoke()
                val newer = query["newer"]?.toBigDecimal()
                val ids = query["ids"]?.split(',')?.map { URLDecoder.decode(it, "UTF-8") }
                val items = JSONArray()
                stored.entries.filter { (id, bso) -> (newer == null || bso.modified > newer) && (ids == null || id in ids) }.sortedBy { it.value.modified }.forEach { (id, bso) ->
                    items.put(JSONObject().put("id", id).put("modified", bso.modified).put("payload", bso.payload))
                }
                respond(OK, items.toString(), lastModified = spacesModified)
            }
            method == "POST" && path == "storage/spaces" -> {
                beforeNextUpload?.also { beforeNextUpload = null }?.invoke()
                val since = headers["X-If-Unmodified-Since"]?.toBigDecimal()
                val modified = spacesModified
                if (since != null && modified != null && modified > since) return respond(PRECONDITION_FAILED, "")
                duringNextUpload?.also { duringNextUpload = null }?.invoke()
                val at = tick()
                val items = JSONArray(body)
                val ids = (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    item.getString("id").also {
                        if (stored[it]?.hidden != null) overwrittenUnreadable += it
                        stored[it] = Stored(item.getString("payload"), at)
                    }
                }
                spacesModified = at
                phoneUploads += ids
                afterEachUpload?.invoke()
                if (loseNextUploadAnswer) {
                    loseNextUploadAnswer = false
                    throw IOException("Connection lost")
                }
                val answer = JSONObject().put("success", JSONArray(ids)).put("failed", JSONObject()).put("modified", at)
                respond(OK, answer.toString(), lastModified = at)
            }
            method == "POST" && path == "storage/clients" -> {
                if (refuseNextDeviceUpload) {
                    refuseNextDeviceUpload = false
                    return respond(UNAUTHORIZED, "")
                }
                duringNextDeviceUpload?.also { duringNextDeviceUpload = null }?.invoke()
                val items = JSONArray(body)
                val ids = (0 until items.length()).map { items.getJSONObject(it).getString("id") }
                deviceUploads += ids
                respond(OK, JSONObject().put("success", JSONArray(ids)).put("failed", JSONObject()).put("modified", tick()).toString())
            }
            else -> respond(NOT_FOUND, "")
        }
    }

    private fun respond(status: Int, body: String, lastModified: BigDecimal? = null): SyncResponse {
        val headers = mutableMapOf("X-Weave-Timestamp" to clock.toPlainString())
        lastModified?.let { headers["X-Last-Modified"] = it.toPlainString() }
        return SyncResponse(status, headers, body)
    }

    private fun tick(): BigDecimal = (clock + BigDecimal("1.00")).also { clock = it }

    private fun encrypted(record: ZenRecord, keys: KeyBundle = collectionKeys): String {
        val cleartext = JsonObject(mapOf("id" to JsonPrimitive(record.id), "kind" to JsonPrimitive(record.kind), "data" to record.data))
        return keys.encrypt(cleartext.toString()).toString()
    }

    private fun tombstone(id: String): String =
        collectionKeys.encrypt(JSONObject().put("id", id).put("deleted", true).toString()).toString()

    private fun read(id: String, payload: String): ZenRecord? {
        val cleartext = Json.parseToJsonElement(collectionKeys.decrypt(JSONObject(payload))).jsonObject
        if (cleartext["deleted"]?.jsonPrimitive?.booleanOrNull == true) return null
        return ZenRecord(id, cleartext.getValue("kind").jsonPrimitive.content, cleartext.getValue("data").jsonObject)
    }

    private companion object {
        const val TOKEN_SERVER = "https://token.test"
        const val STORAGE = "https://storage.test/1.5/1"
        const val KEY_SIZE = 32
        const val STORAGE_VERSION = 5
        const val TOKEN_SECONDS = 3600
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val PRECONDITION_FAILED = 412
    }
}
