# DataKhoj for Android

**A personal, unrestricted universal data collector.**
Search any kind of data, extract it into structure, export it in the format you need.

[![Conformance](https://github.com/soobujmiah/datakhoj-android/actions/workflows/ci.yml/badge.svg)](https://github.com/soobujmiah/datakhoj-android/actions)

---

## Status

**Pre-alpha — engine works, UI is scaffolding.**

| Component | State | Verified |
|---|---|---|
| Extraction engine (Jsoup) | ✅ Working | 5/5 conformance, byte-identical to Python engine |
| Field type coercion | ✅ Working | 32/32 unit tests |
| JobSpec v1 parse/serialise | ✅ Working | round-trip tested |
| Provider plug-in system | ✅ Working | registry + generic HTML provider tested |
| **Smart search (auto source)** | ✅ Working | 33/33 intent tests, EN + Bengali |
| On-device AI layer (NPU) | ✅ Interfaces + fallbacks | 29/29 incl. broken-model resilience |
| **Dataset / Record / Schema** | ✅ Working | 76 tests — the keystone model |
| **Transform (13 cleaners)** | ✅ Working | conservative, raw values preserved |
| **Deduplication** | ✅ Working | exact/likely/unique, never auto-deletes |
| **Export (8 formats)** | ✅ Working | CSV·TSV·JSON·NDJSON·YAML·MD·HTML·SQL |
| **Export contract** | ✅ Working | ExportRequest→Engine→Writer→Result, validated |
| **Persistence boundary** | ✅ Working | domain stays storage-independent |
| Gradle / Android shell | 🟡 Scaffolded | signed APK builds in CI |
| Compose UI | 🟡 One screen | search only; no navigation yet |
| **Persistence (Room)** | ✅ Working | 85 contract + 4 device tests on emulator |
| Download manager | ⬜ Phase 4 | — |
| WorkManager runner | ⬜ Phase 2b | — |

Everything marked ✅ was compiled and executed, not just written —
**326 JVM assertions + 89 on-emulator**, all pure JVM, no emulator required.

---

## Companion repository

| Repo | Role |
|---|---|
| [`soobujmiah/datakhoj`](https://github.com/soobujmiah/datakhoj) | Python engine — Linux, Termux, servers, cron. **Still maintained.** |
| `soobujmiah/datakhoj-android` (this) | Kotlin engine + Android app |

Both implement **JobSpec v1** and share `spec/conformance/`. A `.dkjob` file
built by tapping on the phone runs unchanged on a Linux box, and the SQLite
database is portable in both directions.

Neither engine may merge a change that breaks the shared corpus — that is the
mechanism keeping them identical. See [`docs/PARITY.md`](docs/PARITY.md).

---

## What it does

DataKhoj is built around three ideas:

**1. Find the source.** Ask for a *kind* of thing — music, video, torrents,
documents, datasets, papers, contacts, prices — and DataKhoj queries every
registered provider that can serve it.

**2. Extract structure.** Point it at any page and pull structured rows out
using CSS selectors, either written by hand or captured by **tapping elements
on screen**.

**3. Export properly.** CSV, XLSX, JSON, YAML, Markdown, HTML, SQLite, plus
direct file download for media. UTF-8 BOM by default so Bengali survives Excel.

### Supported data kinds

`web` · `audio` · `video` · `image` · `document` · `dataset` · `torrent` ·
`software` · `code` · `academic` · `contact` · `social` · `news` · `product` ·
`geo` · `font` · `subtitle` · `api`

Each is served by one or more providers. Adding a new kind is one enum entry.

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  spec/            JobSpec v1 schema + conformance corpus  │
│                   Shared with the Python engine           │
└──────────────────────────────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────┐
│  :core          PURE JVM — no Android SDK                 │
│  ├── model/      JobSpec, FieldSpec, typed errors         │
│  ├── extract/    Extractor (Jsoup), FieldTypes, JobRunner │
│  ├── provider/   SearchProvider, Registry, impls          │
│  ├── export/     CSV/JSON/XLSX/… writers                  │
│  └── net/        HttpClient abstraction                   │
└───────────────────────────┬──────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────┐
│  :app           Android — Compose, Room, WorkManager      │
│                 WebView tap-to-select picker              │
└──────────────────────────────────────────────────────────┘
```

**`:core` is deliberately a plain JVM module.** It has no Android dependency,
so the entire engine compiles and its tests run on any machine — including CI
without an emulator. This is why the engine could be verified before a single
line of UI existed.

---

## Target device

Primary test hardware: **Xiaomi Redmi Turbo 4 Pro**

| | |
|---|---|
| SoC | Qualcomm SM8735 **Snapdragon 8s Gen 4** (4 nm) |
| CPU | 1×3.21 GHz Cortex-X4 + 3×3.0 + 2×2.8 + 2×2.0 Cortex-A720 |
| GPU | Adreno 825 |
| RAM / Storage | 12–16 GB LPDDR5 · 256 GB–1 TB UFS 4.1 |
| Display | 6.83″ AMOLED 1280×2772, 120 Hz |
| OS | Android 15, HyperOS 2 |
| ABI | `arm64-v8a` only |

Build config targets `compileSdk 35` / `targetSdk 35`, `minSdk 26`,
`arm64-v8a` — no wasted APK weight on ABIs this device cannot run.

---

## Build

```bash
git clone https://github.com/soobujmiah/datakhoj-android
cd datakhoj-android

./gradlew :core:test          # engine unit tests
./gradlew :core:conformance   # cross-engine parity check
./gradlew :app:assembleDebug  # APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Android SDK 35.

---

## Documentation

| Doc | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Module layout, data flow, the `:core`/`:app` rule |
| [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) | Dataset, Record, Schema — why a CSV is not a dataset |
| [`docs/DATA_PIPELINE.md`](docs/DATA_PIPELINE.md) | Discover → … → Export, with status per stage |
| [`docs/TRANSFORM.md`](docs/TRANSFORM.md) | Cleaning and deduplication |
| [`docs/PERSISTENCE.md`](docs/PERSISTENCE.md) | Repository boundary; why it precedes Room |
| [`docs/SMART_SEARCH.md`](docs/SMART_SEARCH.md) | One search box → automatic source selection |
| [`docs/NPU.md`](docs/NPU.md) | What the NPU does and does not accelerate |
| [`docs/PROVIDERS.md`](docs/PROVIDERS.md) | Writing a new data source |
| [`docs/JOBSPEC.md`](docs/JOBSPEC.md) | `.dkjob` format reference |
| [`docs/PARITY.md`](docs/PARITY.md) | How the two engines stay identical |
| [`docs/EXPORT.md`](docs/EXPORT.md) | Export formats and download pipeline |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Phased plan |
| [`docs/LEGAL.md`](docs/LEGAL.md) | Licensing, distribution, responsible use |

---

## Distribution

Personal build, distributed as a **sideloaded APK** — not Google Play.
This is deliberate: it keeps the tool unrestricted for personal research use.
See [`docs/LEGAL.md`](docs/LEGAL.md) for what that means in practice.

---

## Licence — AGPL-3.0-or-later

Copyright © 2026 soobujmiah. See [`LICENSE`](LICENSE) and
[`COPYRIGHT.md`](COPYRIGHT.md).

**This is copyleft, not permissive.** The source is public so you can read it,
learn from it, and build on it — not so it can be taken closed-source.

| You may | You must |
|---|---|
| Use it privately, for anything | Keep the copyright notices |
| Study and modify it | Release your changes under AGPL-3.0 |
| Redistribute, even commercially | State what you changed |
| Fork it | **Rename it** — see below |

**§13 (network clause):** run a modified version as a hosted service and you
must offer its full source to that service's users. This is what MIT does not
do, and the reason this project is not MIT.

### Trademark — not licensed

The AGPL covers code, not names. **"DataKhoj"**, **ডেটাখোঁজ**, the jade
magnifier logo, and the app ID `dev.datakhoj.app` are trademarks and are
excluded from the licence grant.

Fork the code, not the identity. If you fork, rename. Same rule the Linux
kernel, Firefox, and Chromium use.

Third-party licences: [`docs/LEGAL.md`](docs/LEGAL.md).
