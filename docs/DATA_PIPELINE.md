# Data Pipeline

```
Discover → Search → Fetch → Extract → Normalize → Validate
   → Store → Preview → Export / Download / Share → Diagnostics
```

Each stage is independently replaceable. Current implementation status:

| Stage | Where | Status |
|---|---|---|
| Discover / Search | `core/intent`, `core/provider` | ✅ 65 tests |
| Fetch | `app/net/AndroidHttpClient` | ✅ works; retry policy Phase 7 |
| Extract | `core/extract/Extractor` | ✅ 5/5 parity with Python |
| **Normalize** | `core/transform` | ✅ **new** — 13 transforms |
| **Validate / dedup** | `core/dedup` | ✅ **new** — exact/likely/unique |
| **Store (in-memory)** | `core/dataset` | ✅ **new** |
| Store (persistent) | Room | ⬜ Phase 2 |
| Preview | `Dataset.stats()`, `missingCounts()` | ✅ data layer ready; UI Phase 3 |
| **Export** | `core/export` | ✅ **new** — 8 formats |
| Download | — | ⬜ Phase 4 |
| Diagnostics | — | ⬜ Phase 8 |

## Worked example

```kotlin
// 1. Extract (existing engine, unchanged)
val rows = JobRunner.runOffline(spec, html)

// 2. Become a dataset — the pivot from "rows" to "data"
val dataset = Dataset.of("ds1", "Contacts", rows, contactSchema)

// 3. Normalize — conservative, reversible
val cleaned = TransformPipeline.run(dataset, standardCleanup())
cleaned.totalChanged   // 14 cells
cleaned.totalRemoved   // 1 blank row

// 4. Validate — classify, never auto-delete
val analysis = Deduplicator(keys = listOf("email")).analyze(cleaned.dataset)
analysis.summary()     // "2 unique, 1 exact duplicate"

// 5. Export — as many times as wanted, no re-scraping
ExportEngine.export(cleaned.dataset, "csv",  csvStream)
ExportEngine.export(cleaned.dataset, "json", jsonStream)
ExportEngine.export(cleaned.dataset, "sql",  sqlStream)
```

Step 5 is the point of the whole design. The dataset is scraped **once**.

## Why formatting lives in `:core`

Export *formatting* is pure logic → `:core`, unit-testable with no emulator.
Export *destination* (a SAF `Uri`) is Android → `:app`.

`ExportWriter` therefore takes an `OutputStream`, never a `File` or `Uri`.
That single decision is why 76 export assertions run in 0.4 s in CI.
