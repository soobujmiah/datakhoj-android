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

package dev.datakhoj.core.export

import dev.datakhoj.core.dataset.Dataset

/**
 * The stable export contract.
 *
 * ```
 * ExportRequest → ExportEngine → ExportWriter → ExportResult
 * ```
 *
 * Previously the engine took `(dataset, formatId, stream, options)` as four
 * loose parameters. That is not a contract: it cannot be validated before
 * execution, logged, queued, retried, or persisted to export history without
 * every caller re-assembling the same tuple by hand.
 *
 * [ExportRequest] makes the operation a **first-class value**, which is what
 * later phases need:
 *
 * | Phase | Needs a request object because |
 * |---|---|
 * | 2 — WorkManager | a queued export must be serialisable and replayable |
 * | 3 — preview UI | the UI must validate and describe an export before running it |
 * | 5 — export history | "repeat this export" means re-submitting a stored request |
 *
 * The request deliberately carries **no destination**. Where bytes go is an
 * Android concern (SAF `Uri`); how they are formatted is pure logic. The
 * destination is supplied at execution time as an `OutputStream`.
 */
data class ExportRequest(
    val dataset: Dataset,
    val formatId: String,
    val options: ExportOptions = ExportOptions(),
    /** Overrides the auto-generated filename when set. */
    val filenameOverride: String? = null,
) {
    /** Resolved columns, in output order. Empty options = the schema's order. */
    val effectiveFields: List<String>
        get() = if (options.fields.isEmpty()) dataset.schema.names
                else options.fields.filter { it in dataset.schema }

    /** Rows that will actually be written. */
    val effectiveRecordCount: Int
        get() = if (options.recordIds.isEmpty()) dataset.records.size
                else dataset.records.count { it.id in options.recordIds }

    /**
     * Check the request without performing it.
     *
     * Lets the UI disable an unusable export and explain why, instead of
     * failing halfway through writing a file.
     */
    fun validate(): ExportValidation {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val writer = ExportEngine.writerFor(formatId)
        if (writer == null) {
            problems += "Unknown format '$formatId'. Available: " +
                ExportEngine.available().joinToString(", ") { it.id }
        }
        if (dataset.records.isEmpty()) {
            problems += "Dataset '${dataset.name}' has no records."
        }
        if (effectiveFields.isEmpty()) {
            problems += "No fields selected — nothing would be written."
        }
        options.fields.filterNot { it in dataset.schema }.forEach {
            warnings += "Field '$it' is not in the schema and will be skipped."
        }
        if (options.recordIds.isNotEmpty() && effectiveRecordCount == 0) {
            problems += "None of the selected record ids exist in this dataset."
        }
        if (dataset.isPartial) {
            val p = dataset.partial!!
            warnings += "Dataset is partial: ${p.failed} page(s) failed, " +
                "${p.skipped} skipped. Export will contain what was collected."
        }
        filenameOverride?.let { name ->
            if (name.any { it in "/\\:*?\"<>|" }) {
                problems += "Filename contains characters that are not allowed."
            }
        }
        return ExportValidation(problems, warnings)
    }

    /** One-line description for confirmation UI and history entries. */
    fun describe(): String {
        val fmt = ExportEngine.writerFor(formatId)?.format
        return "$effectiveRecordCount record(s) × ${effectiveFields.size} field(s) " +
            "→ ${fmt?.label ?: formatId}"
    }
}

data class ExportValidation(
    val problems: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val isValid: Boolean get() = problems.isEmpty()
    val hasWarnings: Boolean get() = warnings.isNotEmpty()

    fun summary(): String = when {
        problems.isNotEmpty() -> problems.joinToString("\n")
        warnings.isNotEmpty() -> warnings.joinToString("\n")
        else -> "Ready to export."
    }
}
