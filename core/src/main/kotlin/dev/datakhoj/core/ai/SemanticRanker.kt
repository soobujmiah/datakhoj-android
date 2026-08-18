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

package dev.datakhoj.core.ai

import dev.datakhoj.core.provider.SearchResult

/**
 * Semantic dedup and ranking — the single best use of the NPU in this app.
 *
 * ## The problem it solves
 *
 * String dedup collapses `example.com/a` and `example.com/a/`. It does nothing
 * for these, which are obviously the same file to a human:
 *
 * ```
 * "Bohemian Rhapsody - Queen (Official Video) [1080p]"
 * "Queen — Bohemian Rhapsody 1080p HD"
 * "bohemian_rhapsody_queen_1080p.mkv"
 * ```
 *
 * Merging those needs *meaning*, which is exactly what a sentence embedding
 * gives you. On a Hexagon NPU a ~25 MB INT8 embedder handles a 50-result page
 * in a few milliseconds — invisible to the user.
 *
 * ## Degradation, not failure
 *
 * Pass `embedder = null` and everything here still works using token overlap.
 * Worse quality, identical behaviour. This is deliberate: the feature must
 * never become a reason the app can't return results.
 */
class SemanticRanker(private val embedder: Embedder? = null) {

    val isSemantic: Boolean get() = embedder != null

    /**
     * Collapse results that mean the same thing.
     *
     * @param threshold cosine similarity above which two hits are "the same".
     *   0.86 is tuned to merge title variants without merging different
     *   episodes of the same series.
     */
    suspend fun dedupe(
        results: List<SearchResult>,
        threshold: Float = 0.86f,
    ): List<SearchResult> {
        if (results.size < 2) return results

        val kept = mutableListOf<SearchResult>()
        val vectors = mutableListOf<FloatArray?>()

        val embeddings: List<FloatArray>? = embedder?.let { e ->
            runCatching { e.embedAll(results.map { fingerprint(it) }) }.getOrNull()
        }

        results.forEachIndexed { i, r ->
            val vec = embeddings?.getOrNull(i)?.let { Vectors.normalize(it) }
            var duplicateOf = -1

            for (j in kept.indices) {
                val same = if (vec != null && vectors[j] != null) {
                    Vectors.cosine(vec, vectors[j]!!) >= threshold
                } else {
                    tokenSimilarity(fingerprint(r), fingerprint(kept[j])) >= 0.82f
                }
                if (same) { duplicateOf = j; break }
            }

            if (duplicateOf < 0) {
                kept += r
                vectors += vec
            } else {
                // Keep whichever variant is more useful to the user.
                kept[duplicateOf] = preferBetter(kept[duplicateOf], r)
            }
        }
        return kept
    }

    /**
     * Rank by meaning rather than keyword overlap, so "cheap phone" can match
     * a listing titled "budget smartphone".
     */
    suspend fun rankByMeaning(
        results: List<SearchResult>,
        query: String,
    ): List<SearchResult> {
        val e = embedder ?: return results
        if (results.isEmpty() || query.isBlank()) return results

        return runCatching {
            val qv = Vectors.normalize(e.embed(query))
            val rv = e.embedAll(results.map { fingerprint(it) })
            results.mapIndexed { i, r ->
                r to Vectors.cosine(qv, Vectors.normalize(rv[i]))
            }
                .sortedByDescending { it.second }
                .map { it.first }
        }.getOrDefault(results)
    }

    /**
     * Score how well each result matches, for a relevance bar in the UI.
     * Returns 0f..1f per result, in input order.
     */
    suspend fun scores(results: List<SearchResult>, query: String): List<Float> {
        val e = embedder ?: return results.map { tokenSimilarity(fingerprint(it), query) }
        return runCatching {
            val qv = Vectors.normalize(e.embed(query))
            e.embedAll(results.map { fingerprint(it) })
                .map { ((Vectors.cosine(qv, Vectors.normalize(it)) + 1f) / 2f) }
        }.getOrDefault(results.map { tokenSimilarity(fingerprint(it), query) })
    }

    // -- helpers -----------------------------------------------------------

    /** The text that best represents a result for similarity purposes. */
    private fun fingerprint(r: SearchResult): String = buildString {
        append(r.title)
        r.author?.let { append(" ").append(it) }
        r.format?.let { append(" ").append(it) }
    }.trim()

    /** Between two duplicates, keep the one the user can actually use. */
    private fun preferBetter(a: SearchResult, b: SearchResult): SearchResult = when {
        a.isDownloadable != b.isDownloadable -> if (a.isDownloadable) a else b
        (a.sizeBytes ?: 0) != (b.sizeBytes ?: 0) ->
            if ((a.sizeBytes ?: 0) >= (b.sizeBytes ?: 0)) a else b
        a.title.length >= b.title.length -> a
        else -> b
    }

    /** Jaccard overlap on word tokens — the no-model fallback. */
    internal fun tokenSimilarity(a: String, b: String): Float {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val inter = ta.intersect(tb).size.toFloat()
        val union = ta.union(tb).size.toFloat()
        return if (union == 0f) 0f else inter / union
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length > 2 && it !in STOP }
            .toSet()

    private companion object {
        val STOP = setOf(
            "the", "and", "for", "with", "official", "video", "audio", "hd",
            "full", "new", "free", "download", "watch", "online",
        )
    }
}
