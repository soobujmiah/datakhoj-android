# On-Device AI & the NPU

Target device: **Redmi Turbo 4 Pro** — Snapdragon 8s Gen 4 (SM8735), Hexagon NPU.

## The honest answer first

**For ~95% of DataKhoj, the NPU is useless.**

The hot path is: open a socket → wait for bytes → run Jsoup CSS matching →
normalise strings → write a file. That is I/O-bound and branch-heavy. A neural
accelerator multiplies matrices; it cannot make a regex, a DOM walk, or a TCP
round-trip faster. Anyone claiming "NPU-accelerated scraping" is selling
something.

What the NPU *is* good for is the small set of DataKhoj problems that are
**fuzzy rather than mechanical** — and those happen to be the ones where the
app currently feels dumbest.

## Your hardware

Qualcomm's published figures for the 8s Gen 4 (SM8735):

| | |
|---|---|
| NPU | Hexagon, **+44% AI performance** vs 8s Gen 3 |
| Memory | **2× larger shared AI memory** |
| Precision | **INT4 / INT8** |
| Capability | On-device multimodal generative AI, LLMs/LVMs |
| Extra | Sensing Hub adds always-on low-power AI (+60% claimed) |

Qualcomm has **not published an absolute TOPS number** for this NPU. Figures
circulating online are estimates, not specifications — treat them accordingly.

Practical envelope on this class of chip: a **~2 GB INT4 Gemma-class model at
roughly 50–80 tokens/sec on the NPU**, versus 30–50 on the Adreno GPU and a
barely-usable 4–5 on CPU. A ~25 MB INT8 sentence embedder runs in single-digit
milliseconds.

With 12–16 GB LPDDR5 and UFS 4.1, your device is comfortably in the tier that
can host these models. Keep the model under ~30% of RAM.

## What we actually use it for

### 1. Semantic dedup ⭐ best value per megabyte

String dedup collapses `example.com/a` and `example.com/a/`. It does nothing
for these, which are obviously one file to a human:

```
"Bohemian Rhapsody - Queen (Official Video) [1080p]"
"Queen — Bohemian Rhapsody 1080p HD"
"bohemian_rhapsody_queen_1080p.mkv"
```

`SemanticRanker` embeds each result and merges anything above a cosine
similarity of 0.86, keeping the variant that is actually downloadable. A 25 MB
model handling 50 results in a few milliseconds — the user never sees a delay.

### 2. Semantic ranking

Rank by meaning, so "cheap phone" matches a listing titled "budget
smartphone". Keyword overlap cannot do this.

### 3. Intent disambiguation

`IntentParser` resolves ~95% of queries deterministically and offline. For the
rest — bare `"dhaka"` could be places, contacts, or news — `LlmIntentAssist`
asks a local model. **Only** when confidence < 0.55, so the common path never
pays the cost.

### 4. Selector suggestion

For a page with no profile, `LlmSelectorAssist` reads a pruned DOM *skeleton*
(tags and classes only, no text) and proposes a container plus field selectors.
Suggestions are verified against the real document before being shown — a
selector matching nothing is discarded, never presented as fact.

### 5. OCR

Pull text out of screenshots and scanned PDFs. ML Kit, NPU-backed.

## What we deliberately do NOT use it for

| Tempting | Why not |
|---|---|
| "AI-powered extraction" replacing CSS | Non-deterministic, unauditable, slower, and breaks conformance parity with the Python engine |
| Running the LLM on every search | Battery and latency cost for no benefit — the parser already handles it |
| LLM-generated export data | Hallucinated rows in a spreadsheet are worse than no rows |
| NPU for HTTP/parsing | Category error. Wrong kind of work. |

## The governing rule

> **AI is an enhancement, never a dependency.**

Every path degrades cleanly:

```
NPU (Hexagon/QNN) → GPU (Adreno/OpenCL) → CPU → deterministic, no model
```

This is enforced by tests, not just intention. `AiTestMain` includes a
`BrokenEmbedder` that throws on every call:

```
=== BROKEN model (must degrade, not explode) ===
  PASS  survived embedder exception
  PASS  ranking survived failure
  PASS  scores survived failure
```

And the LLM is treated as an untrusted component — temperature 0, tiny token
budget, strict JSON contract, validated reply:

```
=== LLM safety: bad output must never corrupt the result ===
  PASS  non-JSON ignored
  PASS  invalid category ignored
  PASS  lower-confidence answer rejected
  PASS  cannot downgrade a confident parse
```

A hallucinating model can make the guess *no worse* than having no model at all.

## Why parity is unaffected

`spec/conformance/` compares this engine byte-for-byte with the Python engine.
AI touches **ranking, dedup, and suggestion** — never extraction. The rows that
come out of a `.dkjob` are identical whether or not a model is installed. That
is why all 5 conformance cases still pass.

## Implementation plan

Not yet wired to real hardware — the interfaces and fallbacks exist and are
tested; the Android bindings come with the app module.

| Piece | Library | Size |
|---|---|---|
| Embedder | LiteRT + QNN delegate, INT8 sentence model | ~25 MB |
| LLM | LiteRT-LM + `libQnnHtp.so`, Gemma-class INT4 | ~2 GB |
| OCR | ML Kit Text Recognition | bundled |

Notes from the field worth heeding:

* Open-source model weights often **lack the device-specific QNN pre-compiled
  binaries**. Forcing an NPU path without them crashes. Always probe, then fall
  back.
* NNAPI is deprecated — use LiteRT delegates or ONNX Runtime's QNN provider.
* Models are downloaded on demand, never bundled. A 2 GB APK is unacceptable.

## Settings the user controls

```
On-device AI
├── Semantic dedup          [ on ]   ~25 MB · NPU
├── Semantic ranking        [ on ]   uses the same model
├── Smart disambiguation    [ off]   ~2 GB download
├── Selector suggestions    [ off]   needs the LLM
├── OCR                     [ on ]   ML Kit
└── Accelerator          [ Auto ▾ ]  Auto · NPU · GPU · CPU
```

`AiCapability.describe()` reports what is actually running, e.g.
`Running on NPU · gemma-3n-int4 · reasoning, semantic` — determined by probing
for the QNN driver at runtime, never assumed from the chipset name.
