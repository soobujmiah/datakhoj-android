package dev.datakhoj.core.provider.impl

import dev.datakhoj.core.extract.Extractor
import dev.datakhoj.core.provider.*

/**
 * A provider defined entirely by data, not code.
 *
 * This is what makes DataKhoj "unrestricted": the user (or a shipped JSON
 * catalogue) can describe ANY site — a music index, a torrent tracker, a
 * document library — as selectors, and it becomes a first-class search source
 * with no recompilation.
 *
 * ```json
 * { "id":"myindex", "name":"My Index", "kinds":["audio"],
 *   "searchUrl":"https://example.com/search?q={query}&page={page}",
 *   "container":".result",
 *   "fields":{"title":".name","url":"a@href","directUrl":"a.dl@href","sizeText":".size"} }
 * ```
 */
class GenericHtmlProvider(
    override val id: String,
    override val displayName: String,
    override val kinds: Set<DataKind>,
    private val searchUrl: String,
    private val container: String,
    private val fields: Map<String, String>,
    override val trust: ProviderTrust = ProviderTrust.USER_DEFINED,
    private val headers: Map<String, String> = emptyMap(),
) : SearchProvider {

    override suspend fun search(query: SearchQuery, http: HttpClient): List<SearchResult> {
        val url = searchUrl
            .replace("{query}", urlEncode(query.text))
            .replace("{page}", query.page.toString())
        val html = http.getText(url, headers)
        return parse(html, url, query)
    }

    /** Exposed for tests so provider behaviour is verifiable without network. */
    fun parse(html: String, baseUrl: String, query: SearchQuery): List<SearchResult> {
        val selectors = buildMap {
            put("container", container)
            fields.forEach { (k, v) -> put(k, v) }
        }
        val res = Extractor().extract(html, selectors, baseUrl, limit = query.limit)
        val kind = kinds.firstOrNull() ?: DataKind.OTHER
        return res.rows.mapNotNull { row ->
            val title = row["title"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SearchResult(
                title = title,
                url = row["url"].orEmpty().ifBlank { baseUrl },
                kind = kind,
                provider = id,
                snippet = row["snippet"].orEmpty(),
                directUrl = row["directUrl"]?.ifBlank { null },
                magnet = row["magnet"]?.ifBlank { null },
                sizeBytes = row["sizeText"]?.let { parseSize(it) },
                format = row["format"]?.ifBlank { null },
                author = row["author"]?.ifBlank { null },
                extra = row.filterKeys { it !in KNOWN }.filterValues { it.isNotBlank() },
            )
        }
    }

    companion object {
        private val KNOWN = setOf(
            "title","url","snippet","directUrl","magnet","sizeText","format","author"
        )
        private val SIZE = Regex("([\\d.,]+)\\s*(B|KB|MB|GB|TB|KiB|MiB|GiB|TiB)", RegexOption.IGNORE_CASE)

        /** "1.4 GB" -> bytes. Tolerates commas and IEC suffixes. */
        fun parseSize(text: String): Long? {
            val m = SIZE.find(text) ?: return null
            val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2].uppercase().removeSuffix("IB").removeSuffix("B")) {
                "K" -> 1024.0; "M" -> 1024.0*1024; "G" -> 1024.0*1024*1024
                "T" -> 1024.0*1024*1024*1024; else -> 1.0
            }
            return (n * mult).toLong()
        }

        fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
