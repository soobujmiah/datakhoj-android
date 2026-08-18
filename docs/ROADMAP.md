# Roadmap

Reflects the **actual** implementation state (§48). Updated after every phase.

Legend: ✅ done & tested · 🟡 partial · ⬜ not started

---

## Phase 0 — Engine foundation ✅

- [x] `:core` pure JVM — builds and tests without the Android SDK
- [x] JobSpec v1 parse / serialise / round-trip
- [x] Extractor on Jsoup — container scoping, `@attr`, URL absolutisation
- [x] FieldTypes — 10 types, Bengali/Arabic digits
- [x] Typed errors, sysexits codes; zero rows is an error, never silent success
- [x] Provider SPI + registry + `GenericHtmlProvider`
- [x] Smart search — intent parsing, EN + Bengali, offline
- [x] Optional AI layer with NPU→GPU→CPU→deterministic fallback
- [x] **Cross-engine conformance 5/5, byte-identical to Python**
- [x] CI, signing, repository audit → [`AUDIT-PHASE0.md`](AUDIT-PHASE0.md)

## Phase 1 — Dataset, Transform, Dedup, Export ✅

The keystone. Nothing downstream could exist before it.

- [x] `Dataset` / `Record` / `Schema` / `FieldDef` — immutable, typed
- [x] Raw-value preservation — never silently destroy data
- [x] `PartialInfo` — partial completion is first-class
- [x] 13 transforms + reporting pipeline
- [x] Conservative normalisation — non-phones and non-emails left untouched
- [x] Dedup: exact / likely / unique, measured thresholds, never auto-deletes
- [x] `ExportWriter` SPI + 8 writers (CSV, TSV, JSON, NDJSON, YAML, MD, HTML, SQL)
- [x] Export quality: UTF-8 BOM, Bengali, CSV-injection defusal, XSS/SQL escaping
- [x] **76 new tests; 175 total; parity intact**
- [x] Docs: DATA_MODEL, DATA_PIPELINE, EXPORT, TRANSFORM, ARCHITECTURE, JOBSPEC

## Phase 2 — Persistence & job execution ⬜

- [ ] Room schema — Jobs, JobRuns, Datasets, Records, Exports
- [ ] Migrations that never delete user data
- [ ] WorkManager runner, foreground notification
- [ ] Pause / resume / cancel / retry
- [ ] Process-death recovery
- [ ] JobRun state machine incl. `partially_completed`
- [ ] Job history screen

## Phase 3 — Dataset UI ⬜

- [ ] Datasets list, rename, delete, duplicate, merge
- [ ] Virtualised record table (10k+ rows, paged — not all in Compose)
- [ ] Search / filter / sort / select
- [ ] Schema inspector, missing-value indicators
- [ ] Transform UI with before/after preview
- [ ] Dedup review screen
- [ ] Pre-export preview (§32)

## Phase 4 — Download manager ⬜

- [ ] Queue, RetryPolicy, ResumeSupport, StorageResolver, IntegrityChecker
- [ ] States: queued/downloading/paused/completed/failed/cancelled
- [ ] Progress, speed, ETA
- [ ] Safe filenames, duplicate handling, MIME detection
- [ ] Downloads screen; history

## Phase 5 — Export integration ⬜

- [ ] SAF destination picker
- [ ] XLSX writer (needs an Android library)
- [ ] Export history: repeat, open, share, delete
- [ ] Share sheet

## Phase 6 — Extraction UX ⬜

- [ ] WebView tap-to-select picker
- [ ] Selector generation + live preview
- [ ] Pagination execution (`next_link`, `url_pattern`)
- [ ] Detail-page following
- [ ] `render: js`

## Phase 7 — Provider reliability ⬜

- [ ] Health states: available / rate-limited / auth-required / blocked
- [ ] Retry policy, backoff, non-retryable classification
- [ ] Partial-result semantics surfaced in UI
- [ ] Auth abstraction — credentials never in JobSpec, never in logs

## Phase 8 — Diagnostics ⬜

- [ ] Structured event store
- [ ] Automatic redaction of tokens/cookies/keys
- [ ] Diagnostic session + ZIP report
- [ ] FACT / OBSERVATION / POSSIBLE CAUSE separation

## Phase 9 — Security & privacy ⬜

- [ ] Encrypted credential storage
- [ ] Data deletion controls
- [ ] Diagnostic consent; datasets never auto-exported into reports
- [ ] Permission minimisation review

## Phase 10 — Quality ⬜

- [ ] Android instrumentation tests (insets, navigation, WebView)
- [ ] Device tests on Redmi Turbo 4 Pro
- [ ] Failure matrix: offline, 403, 429, invalid HTML, disk full, process death
- [ ] Large dataset tests (10k+)
- [ ] Performance profiling

## Phase 11 — Optional future ⬜

Only after the core is stable: AI-assisted extraction, more exporters
(Parquet/XML/PDF), media processing, scheduling, more providers.

---

## Known gaps

| Gap | Impact | Phase |
|---|---|---|
| No persistence | everything lost on process death | 2 |
| UI is one screen | no navigation to datasets/downloads | 3 |
| No downloads | media results can only be opened in a browser | 4 |
| Insets not consumed | content can sit under the status bar | 3 |
| `room`/`work`/`navigation` deps unused | APK weight, implies absent capability | 2 |
| No XLSX | listed in docs, not yet implemented | 5 |

## Constraint

Dev sandbox has 2 GB RAM; Android builds need 4–8 GB and are OOM-killed
locally. All Android verification runs on GitHub runners. `:core` work is
verified locally in ~25 s — a further reason to keep logic out of `:app`.
