package dev.datakhoj.core.extract

import dev.datakhoj.core.model.ParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The extraction engine — turns HTML into structured rows.
 *
 * Mirrors Python's `datakhoj/core/extractor.py` exactly; parity is enforced by
 * `spec/conformance/`. Uses Jsoup (pure Java, full CSS selector support, no
 * NDK) which is why this module needs no Android SDK to build or test.
 *
 * Selector contract:
 * ```
 * container: ".product-card"   repeating element; one row per match
 * name:      ".title"          field selectors, resolved INSIDE each container
 * link:      "a@href"          @attr suffix pulls an attribute
 * tags:      ".tag@textall"    join every match with " | "
 * body:      ".c@html"         inner HTML
 * ```
 * `href`/`src`-style attributes are absolutised against the page URL.
 */
class Extractor(private val stripEmptyRows: Boolean = true) {

    companion object {
        private val URL_ATTRS = setOf("href", "src", "data-src", "srcset", "action", "poster")

        /** Keys in a selector map that are plumbing, not output fields. */
        val RESERVED = setOf(
            "container", "pagination", "next", "next_selector",
            "load_more", "load_more_selector", "infinite_scroll",
        )

        /**
         * Split `"a.link@href"` into `("a.link", "href")`. Bare selectors
         * default to `@text`. Only splits on an `@` outside `[...]`, so
         * attribute selectors like `a[href@x]` survive.
         */
        fun parseSelector(raw: String): Pair<String, String> {
            val s = raw.trim()
            if (s.isEmpty()) return "" to "text"
            var depth = 0
            for (i in s.indices.reversed()) {
                when (s[i]) {
                    ']' -> depth++
                    '[' -> depth--
                    '@' -> if (depth == 0) {
                        val sel = s.substring(0, i).trim()
                        val attr = s.substring(i + 1).trim().lowercase().ifEmpty { "text" }
                        return sel to attr
                    }
                }
            }
            return s to "text"
        }
    }

    /** Per-field match accounting, used to explain disappointing results. */
    data class FieldDiagnostic(
        val name: String,
        val selector: String,
        val attr: String,
        var matched: Int = 0,
        var nonEmpty: Int = 0,
    )

    data class Result(
        val rows: MutableList<Map<String, String>> = mutableListOf(),
        val containerSelector: String? = null,
        val containersMatched: Int = 0,
        val fields: MutableMap<String, FieldDiagnostic> = linkedMapOf(),
        val htmlBytes: Int = 0,
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()

        /** Human-readable explanation of what matched and what didn't. */
        fun diagnostic(url: String = ""): String = buildString {
            val where = if (url.isNotBlank()) " for $url" else ""
            appendLine("Extraction report$where: parsed $htmlBytes bytes using jsoup.")
            if (containerSelector != null) {
                appendLine("  container  '$containerSelector' matched $containersMatched element(s)")
                if (containersMatched == 0) {
                    appendLine(
                        "    -> The container selector matched nothing. The page structure " +
                            "likely differs from your job, or the content is rendered by " +
                            "JavaScript (enable JS rendering)."
                    )
                }
            } else {
                appendLine("  container  (none set — whole document treated as one row)")
            }
            if (fields.isNotEmpty()) {
                appendLine("  fields:")
                fields.values.forEach { d ->
                    val status = if (d.nonEmpty > 0) "ok" else "EMPTY"
                    appendLine(
                        "    [${status.padEnd(5)}] ${d.name.padEnd(16)} '${d.selector}'@${d.attr} " +
                            "— matched ${d.matched}, non-empty ${d.nonEmpty}"
                    )
                }
                val dead = fields.values.filter { it.nonEmpty == 0 }.map { it.name }
                if (dead.isNotEmpty() && containersMatched > 0) {
                    appendLine(
                        "    -> ${dead.size} field(s) never produced a value: " +
                            "${dead.joinToString(", ")}. Check those selectors are relative " +
                            "to the container."
                    )
                }
            }
        }.trimEnd()
    }

    fun extract(
        html: String,
        selectors: Map<String, String>,
        baseUrl: String = "",
        fields: List<String>? = null,
        limit: Int? = null,
    ): Result {
        if (html.isEmpty()) throw ParseException("Empty document — nothing to extract.")

        val doc = try {
            if (baseUrl.isNotBlank()) Jsoup.parse(html, baseUrl) else Jsoup.parse(html)
        } catch (e: Exception) {
            throw ParseException("Could not parse document: ${e.message}")
        }

        val containerSel = selectors["container"]?.trim()?.ifBlank { null }

        var fieldMap: Map<String, String> = selectors
            .filterKeys { it !in RESERVED }
            .filterValues { it.isNotBlank() }

        if (fields != null) {
            val ordered = linkedMapOf<String, String>()
            fields.forEach { f -> ordered[f] = fieldMap[f] ?: "" }
            fieldMap = ordered
        }

        val diags = linkedMapOf<String, FieldDiagnostic>()
        fieldMap.forEach { (name, raw) ->
            val (sel, attr) = parseSelector(raw)
            diags[name] = FieldDiagnostic(name, sel, attr)
        }

        val containers: List<Element> =
            if (containerSel != null) doc.select(containerSel).toList() else listOf(doc)

        val result = Result(
            containerSelector = containerSel,
            containersMatched = if (containerSel != null) containers.size else 0,
            fields = diags,
            htmlBytes = html.length,
        )

        if (containerSel != null && containers.isEmpty()) return result

        for (node in containers) {
            if (limit != null && result.rows.size >= limit) break
            val row = extractRow(node, fieldMap, baseUrl, diags)
            if (stripEmptyRows && row.values.none { it.isNotBlank() }) continue
            result.rows.add(row)
        }
        return result
    }

    private fun extractRow(
        node: Element,
        fieldMap: Map<String, String>,
        baseUrl: String,
        diags: Map<String, FieldDiagnostic>,
    ): Map<String, String> {
        val row = linkedMapOf<String, String>()
        for ((name, raw) in fieldMap) {
            if (raw.isBlank()) { row[name] = ""; continue }
            val (sel, attr) = parseSelector(raw)
            val diag = diags[name]

            val targets: List<Element> =
                if (sel.isEmpty()) listOf(node) else node.select(sel).toList()
            if (targets.isNotEmpty()) diag?.matched = (diag?.matched ?: 0) + targets.size

            val value = valueOf(targets, attr, baseUrl)
            if (value.isNotBlank()) diag?.nonEmpty = (diag?.nonEmpty ?: 0) + 1
            row[name] = value
        }
        return row
    }

    private fun valueOf(targets: List<Element>, attr: String, baseUrl: String): String {
        if (targets.isEmpty()) return ""
        val first = targets.first()
        return when (attr) {
            "text", "" -> collapse(first.text())
            "html" -> first.html()
            "textall" -> targets.map { collapse(it.text()) }.filter { it.isNotBlank() }
                .joinToString(" | ")
            else -> {
                // Jsoup's abs: prefix resolves against the document base URI.
                val raw = if (attr in URL_ATTRS && baseUrl.isNotBlank()) {
                    first.absUrl(attr).ifBlank { first.attr(attr) }
                } else {
                    first.attr(attr)
                }
                raw.trim()
            }
        }
    }

    private fun collapse(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
