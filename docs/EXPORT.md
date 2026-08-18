# Export System

Dataset-first and extensible (§13, §14, §39).

## The contract

```
ExportRequest → ExportEngine → ExportWriter → ExportResult
```

`ExportRequest` makes an export a **first-class value** rather than four loose
parameters. That is what later phases need:

| Phase | Requires a request object because |
|---|---|
| 2 — WorkManager | a queued export must be serialisable and replayable |
| 3 — preview UI | the UI validates and describes an export before running it |
| 5 — export history | "repeat this export" = re-submitting a stored request |

```kotlin
val request = ExportRequest(
    dataset = dataset,
    formatId = "csv",
    options = ExportOptions(fields = listOf("name", "email")),
    filenameOverride = null,
)

val v = request.validate()          // check BEFORE writing any bytes
if (!v.isValid) showError(v.summary())
if (v.hasWarnings) showWarning(v.summary())

request.describe()                  // "3 record(s) × 2 field(s) → CSV"

val result: ExportResult = ExportEngine.submit(request, outputStream)
```

**Validation happens before execution**, so a bad export fails cleanly instead
of leaving a half-written file. Problems block; warnings (a partial dataset, an
unknown field name) inform but allow.

The request carries **no destination**. Where bytes go is an Android concern
(SAF `Uri`); how they are formatted is pure logic.

## Formats

| id | Format | Ext | Notes |
|---|---|---|---|
| `csv` | CSV | .csv | UTF-8 BOM, RFC 4180, injection-safe |
| `tsv` | TSV | .tsv | tab-delimited |
| `json` | JSON | .json | typed values, optional metadata |
| `ndjson` | NDJSON | .ndjson | one object per line, streaming-friendly |
| `yaml` | YAML | .yaml | quoted where ambiguous |
| `md` | Markdown | .md | pipe-escaped tables |
| `html` | HTML | .html | styled, XSS-escaped, self-contained |
| `sql` | SQL | .sql | CREATE TABLE + INSERTs, literals escaped |
| `xlsx` | Excel | .xlsx | ⬜ Phase 5 — needs an Android library |

## Quality guarantees (§14) — each has a test

| Guarantee | Test |
|---|---|
| UTF-8 throughout | `Bengali survives CSV` |
| Excel opens Bengali | `Bengali CSV has BOM for Excel` |
| Deterministic column order | `column order respected` |
| Stable headers | `CSV header first` |
| Null/empty handling | `blank becomes null` |
| CSV injection defused | `CSV injection neutralised` |
| Quote escaping | `CSV quotes escaped` |
| SQL literal escaping | `SQL quotes escaped` |
| HTML escaping | `HTML escaped` |
| Markdown pipe escaping | `pipes escaped` |
| Numeric typing | `numbers unquoted in JSON` |
| Filename generation | `filename suggested` |

### CSV injection

A cell beginning `=`, `+`, `-` or `@` is quoted. Without this, opening an
export in Excel can execute a formula from scraped content — a real attack on
anyone who scrapes hostile pages.

```
=cmd|'/c calc'!A1   →   "=cmd|'/c calc'!A1"
```

## Options

```kotlin
ExportOptions(
    fields = listOf("name", "email"),   // subset + order
    recordIds = setOf("ds1-2"),         // selected rows only
    includeHeader = true,
    utf8Bom = true,                     // Excel compatibility
    nullValue = "",
    delimiter = ',',
    includeMetadata = false,            // schema block in JSON/YAML
    tableName = "records",              // SQL
)
```

## Adding a format (§39)

Implement `ExportWriter` and register it. Nothing else changes:

```kotlin
class ParquetWriter : ExportWriter {
    override val format = ExportFormat("parquet", "Parquet", "parquet",
                                       "application/vnd.apache.parquet", isText = false)
    override fun write(d: Dataset, out: OutputStream, o: ExportOptions): ExportResult { ... }
}

ExportEngine.register(ParquetWriter())
```

Deferred by design: Parquet, XML, PDF, DOCX, PPTX. The contract accepts them;
none are implemented, per §46.

## Android integration (Phase 5)

Writers take an `OutputStream`, never a path. The Android layer supplies it
from the Storage Access Framework:

```kotlin
contentResolver.openOutputStream(uri)?.use { stream ->
    ExportEngine.export(dataset, "csv", stream, options)
}
```

No broad storage permission is required (§16, §31).
