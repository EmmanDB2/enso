package app.enso.browser.toolbar

import android.net.Uri
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Request
import mozilla.components.concept.fetch.isSuccess
import org.json.JSONArray

/**
 * Google's suggestions for what's typed in bottom search (Google is the search engine, as in [AddressInput]). Asked
 * privately, with no cookies or cache, and only while `Settings.searchSuggestions` is on.
 */
class SearchSuggestions(private val client: Client) {
    /** Suggestions for [query], or none if the request fails. */
    suspend fun fetch(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request(
                url = "https://www.google.com/complete/search?client=firefox&q=" + Uri.encode(query),
                connectTimeout = 3L to TimeUnit.SECONDS,
                readTimeout = 3L to TimeUnit.SECONDS,
                useCaches = false,
                private = true,
            )
            client.fetch(request).use { response ->
                if (!response.isSuccess) return@use emptyList()
                // ["query", ["suggestion", …], …]
                val list = JSONArray(response.body.string(Charsets.UTF_8)).getJSONArray(1)
                List(list.length()) { list.getString(it) }
            }
        }.getOrDefault(emptyList())
    }
}
