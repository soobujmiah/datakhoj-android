# Roadmap

## Phase 0 — Engine foundation ✅ COMPLETE

- [x] `:core` as a pure-JVM module (builds/tests without Android SDK)
- [x] JobSpec v1 parse / serialise / round-trip
- [x] Extractor on Jsoup — container scoping, `@attr`, URL absolutisation
- [x] FieldTypes coercion — 10 types, Bengali/Arabic digits
- [x] Typed errors with sysexits codes; zero rows is an error, never a silent success
- [x] Provider SPI + registry + `GenericHtmlProvider`
- [x] **Cross-engine conformance: 5/5 byte-identical to Python**
- [x] Provider/coercion unit tests: 32/32
- [x] Gradle scaffolding, CI

## Phase 1 — Make it run on the device
- [ ] `OkHttpClient` implementation of `HttpClient`
- [ ] `Robots.kt` — fetch, cache, `Crawl-delay`
- [ ] Pagination: `next_link`, `url_pattern`
- [ ] Room schema from `spec/db-v1.sql`
- [ ] WorkManager job runner + foreground notification, pause/resume
- [ ] Exporters: CSV, JSON, XLSX, YAML, MD, HTML
- [ ] Storage Access Framework output (no hardcoded `/sdcard`)
- [ ] **Milestone: a job runs end-to-end on the Redmi Turbo 4 Pro**

## Phase 2 — The UI
- [ ] Compose shell, jade/saffron Material 3 theme, dark mode
- [ ] Home: search-first, recent jobs
- [ ] Results: grouped by kind, filter, sort, preview
- [ ] Data table: virtualised, inline edit, per-cell error state
- [ ] Export sheet with format picker
- [ ] **WebView tap-to-select picker** — the flagship feature

## Phase 3 — Providers (the "any data" layer)
- [ ] Web search, generic HTML catalogue
- [ ] Audio, video, subtitles
- [ ] Documents, books, academic papers
- [ ] Datasets, APIs
- [ ] Torrent indexes (magnet + `.torrent`)
- [ ] Software/package registries, code search
- [ ] Contact/OSINT lookup (opt-in, see `docs/LEGAL.md`)
- [ ] User-installable provider packs (JSON, importable)

## Phase 4 — Download system
- [ ] Queue with pause/resume/retry, parallel segments
- [ ] Integrity: size + checksum verification
- [ ] Format conversion hooks
- [ ] Magnet handoff to a torrent client
- [ ] Batch download from a result set

## Phase 5 — Advanced
- [ ] WebView JS rendering for SPA sites
- [ ] Auto-detect: JSON-LD, microdata, OpenGraph, `<table>` inference
- [ ] Scheduled recurring jobs + change detection
- [ ] SQLCipher encrypted database
- [ ] Full Bengali localisation
- [ ] `.dkjob` share/import, phone↔desktop round-trip
