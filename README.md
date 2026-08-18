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
| Gradle / Android shell | 🟡 Scaffolded | not yet built on device |
| Compose UI | ⬜ Not started | design in `docs/DESIGN.md` |
| Download manager | ⬜ Not started | — |
| WorkManager runner | ⬜ Not started | — |

Everything marked ✅ was compiled and executed in CI, not just written.

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
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Module layout, data flow, threading |
| [`docs/SMART_SEARCH.md`](docs/SMART_SEARCH.md) | One search box → automatic source selection |
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

## Licence

MIT © soobujmiah. Third-party licences in [`docs/LEGAL.md`](docs/LEGAL.md).
