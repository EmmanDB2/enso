package app.enso.browser.spaces

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.core.util.readText
import androidx.core.util.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException

/** The spaces model in memory, saved to a JSON file shortly after each change. */
@OptIn(FlowPreview::class)
class SpacesStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "spaces.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SpacesState> = _state.asStateFlow()

    init {
        scope.launch { _state.drop(1).debounce(SAVE_DELAY_MS).collect(::save) }
    }

    /** Every change also drops splits it broke (see [withValidSplits]), so no edit has to tidy them up itself. */
    fun update(transform: (SpacesState) -> SpacesState) = _state.update { transform(it).withValidSplits() }

    /** Saves right away, for when the app may be about to be killed. */
    fun flush() = save(_state.value)

    private fun load(): SpacesState =
        try {
            json.decodeFromJsonElement<SpacesState>(migrate(json.parseToJsonElement(file.readText()))).withValidSplits()
        } catch (_: FileNotFoundException) {
            SpacesState.initial()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read spaces; starting fresh", e)
            SpacesState.initial()
        }

    @Synchronized
    private fun save(state: SpacesState) = file.writeText(json.encodeToString(state))

    /** Folders used to hold only `tabs`; now they hold `items` (tabs and subfolders), as Zen's do. */
    private fun migrate(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            val folderTabs = element["tabs"].takeIf { element["type"]?.jsonPrimitive?.content == "folder" }
            if (folderTabs is JsonArray && "items" !in element) {
                val items = JsonArray(folderTabs.map { buildJsonObject { put("type", "tab"); put("tab", it) } })
                JsonObject(element - "tabs" + ("items" to items))
            } else {
                JsonObject(element.mapValues { migrate(it.value) })
            }
        }
        is JsonArray -> JsonArray(element.map(::migrate))
        else -> element
    }

    private companion object {
        const val TAG = "SpacesStore"
        const val SAVE_DELAY_MS = 500L
    }
}
