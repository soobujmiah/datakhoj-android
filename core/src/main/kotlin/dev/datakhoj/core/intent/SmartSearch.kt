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

package dev.datakhoj.core.intent

import dev.datakhoj.core.provider.*

/**
 * The single-search-box brain.
 *
 * ```
 * user types  ->  IntentParser  ->  QueryIntent  ->  provider selection  ->  results
 * ```
 *
 * The user never picks a source. They type "500 mp3 arijit singh" and
 * DataKhoj works out it means AUDIO, finds every registered provider that
 * serves audio, queries them concurrently, merges and ranks.
 *
 * If nothing is registered for the detected kind, [plan] says so explicitly
 * rather than silently returning nothing — the user learns *why* and can
 * install a provider pack for it.
 */
class SmartSearch(
    private val parser: IntentParser = IntentParser(),
    private val registry: ProviderRegistry = ProviderRegistry,
    private val llm: LlmAssist? = null,
) {

    /** What will happen, before it happens. Drives the confirmation UI. */
    data class Plan(
        val intent: QueryIntent,
        val providers: List<SearchProvider>,
        val fallbackProviders: List<SearchProvider> = emptyList(),
    ) {
        val hasSources: Boolean get() = providers.isNotEmpty() || fallbackProviders.isNotEmpty()
        val needsConfirmation: Boolean get() = intent.isAmbiguous || !hasSources

        fun explain(): String = buildString {
            appendLine(intent.describe())
            if (intent.reasoning.isNotEmpty()) {
                appendLine()
                intent.reasoning.forEach { appendLine("  · $it") }
            }
            appendLine()
            when {
                providers.isNotEmpty() ->
                    appendLine("Sources: ${providers.joinToString(", ") { it.displayName }}")
                fallbackProviders.isNotEmpty() ->
                    appendLine(
                        "No dedicated ${intent.kind.label.lowercase()} source installed — " +
                            "falling back to ${fallbackProviders.joinToString(", ") { it.displayName }}"
                    )
                else ->
                    appendLine(
                        "No source can serve ${intent.kind.label}. " +
                            "Add a provider for it in Settings → Sources."
                    )
            }
        }.trimEnd()
    }

    /** Parse and choose sources without executing anything. */
    suspend fun plan(
        input: String,
        enabled: Set<String>? = null,
        limitOverride: Int? = null,
    ): Plan {
        var intent = parser.parse(input)

        // Consult an LLM only when the deterministic parser is unsure and the
        // user has configured one. Absence degrades quality, never function.
        val assist = llm
        if (intent.isAmbiguous && assist != null) {
            intent = runCatching { assist.refine(input, intent) }.getOrDefault(intent)
        }

        val query = intent.toQuery(limitOverride)
        val exact = registry.candidates(query, enabled)
            .filter { intent.kind in it.kinds }
            .sortedByDescending { it.trust.ordinal }

        // Anything that can at least do a generic web lookup.
        val fallback = if (exact.isEmpty()) {
            registry.candidates(query.copy(kinds = setOf(DataKind.WEB)), enabled)
        } else emptyList()

        return Plan(intent, exact, fallback)
    }

    /** Plan, then execute. */
    suspend fun search(
        input: String,
        http: HttpClient,
        enabled: Set<String>? = null,
        limitOverride: Int? = null,
        onError: ((SearchProvider, Throwable) -> Unit)? = null,
    ): Pair<Plan, List<SearchResult>> {
        val plan = plan(input, enabled, limitOverride)
        val use = plan.providers.ifEmpty { plan.fallbackProviders }
        if (use.isEmpty()) return plan to emptyList()

        val query = plan.intent.toQuery(limitOverride)
        val out = mutableListOf<SearchResult>()
        for (p in use) {
            try {
                out += p.search(query, http)
            } catch (t: Throwable) {
                onError?.invoke(p, t)
            }
        }
        val ranked = rank(ProviderRegistry.dedupe(out), plan.intent)
        return plan to ranked.take(query.limit)
    }

    /**
     * Order results by usefulness for this specific intent:
     * downloadable first, then matching the requested format, then trust.
     */
    fun rank(results: List<SearchResult>, intent: QueryIntent): List<SearchResult> =
        results.sortedWith(
            compareByDescending<SearchResult> { it.isDownloadable }
                .thenByDescending { r ->
                    intent.formats.isNotEmpty() &&
                        r.format?.lowercase() in intent.formats.map { it.lowercase() }
                }
                .thenByDescending { r -> r.kind == intent.kind }
                .thenByDescending { r -> titleOverlap(r.title, intent.subject) }
        )

    private fun titleOverlap(title: String, subject: String): Int {
        if (subject.isBlank()) return 0
        val want = subject.lowercase().split(' ').filter { it.length > 2 }.toSet()
        if (want.isEmpty()) return 0
        val have = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        return want.count { it in have }
    }
}

/** Default when the query names no count. Providers page until they reach it. */
const val DEFAULT_RESULT_LIMIT = 50

/**
 * Bridge an intent into the provider-layer query object.
 *
 * A count parsed from the query ("500 mp3") always wins over the default, and
 * nothing here caps it — providers page until they satisfy the limit or run
 * out of results.
 */
fun QueryIntent.toQuery(limitOverride: Int? = null): SearchQuery = SearchQuery(
    text = subject.ifBlank { raw },
    kinds = setOf(kind),
    limit = limitOverride ?: count ?: DEFAULT_RESULT_LIMIT,
    filters = buildMap {
        site?.let { put("site", it) }
        if (formats.isNotEmpty()) put("formats", formats.joinToString(","))
    },
)
