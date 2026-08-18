/*
 * DataKhoj — a personal, unrestricted universal data collector.
 * Copyright (C) 2026 soobujmiah
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details: <https://www.gnu.org/licenses/>.
 *
 * "DataKhoj" and its logo are trademarks of the copyright holder and are NOT
 * licensed under the AGPL. Forks must use their own name and branding.
 */

package dev.datakhoj.app.net

import dev.datakhoj.core.provider.*
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder

/**
 * Whole-web search via DuckDuckGo's HTML endpoint. No API key.
 *
 * Serves as the universal fallback: when no specialised provider exists for a
 * detected kind, SmartSearch routes here rather than returning nothing.
 *
 * ## Pagination
 *
 * DuckDuckGo's HTML endpoint returns roughly 10–30 results per request. The
 * previous implementation issued a single POST and stopped, so every search
 * appeared capped at ~10 hits regardless of the requested limit.
 *
 * It now follows the `s` (offset) parameter until the requested count is
 * reached, a page yields nothing new, or [MAX_PAGES] is hit. A small delay
 * between pages keeps the load polite — this is an unofficial endpoint.
 *
 * Ad and tracker redirects are filtered out; a live run once returned a
 * 1,200-character sponsored redirect as result #1 of 5.
 */
class DuckDuckGoProvider : SearchProvider {
    override val id = "duckduckgo"
    override val displayName = "DuckDuckGo (whole web)"
    override val kinds = DataKind.entries.toSet()   // universal fallback
    override val trust = ProviderTrust.PUBLIC_INDEX

    private companion object {
        const val ENDPOINT = "https://html.duckduckgo.com/html/"
        /** Hard ceiling so a huge limit cannot hammer the endpoint forever. */
        const val MAX_PAGES = 30
        /** Politeness gap between pages. */
        const val PAGE_DELAY_MS = 600L
        val AD_MARKERS = listOf(
            "duckduckgo.com/y.js", "ad_provider=", "ad_domain=",
            "bing.com/aclick", "googleadservices.com", "doubleclick.net",
        )
        val NON_FILE_EXT = setOf("html", "htm", "php", "aspx", "jsp", "asp", "cgi")
    }

    override suspend fun search(query: SearchQuery, http: HttpClient): List<SearchResult> {
        val q = buildString {
            append(query.text)
            query.filters["site"]?.let { append(" site:").append(it) }
            query.filters["formats"]?.split(",")?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let { append(" filetype:").append(it) }
        }

        val out = LinkedHashMap<String, SearchResult>()   // preserves order, dedupes by url
        var offset = 0
        var page = 0

        while (out.size < query.limit && page < MAX_PAGES) {
            val form = buildMap {
                put("q", q)
                if (offset > 0) {
                    put("s", offset.toString())
                    put("dc", (offset + 1).toString())
                }
            }

            val html = try {
                http.postForm(ENDPOINT, form)
            } catch (e: Exception) {
                // Partial results beat none: keep what earlier pages gave us.
                if (out.isEmpty()) throw e else break
            }

            val doc = Jsoup.parse(html, "https://duckduckgo.com")
            val before = out.size
            harvest(doc, query, out)

            // No new results means we have reached the end of the index.
            if (out.size == before) break

            offset += 30
            page++
            if (out.size < query.limit && page < MAX_PAGES) delay(PAGE_DELAY_MS)
        }

        return out.values.take(query.limit)
    }

    private fun harvest(
        doc: Document,
        query: SearchQuery,
        out: MutableMap<String, SearchResult>,
    ) {
        for (el in doc.select("div.result, div.web-result")) {
            if (out.size >= query.limit) return
            val a = el.selectFirst("a.result__a") ?: continue

            var href = a.absUrl("href").ifBlank { a.attr("href") }
            href = unwrap(href)
            if (href.isBlank() || !href.startsWith("http")) continue
            if (AD_MARKERS.any { href.contains(it, ignoreCase = true) }) continue

            val key = href.substringBefore('#').trimEnd('/').lowercase()
            if (out.containsKey(key)) continue

            val ext = Regex("""\.([a-z0-9]{2,5})(?:\?|#|$)""", RegexOption.IGNORE_CASE)
                .find(href)?.groupValues?.get(1)?.lowercase()

            out[key] = SearchResult(
                title = a.text().trim().ifBlank { href },
                url = href,
                kind = query.kinds.firstOrNull() ?: DataKind.WEB,
                provider = id,
                snippet = el.selectFirst(".result__snippet")?.text()?.trim().orEmpty(),
                directUrl = if (ext != null && ext !in NON_FILE_EXT) href else null,
                format = ext?.uppercase(),
            )
        }
    }

    /** DuckDuckGo wraps outbound links as `/l/?uddg=<encoded>`. */
    private fun unwrap(url: String): String {
        if (!url.contains("uddg=")) return url
        return runCatching {
            URLDecoder.decode(url.substringAfter("uddg=").substringBefore("&"), "UTF-8")
        }.getOrDefault(url)
    }
}
