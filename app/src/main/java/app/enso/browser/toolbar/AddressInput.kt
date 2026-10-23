package app.enso.browser.toolbar

import android.net.Uri
import mozilla.components.support.ktx.util.URLStringUtils

object AddressInput {
    const val BLANK_PAGE = "about:blank"
    private const val SEARCH_URL = "https://www.google.com/search?q="

    /** Turns what the user typed into the URL to load: the URL itself, or a web search. */
    fun toUrl(input: String): String =
        if (URLStringUtils.isURLLike(input)) {
            URLStringUtils.toNormalizedURL(input)
        } else {
            SEARCH_URL + Uri.encode(input)
        }

    /** The domain shown in the address field, or "" for a blank page. */
    fun displayHost(url: String): String = when {
        url.isEmpty() || url == BLANK_PAGE -> ""
        else -> Uri.parse(url).host?.removePrefix("www.")?.ifEmpty { null } ?: url
    }
}
