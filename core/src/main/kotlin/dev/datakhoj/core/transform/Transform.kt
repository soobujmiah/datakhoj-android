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

package dev.datakhoj.core.transform

import dev.datakhoj.core.dataset.Dataset
import dev.datakhoj.core.dataset.FieldDef
import dev.datakhoj.core.dataset.Record
import dev.datakhoj.core.dataset.Schema
import dev.datakhoj.core.extract.FieldTypes
import dev.datakhoj.core.model.FieldType

/**
 * The cleaning layer that sits between extraction and storage/export (§7).
 *
 * Governing rule: **never silently destroy data.** Every transform that
 * changes a value records the original in [Record.raw], so the UI can always
 * show "was → now" and the user can audit or revert.
 *
 * Transforms are pure and composable:
 * ```
 * dataset.transform(TrimWhitespace(), NormalizeEmail("email"), RemoveEmptyRows())
 * ```
 */
interface Transform {
    val id: String
    val description: String
    fun apply(dataset: Dataset): Dataset
}

/** Applies to specific fields, or all text fields when [fields] is empty. */
abstract class FieldTransform(protected val fields: List<String> = emptyList()) : Transform {
    protected abstract fun transformValue(value: String, type: FieldType): String

    protected open fun targets(dataset: Dataset): List<String> =
        if (fields.isEmpty()) dataset.schema.names else fields.filter { it in dataset.schema }

    override fun apply(dataset: Dataset): Dataset {
        val cols = targets(dataset)
        if (cols.isEmpty()) return dataset
        return dataset.copy(records = dataset.records.map { rec ->
            var r = rec
            for (c in cols) {
                val old = rec[c]
                val new = transformValue(old, dataset.schema.typeOf(c))
                if (new != old) r = r.with(c, new)
            }
            r
        })
    }
}

// ---------------------------------------------------------------- whitespace

class TrimWhitespace(fields: List<String> = emptyList()) : FieldTransform(fields) {
    override val id = "trim"
    override val description = "Remove leading and trailing spaces"
    override fun transformValue(value: String, type: FieldType) = value.trim()
}

class NormalizeWhitespace(fields: List<String> = emptyList()) : FieldTransform(fields) {
    override val id = "normalize_whitespace"
    override val description = "Collapse runs of whitespace into one space"
    private val ws = Regex("\\s+")
    override fun transformValue(value: String, type: FieldType) = ws.replace(value, " ").trim()
}

// ------------------------------------------------------------ typed cleaning

/**
 * Conservative email normalisation: lowercase and trim only.
 * A value that is not an email is left untouched rather than mangled.
 */
class NormalizeEmail(fields: List<String> = emptyList()) : FieldTransform(fields) {
    override val id = "normalize_email"
    override val description = "Lowercase and trim email addresses"
    private val re = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    override fun targets(dataset: Dataset) =
        if (fields.isNotEmpty()) fields.filter { it in dataset.schema }
        else dataset.schema.fields.filter { it.type == FieldType.EMAIL }.map { it.name }
    override fun transformValue(value: String, type: FieldType): String {
        val t = value.trim()
        return if (re.matches(t)) t.lowercase() else value
    }
}

/**
 * Conservative phone normalisation: keeps a leading +, strips separators.
 * Anything that does not look like a phone number is returned unchanged, so
 * "call us" never becomes "".
 */
class NormalizePhone(fields: List<String> = emptyList()) : FieldTransform(fields) {
    override val id = "normalize_phone"
    override val description = "Strip separators, keep leading +"
    override fun targets(dataset: Dataset) =
        if (fields.isNotEmpty()) fields.filter { it in dataset.schema }
        else dataset.schema.fields.filter { it.type == FieldType.PHONE }.map { it.name }
    override fun transformValue(value: String, type: FieldType): String {
        val digits = FieldTypes.normalizeDigits(value).count { it.isDigit() }
        if (digits < 6) return value          // not a phone number — leave alone
        return FieldTypes.coerce(value, FieldType.PHONE)
    }
}

class NormalizeUrl(fields: List<String> = emptyList()) : FieldTransform(fields) {
    override val id = "normalize_url"
    override val description = "Trim and drop tracking fragments"
    override fun targets(dataset: Dataset) =
        if (fields.isNotEmpty()) fields.filter { it in dataset.schema }
        else dataset.schema.fields.filter { it.type == FieldType.URL || it.type == FieldType.IMAGE }
            .map { it.name }
    override fun transformValue(value: String, type: FieldType): String {
        val t = value.trim()
        if (!t.startsWith("http")) return value
        return t.substringBefore('#').trimEnd('/')
    }
}

class NormalizeDate(fields: List<String> = emptyList()) : FieldTransform(fields) {
    override val id = "normalize_date"
    override val description = "Convert dates to ISO-8601 where recognisable"
    override fun targets(dataset: Dataset) =
        if (fields.isNotEmpty()) fields.filter { it in dataset.schema }
        else dataset.schema.fields.filter { it.type == FieldType.DATE }.map { it.name }
    override fun transformValue(value: String, type: FieldType) =
        FieldTypes.coerce(value, FieldType.DATE).ifBlank { value }
}

class NormalizeNumber(fields: List<String> = emptyList()) : FieldTransform(fields) {
    override val id = "normalize_number"
    override val description = "Strip currency symbols and separators"
    override fun targets(dataset: Dataset) =
        if (fields.isNotEmpty()) fields.filter { it in dataset.schema }
        else dataset.schema.fields
            .filter { it.type == FieldType.NUMBER || it.type == FieldType.CURRENCY }
            .map { it.name }
    override fun transformValue(value: String, type: FieldType) =
        FieldTypes.coerce(value, if (type == FieldType.CURRENCY) type else FieldType.NUMBER)
            .ifBlank { value }
}

enum class CaseMode { LOWER, UPPER, TITLE }

class ChangeCase(private val mode: CaseMode, fields: List<String>) : FieldTransform(fields) {
    override val id = "case_${mode.name.lowercase()}"
    override val description = "Change text case to ${mode.name.lowercase()}"
    override fun transformValue(value: String, type: FieldType) = when (mode) {
        CaseMode.LOWER -> value.lowercase()
        CaseMode.UPPER -> value.uppercase()
        CaseMode.TITLE -> value.split(' ').joinToString(" ") { w ->
            if (w.isEmpty()) w else w[0].uppercase() + w.drop(1).lowercase()
        }
    }
}

// ------------------------------------------------------------ structural ops

class RemoveEmptyRows : Transform {
    override val id = "remove_empty_rows"
    override val description = "Drop rows where every field is blank"
    override fun apply(dataset: Dataset) = dataset.removeEmptyRows()
}

class RenameField(private val from: String, private val to: String) : Transform {
    override val id = "rename_field"
    override val description = "Rename '$from' to '$to'"
    override fun apply(dataset: Dataset) = dataset.renameField(from, to)
}

class SelectFields(private val keep: List<String>) : Transform {
    override val id = "select_fields"
    override val description = "Keep only: ${keep.joinToString(", ")}"
    override fun apply(dataset: Dataset) = dataset.selectFields(keep)
}

/** Combine two or more fields into a new one. */
class MergeFields(
    private val sources: List<String>,
    private val into: String,
    private val separator: String = " ",
    private val dropSources: Boolean = false,
) : Transform {
    override val id = "merge_fields"
    override val description = "Merge ${sources.joinToString(" + ")} into '$into'"
    override fun apply(dataset: Dataset): Dataset {
        val present = sources.filter { it in dataset.schema }
        if (present.isEmpty()) return dataset
        val schema = if (into in dataset.schema) dataset.schema
                     else Schema(dataset.schema.fields + FieldDef(into, origin = "merge"))
        var out = dataset.copy(
            schema = schema,
            records = dataset.records.map { r ->
                val merged = present.map { r[it] }.filter { it.isNotBlank() }.joinToString(separator)
                r.copy(values = r.values + (into to merged))
            },
        )
        if (dropSources) out = out.selectFields(out.schema.names.filterNot { it in present })
        return out
    }
}

/** Split one field into several by a delimiter. */
class SplitField(
    private val source: String,
    private val into: List<String>,
    private val delimiter: String = ",",
) : Transform {
    override val id = "split_field"
    override val description = "Split '$source' into ${into.joinToString(", ")}"
    override fun apply(dataset: Dataset): Dataset {
        if (source !in dataset.schema) return dataset
        val newFields = into.filterNot { it in dataset.schema }.map { FieldDef(it, origin = "split") }
        return dataset.copy(
            schema = Schema(dataset.schema.fields + newFields),
            records = dataset.records.map { r ->
                val parts = r[source].split(delimiter).map { it.trim() }
                r.copy(values = r.values + into.mapIndexed { i, n ->
                    n to (parts.getOrNull(i) ?: "")
                }.toMap())
            },
        )
    }
}

// --------------------------------------------------------------- the runner

/** Applies transforms in order and reports what each one changed. */
object TransformPipeline {

    data class Step(val id: String, val description: String, val cellsChanged: Int, val rowsRemoved: Int)
    data class Result(val dataset: Dataset, val steps: List<Step>) {
        val totalChanged: Int get() = steps.sumOf { it.cellsChanged }
        val totalRemoved: Int get() = steps.sumOf { it.rowsRemoved }
    }

    fun run(dataset: Dataset, transforms: List<Transform>): Result {
        var current = dataset
        val steps = mutableListOf<Step>()
        for (t in transforms) {
            val before = current
            current = t.apply(current)
            steps += Step(
                id = t.id,
                description = t.description,
                cellsChanged = countChanges(before, current),
                rowsRemoved = (before.size - current.size).coerceAtLeast(0),
            )
        }
        return Result(current, steps)
    }

    private fun countChanges(before: Dataset, after: Dataset): Int {
        var n = 0
        val byId = before.records.associateBy { it.id }
        for (r in after.records) {
            val b = byId[r.id] ?: continue
            for ((k, v) in r.values) if (b.values[k] != v) n++
        }
        return n
    }
}

/** Sensible defaults for freshly scraped data. Conservative by design. */
fun standardCleanup(): List<Transform> = listOf(
    NormalizeWhitespace(),
    NormalizeEmail(),
    NormalizePhone(),
    NormalizeUrl(),
    NormalizeNumber(),
    NormalizeDate(),
    RemoveEmptyRows(),
)

fun Dataset.transform(vararg transforms: Transform): Dataset =
    TransformPipeline.run(this, transforms.toList()).dataset
