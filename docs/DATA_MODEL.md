# Data Model

The roadmap's central rule (§4, §5): **a CSV is not a dataset.** A dataset is
collected data; a CSV is one rendering of it. Keeping these separate is what
lets the user export the same data repeatedly, in any format, without
scraping again.

## Entities

```
Job ──────── has many ───────> JobRun
 │                               │
 │ defines                       │ produces
 ▼                               ▼
JobSpec v1                    Dataset ──── has many ────> Record
 │                               │                          │
 │ uses                          │ described by             │ conforms to
 ▼                               ▼                          ▼
Provider                      Schema ───── has many ────> FieldDef
                                 │
                                 │ rendered as
                                 ▼
                              Export ──> CSV | XLSX | JSON | YAML | MD | HTML | SQL
```

Never conflate these. A `Job` is a reusable definition; a `JobRun` is one
execution of it; a `Dataset` is what that execution produced.

## Implemented — `:core`, pure JVM

| Type | File | Purpose |
|---|---|---|
| `FieldDef` | `dataset/Dataset.kt` | one column: name, type, label, origin |
| `Schema` | " | ordered columns; merge, select, infer |
| `Record` | " | one row + **raw values** + provenance |
| `Dataset` | " | named, typed, immutable collection |
| `DatasetSource` | " | job / import / merge / manual |
| `PartialInfo` | " | pages processed, failed, skipped (§33) |
| `DatasetStats` | " | counts and per-field completeness |

### Immutability

Every operation returns a new `Dataset`. Undo and preview become trivial, and
no screen can mutate data underneath another.

```kotlin
val cleaned = dataset
    .transform(NormalizeWhitespace(), NormalizeEmail())
    .removeEmptyRows()
    .sortedBy("name")
// `dataset` is unchanged
```

### Raw-value preservation

`Record` carries both `values` (normalised) and `raw` (as collected). A
transform that changes a cell records the original, so nothing is ever
silently destroyed (§7):

```kotlin
record["name"]            // "Sobuj Miah"
record.rawOf("name")      // "  Sobuj   Miah "
record.wasModified("name")// true
```

The UI can always show *was → now*, and the user can audit or revert.

## JobRun states (§4)

```
queued → running → completed
            │  ├──> partially_completed   (§33 — usable, some pages failed)
            │  ├──> failed
            │  ├──> cancelled
            └──> paused → running
```

`partially_completed` is deliberate: 80 of 100 pages succeeding produces a
usable dataset, not a failure.

**Status:** modelled in `PartialInfo`; the state machine itself lands with
WorkManager in Phase 2.

## Deferred, with extension points in place

| Entity | Extension point today |
|---|---|
| `Job`, `JobRun` persistence | `Dataset.source` carries `jobId` |
| `Download` | — Phase 4 |
| `Export` history | `ExportResult` already returns everything needed to log |
| `DiagnosticSession` | — Phase 8 |

Per §46, these are documented rather than stubbed.
