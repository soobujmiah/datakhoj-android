# Architecture

## Module rule

```
:core   pure JVM — no Android SDK, ever
:app    Android — Compose, Room, WorkManager, SAF, WebView
```

`:core` has **no Android dependency**, deliberately. Consequences:

- the entire engine compiles and tests on any machine in ~25 s
- CI verifies 175 assertions without an emulator
- the Python engine can be checked against it byte-for-byte
- a desktop or server build stays possible later

Do not move an Android type into `:core` for convenience. If a feature needs
one, split it: pure logic in `:core`, platform binding in `:app`.

**Worked example — export.** Formatting a CSV is pure logic; writing it to a
user-chosen folder needs SAF. So `ExportWriter` takes an `OutputStream`.
`:core` formats, `:app` supplies the destination.

## Layout

```
:core
├── model/       JobSpec v1, FieldType, typed errors
├── extract/     Extractor (Jsoup), FieldTypes, JobRunner
├── dataset/     Dataset, Record, Schema, FieldDef      ← keystone
├── transform/   13 cleaning transforms + pipeline
├── dedup/       exact / likely / unique classification
├── export/      ExportWriter SPI + 8 writers
├── repository/  persistence boundary — keeps the domain storage-free
├── provider/    SearchProvider SPI, registry, generic HTML
├── intent/      IntentParser, SmartSearch, QueryIntent
└── ai/          optional NPU layer (never a dependency)

:app
├── net/         OkHttp client, DuckDuckGo provider
├── ui/          Compose theme + screens
└── MainActivity, DataKhojApp
```

## Data flow

```
                    ┌───────────┐
   user input ────► │  Intent   │  offline, deterministic
                    └─────┬─────┘
                          ▼
                    ┌───────────┐
                    │ Providers │  registry, partial results
                    └─────┬─────┘
                          ▼
                    ┌───────────┐
                    │   Fetch   │  OkHttp (:app)
                    └─────┬─────┘
                          ▼
                    ┌───────────┐
                    │  Extract  │  Jsoup, JobSpec v1
                    └─────┬─────┘
                          ▼
                    ┌───────────┐
                    │  Dataset  │  ← the pivot: rows become data
                    └─────┬─────┘
              ┌───────────┼───────────┐
              ▼           ▼           ▼
        ┌──────────┐ ┌────────┐ ┌──────────┐
        │Transform │ │ Dedup  │ │  Stats   │
        └────┬─────┘ └───┬────┘ └────┬─────┘
             └───────────┼───────────┘
                         ▼
                   ┌───────────┐
                   │  Export   │  8 formats, one dataset
                   └───────────┘
```

The `Dataset` node is why the same collection can be exported repeatedly
without re-scraping (§5).

## Parity

`:core` extraction must stay byte-identical to the Python engine at
`soobujmiah/datakhoj`. Enforced by `spec/conformance/` running on both sides;
CI fails on divergence. See [`PARITY.md`](PARITY.md).

The layers added in Phase 1 (dataset, transform, dedup, export) are **Android-side
only** and do not affect parity — extraction output is unchanged, which is why
all 5 conformance cases still pass.

## Testing

| Suite | Assertions | Runtime |
|---|---|---|
| Conformance (cross-engine) | 5 | <1 s |
| Provider + coercion | 32 | <1 s |
| Intent parser | 33 | <1 s |
| AI layer | 29 | <1 s |
| Dataset / transform / dedup / export | 76 | <1 s |
| Export + persistence contracts | 56 | <1 s |
| **Total** | **231** | **~30 s incl. compile** |

All pure JVM. No emulator, no network, no device.

## Deliberate constraints

| Constraint | Reason |
|---|---|
| No Chaquopy | `lxml`/`selectolax` have no Android wheels |
| AI optional | app must work with no model installed |
| Immutable datasets | undo, preview, no cross-screen mutation |
| `OutputStream` not `File` | keeps export testable off-device |
| Builds on CI, not locally | dev sandbox has 2 GB RAM; AGP needs 4–8 GB |
