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
    suspend fun plan(input: String, enabled: Set<String>? = null): Plan {
        var intent = parser.parse(input)

        // Consult an LLM only when the deterministic parser is unsure and the
        // user has configured one. Absence degrades quality, never function.
        val assist = llm
        if (intent.isAmbiguous && assist != null) {
            intent = runCatching { assist.refine(input, intent) }.getOrDefault(intent)
        }

        val query = intent.toQuery()
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
        onError: ((SearchProvider, Throwable) -> Unit)? = null,
    ): Pair<Plan, List<SearchResult>> {
        val plan = plan(input, enabled)
        val use = plan.providers.ifEmpty { plan.fallbackProviders }
        if (use.isEmpty()) return plan to emptyList()

        val query = plan.intent.toQuery()
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

/** Bridge an intent into the provider-layer query object. */
fun QueryIntent.toQuery(): SearchQuery = SearchQuery(
    text = subject.ifBlank { raw },
    kinds = setOf(kind),
    limit = count ?: 50,
    filters = buildMap {
        site?.let { put("site", it) }
        if (formats.isNotEmpty()) put("formats", formats.joinToString(","))
    },
)
