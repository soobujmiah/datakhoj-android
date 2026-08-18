# Phase 0 — Repository Audit

**Date:** 2026-08-18 · **Commit:** `v0.1.0` · **Method:** static inspection + executing every test suite.

---

## 1. Implementation map

### ALREADY WORKING — preserve, do not rewrite

| Component | LOC | Evidence |
|---|---|---|
| `core/extract/Extractor.kt` | 230 | 5/5 conformance, byte-identical to Python engine |
| `core/extract/FieldTypes.kt` | 159 | 10 types, Bengali/Arabic digits, pinned by corpus |
| `core/model/JobSpec.kt` | 241 | parse/serialise/round-trip tested |
| `core/model/Errors.kt` | 69 | typed, sysexits codes |
| `core/provider/*` | 347 | registry + SPI + generic HTML provider, 32/32 |
| `core/intent/*` | 590 | 33/33, EN + Bengali |
| `core/ai/*` | 484 | 29/29 incl. broken-model resilience |
| `app/net/AndroidHttpClient.kt` | 76 | OkHttp, compiles in CI |
| `app/net/DuckDuckGoProvider.kt` | 95 | ad-filtered, working |
| `app/ui/Theme.kt` | 84 | jade/saffron M3, light + dark |
| CI + signing | — | signed APK produced, apksigner verified |

**Total verified: 99 passing assertions across 4 suites.**

### NEEDS COMPLETION

| Component | Gap |
|---|---|
| `app/ui/SearchScreen.kt` | Single screen, no navigation. `Scaffold` does not consume `WindowInsets` → content can sit under the status bar. |
| `docs/ROADMAP.md` | Claims Phase 1 items unstarted that are in fact done (`OkHttpClient`); no dataset/diagnostics phases. |

### MISSING ENTIRELY — zero files, zero packages

Confirmed by package scan:

```
export         files=0   ← MISSING
storage        files=0   ← MISSING
download       files=0   ← MISSING
transform      files=0   ← MISSING
diagnostics    files=0   ← MISSING
validate       files=0   ← MISSING
dataset        files=0   ← MISSING
```

This is the finding that matters. **The app can search but cannot keep, clean,
export, or download anything.** Nothing survives process death.

### NEEDS REFACTOR

| Issue | Detail |
|---|---|
| `SearchScreen.kt` (343 lines) | ViewModel, networking wiring, and three UI components in one file. Will not scale to 9 screens. |
| Results are transient | `SearchResult` lives in a `mutableStateOf` list. No persistence layer, so every process death loses everything. |
| No Dataset concept | Results are provider hits, not a typed dataset with a schema. The roadmap's dataset-first requirement has no foundation yet. |

### Dead weight — declared but never used

`room`, `work-runtime`, `documentfile`, `navigation-compose` are all in
`app/build.gradle.kts` with **zero** usages in source. They inflate the APK and
imply capability that does not exist.

### Broken documentation links

`README.md` links to three files that do not exist:

```
DEAD docs/ARCHITECTURE.md
DEAD docs/EXPORT.md
DEAD docs/JOBSPEC.md
```

### CORE-ONLY vs ANDROID-ONLY vs DEVICE-DEPENDENT

| Layer | Placement | Rule |
|---|---|---|
| Dataset, Record, Schema, transforms, dedup, export *writers* | `:core` | pure JVM, unit-testable, parity-checked |
| SAF, Room, WorkManager, Compose, WebView, notifications | `:app` | Android only |
| NPU/QNN, adb install, insets on a real cutout | device | cannot be verified in CI |

**Architectural rule confirmed:** export *formatting* is pure logic → `:core`.
Export *destination* (SAF `Uri`) is Android → `:app`. This keeps writers
testable without an emulator.

---

## 2. Priority judgement

The roadmap lists 48 sections. Attempting them in one pass would produce
unverifiable code. Ordering by *what unblocks the most*:

1. **Dataset + Records + Schema** — nothing else can exist without it. Export
   needs a dataset. Preview needs records. Dedup needs a schema. Diagnostics
   needs run history. This is the keystone.
2. **Transform + dedup** — required by §7, §8, §32; pure logic, fully testable.
3. **Export contract + writers** — the "scrape once, export many" requirement
   (§5) is impossible until 1 and 2 exist.
4. Storage (Room) → 5. WorkManager → 6. Downloads → 7. UI shell → 8. Diagnostics.

Items 1–3 are `:core`, so they can be built **and proven** in this environment.
Items 4–7 need a device build to verify honestly.

---

## 3. Phase 1 scope (this pass)

Deliberately narrow, per §46 (no uncontrolled scope expansion):

- [x] `core/dataset/` — Dataset, Record, Schema, FieldDef, DatasetBuilder
- [x] `core/transform/` — 13 transforms, raw-value preservation
- [x] `core/dedup/` — exact/likely/unique with user-defined keys
- [x] `core/export/` — ExportWriter SPI + CSV, JSON, Markdown, HTML, YAML, SQL
- [x] Tests for all of the above
- [ ] Room, WorkManager, Downloads, UI shell → Phase 2+, documented not stubbed

**Explicitly deferred with extension points left in place:** Parquet/XML/PDF
writers, media processing, scheduling, plugin marketplace.

---

## 4. Constraint discovered

The dev sandbox has **2 GB RAM**; the Gradle daemon is OOM-killed by Android
builds (AGP + Kotlin + Compose + R8 need 4–8 GB). All Android verification must
run on GitHub runners. Pure-JVM `:core` work compiles and tests locally in
~20 s, which is a further reason to keep logic in `:core`.

Per §47: **no releases, no tags, no APK publication during this phase.**
