package dev.datakhoj.app.net

import dev.datakhoj.core.provider.*
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Whole-web search via DuckDuckGo's HTML endpoint. No API key.
 *
 * Serves as the universal fallback: when no specialised provider exists for a
 * detected kind, SmartSearch routes here rather than returning nothing.
 *
 * Ad/tracker redirects are filtered — a live test of the Python engine once
 * returned a 1,200-character sponsored redirect as result #1 of 5.
 */
class DuckDuckGoProvider : SearchProvider {
    override val id = "duckduckgo"
    override val displayName = "DuckDuckGo (whole web)"
    override val kinds = DataKind.entries.toSet()   // universal fallback
    override val trust = ProviderTrust.PUBLIC_INDEX

    private val adMarkers = listOf(
        "duckduckgo.com/y.js", "ad_provider=", "ad_domain=",
        "bing.com/aclick", "googleadservices.com", "doubleclick.net",
    )

    override suspend fun search(query: SearchQuery, http: HttpClient): List<SearchResult> {
        val q = buildString {
            append(query.text)
            query.filters["site"]?.let { append(" site:").append(it) }
            query.filters["formats"]?.split(",")?.firstOrNull()?.let {
                if (it.isNotBlank()) append(" filetype:").append(it)
            }
        }
        val html = http.postForm(
            "https://html.duckduckgo.com/html/",
            mapOf("q" to q),
        )
        val doc = Jsoup.parse(html, "https://duckduckgo.com")
        val out = mutableListOf<SearchResult>()

        for (el in doc.select("div.result, div.web-result")) {
            if (out.size >= query.limit) break
            val a = el.selectFirst("a.result__a") ?: continue
            var href = a.absUrl("href").ifBlank { a.attr("href") }
            href = unwrap(href)
            if (href.isBlank() || adMarkers.any { href.contains(it, true) }) continue
            if (!href.startsWith("http")) continue

            val title = a.text().trim().ifBlank { href }
            val snippet = el.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            val ext = Regex("""\.([a-z0-9]{2,5})(?:\?|#|$)""", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.get(1)?.lowercase()

            out += SearchResult(
                title = title,
                url = href,
                kind = query.kinds.firstOrNull() ?: DataKind.WEB,
                provider = id,
                snippet = snippet,
                directUrl = if (ext != null && ext !in setOf("html", "htm", "php", "aspx")) href else null,
                format = ext?.uppercase(),
            )
        }
        return out
    }

    /** DuckDuckGo wraps outbound links as /l/?uddg=<encoded>. */
    private fun unwrap(url: String): String {
        if (!url.contains("uddg=")) return url
        return runCatching {
            val v = url.substringAfter("uddg=").substringBefore("&")
            URLDecoder.decode(v, "UTF-8")
        }.getOrDefault(url)
    }
}
