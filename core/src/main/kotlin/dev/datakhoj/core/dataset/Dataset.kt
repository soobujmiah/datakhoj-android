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

package dev.datakhoj.core.dataset

import dev.datakhoj.core.model.FieldType

/**
 * The keystone data model.
 *
 * A [Dataset] is collected data **independent of any export format**. This is
 * the single most important architectural rule in the product: a CSV is not a
 * dataset, it is one *rendering* of a dataset.
 *
 * ```
 * Dataset "Contacts"
 *   ├── CSV export
 *   ├── XLSX export
 *   ├── JSON export
 *   └── SQLite export
 * ```
 *
 * Because of this the user can export the same collected data repeatedly, in
 * any format, **without scraping again**.
 */

/** One column: its name, type, and provenance. */
data class FieldDef(
    val name: String,
    val type: FieldType = FieldType.TEXT,
    /** Label shown in the UI; defaults to [name]. */
    val label: String = name,
    /** Selector or provider key this came from — kept for diagnostics. */
    val origin: String = "",
    val required: Boolean = false,
) {
    init { require(name.isNotBlank()) { "Field name cannot be blank" } }
}

/** The shape of a dataset. Ordered — column order is deterministic on export. */
data class Schema(val fields: List<FieldDef>) {
    val names: List<String> get() = fields.map { it.name }
    operator fun get(name: String): FieldDef? = fields.firstOrNull { it.name == name }
    fun typeOf(name: String): FieldType = this[name]?.type ?: FieldType.TEXT
    operator fun contains(name: String) = fields.any { it.name == name }

    /** Union of two schemas, preserving left order. Used when merging datasets. */
    fun mergedWith(other: Schema): Schema {
        val out = fields.toMutableList()
        other.fields.forEach { f -> if (f.name !in names) out += f }
        return Schema(out)
    }

    /** Restrict to [keep], preserving the given order. */
    fun select(keep: List<String>): Schema =
        Schema(keep.mapNotNull { n -> this[n] })

    companion object {
        /** Infer a schema from raw rows, preserving first-seen key order. */
        fun infer(rows: List<Map<String, String>>): Schema {
            val seen = LinkedHashSet<String>()
            rows.forEach { seen.addAll(it.keys) }
            return Schema(seen.map { FieldDef(it) })
        }
    }
}

/**
 * One row.
 *
 * Holds both the normalised [values] and, where a transform changed something,
 * the original in [raw]. Never silently destroy data (roadmap §7) — the user
 * can always see what was there before cleaning.
 */
data class Record(
    val id: String,
    val values: Map<String, String>,
    val raw: Map<String, String> = emptyMap(),
    /** Where this row came from, for attribution and re-running. */
    val sourceUrl: String = "",
    val collectedAt: Long = 0L,
) {
    operator fun get(field: String): String = values[field].orEmpty()
    fun rawOf(field: String): String = raw[field] ?: values[field].orEmpty()
    fun wasModified(field: String): Boolean =
        raw.containsKey(field) && raw[field] != values[field]

    val isEmpty: Boolean get() = values.values.none { it.isNotBlank() }

    fun with(field: String, value: String): Record {
        val newRaw = if (values[field] != value && !raw.containsKey(field))
            raw + (field to values[field].orEmpty()) else raw
        return copy(values = values + (field to value), raw = newRaw)
    }

    fun only(fields: List<String>): Record =
        copy(values = fields.associateWith { values[it].orEmpty() })
}

/** Provenance — how this dataset came to exist. */
data class DatasetSource(
    val kind: String = "job",          // job | import | merge | manual
    val jobId: String = "",
    val providerId: String = "",
    val url: String = "",
)

/**
 * A named, typed collection of records.
 *
 * Immutable: every operation returns a new Dataset, so undo and preview are
 * trivial and no UI can mutate data under another screen's feet.
 */
data class Dataset(
    val id: String,
    val name: String,
    val schema: Schema,
    val records: List<Record>,
    val source: DatasetSource = DatasetSource(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Pages/providers that failed — partial completion is first-class (§33). */
    val partial: PartialInfo? = null,
) {
    val size: Int get() = records.size
    val isEmpty: Boolean get() = records.isEmpty()
    val isPartial: Boolean get() = partial != null && partial.failed > 0

    fun rename(newName: String) = copy(name = newName)

    fun selectFields(keep: List<String>) = copy(
        schema = schema.select(keep),
        records = records.map { it.only(keep) },
    )

    fun renameField(from: String, to: String): Dataset {
        if (from !in schema) return this
        return copy(
            schema = Schema(schema.fields.map { if (it.name == from) it.copy(name = to) else it }),
            records = records.map { r ->
                r.copy(values = r.values.mapKeys { (k, _) -> if (k == from) to else k })
            },
        )
    }

    fun filter(predicate: (Record) -> Boolean) = copy(records = records.filter(predicate))

    /** Case-insensitive substring search across all fields, or one field. */
    fun search(query: String, field: String? = null): Dataset {
        if (query.isBlank()) return this
        val q = query.lowercase()
        return filter { r ->
            if (field != null) r[field].lowercase().contains(q)
            else r.values.values.any { it.lowercase().contains(q) }
        }
    }

    fun sortedBy(field: String, descending: Boolean = false): Dataset {
        val type = schema.typeOf(field)
        val cmp = compareBy<Record> { r ->
            val v = r[field]
            when (type) {
                FieldType.NUMBER, FieldType.CURRENCY -> v.toDoubleOrNull() ?: Double.MAX_VALUE
                else -> Double.NaN
            }
        }.thenBy { it[field].lowercase() }
        return copy(records = if (descending) records.sortedWith(cmp).reversed()
                              else records.sortedWith(cmp))
    }

    fun removeEmptyRows() = copy(records = records.filterNot { it.isEmpty })

    /** Union with another dataset; schemas are merged, not required to match. */
    fun mergeWith(other: Dataset, newName: String = name): Dataset = copy(
        name = newName,
        schema = schema.mergedWith(other.schema),
        records = records + other.records,
        source = DatasetSource(kind = "merge"),
    )

    /** Per-field completeness, for the pre-export preview (§32). */
    fun missingCounts(): Map<String, Int> =
        schema.names.associateWith { f -> records.count { it[f].isBlank() } }

    fun stats(): DatasetStats = DatasetStats(
        records = size,
        fields = schema.fields.size,
        missing = missingCounts(),
        emptyRows = records.count { it.isEmpty },
    )

    companion object {
        /** Build from raw extractor output. */
        fun of(
            id: String,
            name: String,
            rows: List<Map<String, String>>,
            schema: Schema? = null,
            source: DatasetSource = DatasetSource(),
            now: Long = 0L,
        ): Dataset {
            val s = schema ?: Schema.infer(rows)
            return Dataset(
                id = id, name = name, schema = s,
                records = rows.mapIndexed { i, row ->
                    Record(
                        id = "$id-${i + 1}",
                        values = s.names.associateWith { row[it].orEmpty() },
                        sourceUrl = row["url"].orEmpty(),
                        collectedAt = now,
                    )
                },
                source = source, createdAt = now, updatedAt = now,
            )
        }
    }
}

data class PartialInfo(
    val pagesProcessed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val reason: String = "",
)

data class DatasetStats(
    val records: Int,
    val fields: Int,
    val missing: Map<String, Int>,
    val emptyRows: Int,
)
