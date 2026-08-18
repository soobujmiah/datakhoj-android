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
import dev.datakhoj.core.dataset.Record
import dev.datakhoj.core.model.FieldType
import java.io.Writer

/**
 * Concrete format writers.
 *
 * All are pure text transforms over a [Dataset], so each is unit-testable
 * without an emulator, a file system, or a network.
 */

// ------------------------------------------------------------------- CSV/TSV

class CsvWriter(private val tsv: Boolean = false) : TextExportWriter() {
    override val format = if (tsv) Formats.TSV else Formats.CSV

    override fun writeText(
        dataset: Dataset, fields: List<String>, records: List<Record>,
        out: Writer, options: ExportOptions,
    ) {
        val d = if (tsv) '\t' else options.delimiter
        if (options.includeHeader) {
            out.write(fields.joinToString(d.toString()) { esc(it, d) }); out.write("\r\n")
        }
        for (r in records) {
            out.write(fields.joinToString(d.toString()) { f ->
                esc(r[f].ifBlank { options.nullValue }, d)
            })
            out.write("\r\n")
        }
    }

    /** RFC 4180 quoting; also quotes leading '=' to defuse CSV injection. */
    private fun esc(v: String, d: Char): String {
        val risky = v.contains(d) || v.contains('"') || v.contains('\n') || v.contains('\r')
        val formula = v.isNotEmpty() && v[0] in charArrayOf('=', '+', '-', '@')
        return when {
            risky || formula -> "\"" + v.replace("\"", "\"\"") + "\""
            else -> v
        }
    }
}

// --------------------------------------------------------------- JSON/NDJSON

class JsonWriter(private val ndjson: Boolean = false) : TextExportWriter() {
    override val format = if (ndjson) Formats.NDJSON else Formats.JSON

    override fun writeText(
        dataset: Dataset, fields: List<String>, records: List<Record>,
        out: Writer, options: ExportOptions,
    ) {
        if (ndjson) {
            records.forEach { r -> out.write(obj(dataset, r, fields, options)); out.write("\n") }
            return
        }
        val nl = if (options.prettyPrint) "\n" else ""
        val ind = if (options.prettyPrint) "  " else ""
        out.write("{$nl")
        if (options.includeMetadata) {
            out.write("$ind\"dataset\": ${str(dataset.name)},$nl")
            out.write("$ind\"records\": ${records.size},$nl")
            out.write("$ind\"schema\": {$nl")
            out.write(fields.joinToString(",$nl") { f ->
                "$ind$ind${str(f)}: ${str(dataset.schema.typeOf(f).id)}"
            })
            out.write("$nl$ind},$nl")
        }
        out.write("$ind\"data\": [$nl")
        out.write(records.joinToString(",$nl") { r ->
            "$ind$ind" + obj(dataset, r, fields, options)
        })
        out.write("$nl$ind]$nl}")
    }

    private fun obj(d: Dataset, r: Record, fields: List<String>, o: ExportOptions): String =
        "{" + fields.joinToString(",") { f ->
            val raw = r[f]
            val v = if (raw.isBlank()) o.nullValue else raw
            val t = d.schema.typeOf(f)
            val json = when {
                v.isEmpty() -> "null"
                t == FieldType.NUMBER || t == FieldType.CURRENCY ->
                    v.toDoubleOrNull()?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: str(v)
                t == FieldType.BOOLEAN -> if (v == "true" || v == "false") v else str(v)
                else -> str(v)
            }
            "${str(f)}:$json"
        } + "}"

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}

// ------------------------------------------------------------------- YAML

class YamlWriter : TextExportWriter() {
    override val format = Formats.YAML

    override fun writeText(
        dataset: Dataset, fields: List<String>, records: List<Record>,
        out: Writer, options: ExportOptions,
    ) {
        if (options.includeMetadata) {
            out.write("dataset: ${q(dataset.name)}\n")
            out.write("records: ${records.size}\n")
        }
        out.write("data:\n")
        for (r in records) {
            var first = true
            for (f in fields) {
                val v = r[f].ifBlank { options.nullValue }
                out.write(if (first) "  - " else "    ")
                out.write("$f: ${q(v)}\n")
                first = false
            }
            if (first) out.write("  - {}\n")
        }
    }

    private fun q(v: String): String {
        if (v.isEmpty()) return "\"\""
        val needs = v.any { it in ":#{}[],&*?|<>=!%@`\"'\n" } ||
            v.first().isWhitespace() || v.last().isWhitespace() ||
            v.lowercase() in setOf("true", "false", "null", "yes", "no", "~")
        return if (needs) "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
               else v
    }
}

// ---------------------------------------------------------------- Markdown

class MarkdownWriter : TextExportWriter() {
    override val format = Formats.MARKDOWN

    override fun writeText(
        dataset: Dataset, fields: List<String>, records: List<Record>,
        out: Writer, options: ExportOptions,
    ) {
        out.write("# ${dataset.name}\n\n")
        if (options.includeMetadata) {
            out.write("${records.size} records · ${fields.size} fields\n\n")
        }
        out.write("| " + fields.joinToString(" | ") { esc(it) } + " |\n")
        out.write("|" + fields.joinToString("|") { "---" } + "|\n")
        for (r in records) {
            out.write("| " + fields.joinToString(" | ") { esc(r[it].ifBlank { options.nullValue }) } + " |\n")
        }
    }

    private fun esc(v: String) = v.replace("|", "\\|").replace("\n", "<br>")
}

// -------------------------------------------------------------------- HTML

class HtmlWriter : TextExportWriter() {
    override val format = Formats.HTML

    override fun writeText(
        dataset: Dataset, fields: List<String>, records: List<Record>,
        out: Writer, options: ExportOptions,
    ) {
        out.write("""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<title>${esc(dataset.name)}</title>
<style>
 body{font-family:system-ui,-apple-system,'Noto Sans Bengali',sans-serif;margin:24px;color:#111917}
 h1{font-size:20px;color:#0B6E5F}
 .meta{color:#5A6B66;font-size:13px;margin-bottom:16px}
 table{border-collapse:collapse;width:100%;font-size:13px}
 th{background:#0B6E5F;color:#fff;text-align:left;padding:8px;position:sticky;top:0}
 td{border-bottom:1px solid #DDE5E2;padding:8px;vertical-align:top}
 tr:nth-child(even) td{background:#F8FBFA}
 .empty{color:#B3261E;font-style:italic}
</style></head><body>
<h1>${esc(dataset.name)}</h1>
<div class="meta">${records.size} records · ${fields.size} fields</div>
<table><thead><tr>""")
        fields.forEach { out.write("<th>${esc(it)}</th>") }
        out.write("</tr></thead><tbody>\n")
        for (r in records) {
            out.write("<tr>")
            for (f in fields) {
                val v = r[f]
                if (v.isBlank()) out.write("<td class=\"empty\">${esc(options.nullValue)}</td>")
                else out.write("<td>${esc(v)}</td>")
            }
            out.write("</tr>\n")
        }
        out.write("</tbody></table></body></html>\n")
    }

    private fun esc(v: String) = v
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}

// --------------------------------------------------------------------- SQL

class SqlWriter : TextExportWriter() {
    override val format = Formats.SQL

    override fun writeText(
        dataset: Dataset, fields: List<String>, records: List<Record>,
        out: Writer, options: ExportOptions,
    ) {
        val t = ident(options.tableName)
        out.write("-- DataKhoj export: ${dataset.name}\n")
        out.write("-- ${records.size} records\n\n")
        out.write("CREATE TABLE IF NOT EXISTS $t (\n")
        out.write(fields.joinToString(",\n") { f -> "  ${ident(f)} ${sqlType(dataset.schema.typeOf(f))}" })
        out.write("\n);\n\n")
        for (r in records) {
            out.write("INSERT INTO $t (${fields.joinToString(", ") { ident(it) }}) VALUES (")
            out.write(fields.joinToString(", ") { f ->
                val v = r[f]
                val ty = dataset.schema.typeOf(f)
                when {
                    v.isBlank() -> "NULL"
                    ty == FieldType.NUMBER || ty == FieldType.CURRENCY ->
                        v.toDoubleOrNull()?.toString() ?: lit(v)
                    else -> lit(v)
                }
            })
            out.write(");\n")
        }
    }

    private fun sqlType(t: FieldType) = when (t) {
        FieldType.NUMBER, FieldType.CURRENCY -> "REAL"
        FieldType.BOOLEAN -> "INTEGER"
        else -> "TEXT"
    }
    private fun ident(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    private fun lit(s: String) = "'" + s.replace("'", "''") + "'"
}
