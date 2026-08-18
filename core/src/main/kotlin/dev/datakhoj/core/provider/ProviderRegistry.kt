package dev.datakhoj.core.provider

/**
 * Central registry — the single place a new data source gets plugged in.
 *
 * ```kotlin
 * ProviderRegistry.register(MyNewProvider())
 * val hits = ProviderRegistry.searchAll(SearchQuery("bohemian rhapsody", setOf(DataKind.AUDIO)), http)
 * ```
 *
 * The UI never references a concrete provider; it asks the registry what
 * exists and what each one can do. Adding a tool later is one file plus one
 * `register()` call.
 */
object ProviderRegistry {

    private val providers = linkedMapOf<String, SearchProvider>()

    fun register(p: SearchProvider): ProviderRegistry = apply { providers[p.id] = p }
    fun unregister(id: String) { providers.remove(id) }
    fun all(): List<SearchProvider> = providers.values.toList()
    fun get(id: String): SearchProvider? = providers[id]

    /** Providers advertising a given kind. */
    fun forKind(kind: DataKind): List<SearchProvider> =
        providers.values.filter { kind in it.kinds }

    /** Every kind at least one registered provider can serve. */
    fun availableKinds(): List<DataKind> =
        providers.values.flatMap { it.kinds }.distinct().sortedBy { it.label }

    /** Providers that would actually run for this query. */
    fun candidates(query: SearchQuery, enabled: Set<String>? = null): List<SearchProvider> =
        providers.values.filter { p ->
            (enabled == null || p.id in enabled) && p.canHandle(query)
        }

    /**
     * Run every candidate and merge.
     *
     * One provider failing must never fail the search — errors are collected
     * and reported alongside whatever did work, so a dead source degrades the
     * result set instead of destroying it.
     */
    suspend fun searchAll(
        query: SearchQuery,
        http: HttpClient,
        enabled: Set<String>? = null,
        onError: ((SearchProvider, Throwable) -> Unit)? = null,
    ): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        for (p in candidates(query, enabled)) {
            try {
                out += p.search(query, http)
            } catch (t: Throwable) {
                onError?.invoke(p, t)
            }
        }
        return dedupe(out).take(query.limit)
    }

    /** Canonical form of a URL for duplicate detection. */
    internal fun normalizeKey(raw: String): String =
        raw.substringBefore('#')
            .trim()
            .lowercase()
            .removeSuffix("/")

    /** Drop duplicate hits, preferring the entry carrying a download link. */
    fun dedupe(results: List<SearchResult>): List<SearchResult> {
        val byKey = linkedMapOf<String, SearchResult>()
        for (r in results) {
            // Key on the canonical page URL so the same item found by two
            // providers collapses even when only one supplied a download link.
            val key = normalizeKey(r.url.ifBlank { r.magnet ?: r.directUrl ?: "" })
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = r
            } else if (!existing.isDownloadable && r.isDownloadable) {
                byKey[key] = r
            }
        }
        return byKey.values.toList()
    }
}
