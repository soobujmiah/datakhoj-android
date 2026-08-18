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
import java.io.OutputStream

/**
 * Dataset-first, extensible export (§13, §39).
 *
 * ```
 * Dataset -> ExportRequest -> ExportEngine -> ExportWriter -> bytes
 * ```
 *
 * Writers deal in [OutputStream], never in files or Android `Uri`s, so the
 * whole layer is pure JVM and unit-testable. The Android side supplies the
 * stream from the Storage Access Framework — destination is a platform
 * concern, formatting is not.
 *
 * Adding Parquet/XML/PDF later means implementing [ExportWriter] and calling
 * [ExportEngine.register]. Nothing else changes.
 */

data class ExportFormat(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
    /** False for binary formats (XLSX), which need platform libraries. */
    val isText: Boolean = true,
)

object Formats {
    val CSV      = ExportFormat("csv", "CSV", "csv", "text/csv")
    val TSV      = ExportFormat("tsv", "TSV", "tsv", "text/tab-separated-values")
    val JSON     = ExportFormat("json", "JSON", "json", "application/json")
    val NDJSON   = ExportFormat("ndjson", "NDJSON", "ndjson", "application/x-ndjson")
    val YAML     = ExportFormat("yaml", "YAML", "yaml", "application/yaml")
    val MARKDOWN = ExportFormat("md", "Markdown", "md", "text/markdown")
    val HTML     = ExportFormat("html", "HTML", "html", "text/html")
    val SQL      = ExportFormat("sql", "SQL", "sql", "application/sql")
    val XLSX     = ExportFormat("xlsx", "Excel", "xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", isText = false)

    val all = listOf(CSV, TSV, JSON, NDJSON, YAML, MARKDOWN, HTML, SQL, XLSX)
    fun byId(id: String) = all.firstOrNull { it.id == id.lowercase() }
}

data class ExportOptions(
    /** Restrict and order columns. Empty = the dataset's schema order. */
    val fields: List<String> = emptyList(),
    /** Restrict rows by record id. Empty = all. */
    val recordIds: Set<String> = emptySet(),
    val includeHeader: Boolean = true,
    /** UTF-8 BOM so Excel opens Bengali correctly (§14). */
    val utf8Bom: Boolean = true,
    val nullValue: String = "",
    val delimiter: Char = ',',
    val prettyPrint: Boolean = true,
    val includeMetadata: Boolean = false,
    val tableName: String = "records",
)

data class ExportResult(
    val format: ExportFormat,
    val bytesWritten: Long,
    val recordsWritten: Int,
    val fieldsWritten: List<String>,
    val suggestedFilename: String,
)

/** Implement this to add a format. */
interface ExportWriter {
    val format: ExportFormat
    fun write(dataset: Dataset, out: OutputStream, options: ExportOptions = ExportOptions()): ExportResult
}

/** Counts bytes so [ExportResult] is accurate regardless of destination. */
private class CountingStream(private val inner: OutputStream) : OutputStream() {
    var count = 0L; private set
    override fun write(b: Int) { inner.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
    override fun flush() = inner.flush()
    override fun close() = inner.close()
}

/** Shared helpers for text writers. */
abstract class TextExportWriter : ExportWriter {

    protected fun resolveFields(dataset: Dataset, o: ExportOptions): List<String> =
        if (o.fields.isEmpty()) dataset.schema.names
        else o.fields.filter { it in dataset.schema }

    protected fun resolveRecords(dataset: Dataset, o: ExportOptions) =
        if (o.recordIds.isEmpty()) dataset.records
        else dataset.records.filter { it.id in o.recordIds }

    protected fun filename(dataset: Dataset): String {
        val safe = dataset.name.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_')
            .ifBlank { "dataset" }
            .take(60)
        return "$safe.${format.extension}"
    }

    final override fun write(dataset: Dataset, out: OutputStream, options: ExportOptions): ExportResult {
        val counting = CountingStream(out)
        val fields = resolveFields(dataset, options)
        val records = resolveRecords(dataset, options)
        val w = counting.bufferedWriter(Charsets.UTF_8)
        if (options.utf8Bom && format.isText && format != Formats.JSON && format != Formats.NDJSON) {
            w.write("\uFEFF")
        }
        writeText(dataset, fields, records.size.let { records }, w, options)
        w.flush()
        return ExportResult(format, counting.count, records.size, fields, filename(dataset))
    }

    protected abstract fun writeText(
        dataset: Dataset,
        fields: List<String>,
        records: List<dev.datakhoj.core.dataset.Record>,
        out: java.io.Writer,
        options: ExportOptions,
    )
}

private fun OutputStream.bufferedWriter(cs: java.nio.charset.Charset) =
    java.io.BufferedWriter(java.io.OutputStreamWriter(this, cs))

/**
 * The registry and execution point for the export contract.
 *
 * ```
 * ExportRequest → ExportEngine → ExportWriter → ExportResult
 * ```
 *
 * Formats are looked up by id, never hardcoded into callers, so a new writer
 * is registered rather than wired in (§39).
 */
object ExportEngine {
    private val writers = linkedMapOf<String, ExportWriter>()

    init {
        register(CsvWriter())
        register(CsvWriter(tsv = true))
        register(JsonWriter())
        register(JsonWriter(ndjson = true))
        register(YamlWriter())
        register(MarkdownWriter())
        register(HtmlWriter())
        register(SqlWriter())
    }

    fun register(w: ExportWriter) { writers[w.format.id] = w }
    fun writerFor(formatId: String): ExportWriter? = writers[formatId.lowercase()]
    fun available(): List<ExportFormat> = writers.values.map { it.format }

    /** Formats this build can actually produce (XLSX needs the Android module). */
    fun isSupported(formatId: String) = writers.containsKey(formatId.lowercase())

    /**
     * Execute an [ExportRequest]. The primary entry point.
     *
     * The request is validated first, so a bad export fails before a single
     * byte is written rather than leaving a truncated file behind.
     *
     * @param out where the bytes go. Supplied by the caller because the
     *   destination is a platform concern (SAF `Uri` on Android) while
     *   formatting is pure logic.
     */
    fun submit(request: ExportRequest, out: OutputStream): ExportResult {
        val validation = request.validate()
        if (!validation.isValid) {
            throw ExportException(validation.problems.joinToString("; "))
        }
        val writer = writerFor(request.formatId)
            ?: throw ExportException("No writer for '${request.formatId}'.")
        val result = writer.write(request.dataset, out, request.options)
        return request.filenameOverride
            ?.let { result.copy(suggestedFilename = it) }
            ?: result
    }

    /**
     * Convenience overload that builds the request for you.
     *
     * Prefer [submit] where the request needs to be validated, described,
     * queued or logged first.
     */
    fun export(
        dataset: Dataset,
        formatId: String,
        out: OutputStream,
        options: ExportOptions = ExportOptions(),
    ): ExportResult = submit(ExportRequest(dataset, formatId, options), out)
}

/** Raised when an export cannot be performed. Carries a user-facing message. */
class ExportException(message: String) : Exception(message)
