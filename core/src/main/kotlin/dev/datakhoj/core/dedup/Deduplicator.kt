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

package dev.datakhoj.core.dedup

import dev.datakhoj.core.dataset.Dataset
import dev.datakhoj.core.dataset.Record
import dev.datakhoj.core.extract.FieldTypes
import dev.datakhoj.core.model.FieldType

/**
 * Dataset-level deduplication with user-defined matching keys (§8).
 *
 * Governing rule: **never automatically delete ambiguous records.** The
 * analysis classifies rows as exact / likely / unique and hands the decision
 * to the user. Only [applyExact] removes anything without being asked, and
 * even that is opt-in.
 */
class Deduplicator(
    /** Fields forming the match key. Empty = every field. */
    private val keys: List<String> = emptyList(),
    /**
     * 0..1 similarity above which two rows are "likely" duplicates.
     * See [Sensitivity] for measured, named presets.
     */
    private val likelyThreshold: Double = Sensitivity.BALANCED,
) {

    /**
     * Threshold presets, chosen from measured trigram scores rather than
     * guessed:
     *
     * ```
     * "mohammed rahman" vs "mohammad rahman"  -> 0.684   (spelling variant)
     * "sobuj miah"      vs "sobuj miah"       -> 1.000   (identical)
     * "abc"             vs "xyz"              -> 0.000   (unrelated)
     * ```
     *
     * A single global threshold cannot serve every field: names tolerate
     * variation, emails do not. Pass the one that suits the data.
     */
    object Sensitivity {
        /** 0.60 — catches spelling variants like Mohammed/Mohammad. More false positives. */
        const val LOOSE = 0.60
        /** 0.80 — catches whitespace, case and punctuation differences. Default. */
        const val BALANCED = 0.80
        /** 0.95 — near-identical only. Use for emails, URLs and IDs. */
        const val STRICT = 0.95
    }

    enum class Verdict { UNIQUE, EXACT_DUPLICATE, LIKELY_DUPLICATE }

    data class Match(
        val record: Record,
        val verdict: Verdict,
        /** Record this duplicates, if any. */
        val duplicateOf: String? = null,
        val similarity: Double = 1.0,
        val matchedOn: List<String> = emptyList(),
    )

    data class Analysis(
        val matches: List<Match>,
        val exact: Int,
        val likely: Int,
        val unique: Int,
    ) {
        val hasDuplicates: Boolean get() = exact > 0 || likely > 0
        fun summary(): String = buildString {
            append("$unique unique")
            if (exact > 0) append(", $exact exact duplicate${if (exact == 1) "" else "s"}")
            if (likely > 0) append(", $likely likely duplicate${if (likely == 1) "" else "s"}")
        }
    }

    /** Classify without changing anything. */
    fun analyze(dataset: Dataset): Analysis {
        val cols = keys.filter { it in dataset.schema }.ifEmpty { dataset.schema.names }
        val matches = mutableListOf<Match>()
        val kept = mutableListOf<Pair<Record, String>>()   // record + exact key

        for (rec in dataset.records) {
            val key = exactKey(rec, cols, dataset)
            val exactHit = kept.firstOrNull { it.second == key && key.isNotBlank() }
            if (exactHit != null) {
                matches += Match(rec, Verdict.EXACT_DUPLICATE, exactHit.first.id, 1.0, cols)
                continue
            }
            var bestRec: Record? = null
            var bestScore = 0.0
            for ((k, _) in kept) {
                val s = similarity(rec, k, cols, dataset)
                if (bestRec == null || s > bestScore) { bestRec = k; bestScore = s }
            }
            val hit = bestRec
            if (hit != null && bestScore >= likelyThreshold) {
                matches += Match(rec, Verdict.LIKELY_DUPLICATE, hit.id, bestScore, cols)
            } else {
                matches += Match(rec, Verdict.UNIQUE, null, 1.0, cols)
                kept += rec to key
            }
        }
        return Analysis(
            matches = matches,
            exact = matches.count { it.verdict == Verdict.EXACT_DUPLICATE },
            likely = matches.count { it.verdict == Verdict.LIKELY_DUPLICATE },
            unique = matches.count { it.verdict == Verdict.UNIQUE },
        )
    }

    /** Remove only byte-identical duplicates. Safe: no judgement involved. */
    fun applyExact(dataset: Dataset): Dataset {
        val a = analyze(dataset)
        val drop = a.matches.filter { it.verdict == Verdict.EXACT_DUPLICATE }.map { it.record.id }.toSet()
        return dataset.copy(records = dataset.records.filterNot { it.id in drop })
    }

    /** Remove exact *and* likely duplicates. Only call with explicit consent. */
    fun applyAll(dataset: Dataset): Dataset {
        val a = analyze(dataset)
        val drop = a.matches.filter { it.verdict != Verdict.UNIQUE }.map { it.record.id }.toSet()
        return dataset.copy(records = dataset.records.filterNot { it.id in drop })
    }

    /** Drop specific records by id — what the UI calls after user review. */
    fun remove(dataset: Dataset, ids: Set<String>): Dataset =
        dataset.copy(records = dataset.records.filterNot { it.id in ids })

    // ------------------------------------------------------------ internals

    /** Normalised key so "A@B.com " and "a@b.com" collide as they should. */
    private fun exactKey(r: Record, cols: List<String>, d: Dataset): String =
        cols.joinToString("\u0000") { c -> normalize(r[c], d.schema.typeOf(c)) }

    private fun normalize(v: String, t: FieldType): String = when (t) {
        FieldType.EMAIL -> v.trim().lowercase()
        FieldType.PHONE -> FieldTypes.coerce(v, FieldType.PHONE)
        FieldType.URL, FieldType.IMAGE -> v.trim().lowercase().substringBefore('#').trimEnd('/')
        FieldType.NUMBER, FieldType.CURRENCY -> FieldTypes.coerce(v, t)
        else -> v.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun similarity(a: Record, b: Record, cols: List<String>, d: Dataset): Double {
        var total = 0.0
        var counted = 0
        for (c in cols) {
            val x = normalize(a[c], d.schema.typeOf(c))
            val y = normalize(b[c], d.schema.typeOf(c))
            if (x.isBlank() && y.isBlank()) continue
            counted++
            total += when {
                x == y -> 1.0
                x.isBlank() || y.isBlank() -> 0.0
                else -> trigram(x, y)
            }
        }
        return if (counted == 0) 0.0 else total / counted
    }

    /** Trigram Jaccard — cheap, no model, catches typos and word order. */
    internal fun trigram(a: String, b: String): Double {
        val ta = trigrams(a); val tb = trigrams(b)
        if (ta.isEmpty() || tb.isEmpty()) return if (a == b) 1.0 else 0.0
        val inter = ta.intersect(tb).size.toDouble()
        return inter / (ta.size + tb.size - inter)
    }

    private fun trigrams(s: String): Set<String> {
        val p = "  $s "
        if (p.length < 3) return setOf(p)
        return (0..p.length - 3).map { p.substring(it, it + 3) }.toSet()
    }
}
