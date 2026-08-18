# Smart Search — one box, automatic source selection

You type whatever you mean. DataKhoj works out *what kind of thing* you want,
*which sources can serve it*, and routes the query automatically.

```
"500 mp3 arijit singh"     → Search 500 music & audio (mp3) — "arijit singh"
"৫০০ গান ডাউনলোড"           → Search 500 music & audio — "গান ডাউনলোড"
"interstellar 1080p mkv"   → Search video (mkv) — "interstellar 1080p"
"laptop prices from daraz.com.bd" → Search products & prices from daraz.com.bd
"https://shop.com/items"   → Scrape that page
"https://cdn.com/song.mp3" → Download that file
"10.1038/nature12373"      → Search papers & research
```

## Why it is not an LLM (by default)

A model in the search path would mean: an API key, a network round-trip on
every keystroke, per-query cost, and a tool that stops working offline or when
a bill goes unpaid. For a personal tool that is the wrong trade.

`IntentParser` is **deterministic, offline, and instant** — pure string
analysis, microseconds, no key. It handles the overwhelming majority of real
queries because file extensions and domain vocabulary are extremely strong
signals.

An LLM remains available as an **optional refinement** through the `LlmAssist`
interface, consulted *only* when the parser reports low confidence and *only*
if you configure one. A missing key or dead network degrades quality, never
functionality.

## How it decides

Evidence-based scoring, not first-match-wins. Every signal contributes weight
and the winner must beat the runner-up to earn high confidence.

| Signal | Weight | Example |
|---|---|---|
| Magnet URI | 4.0 | `magnet:?xt=urn:btih:…` → torrent |
| File extension | 3.0 | `.mp3` → audio, `.parquet` → dataset |
| DOI pattern | 3.0 | `10.1038/…` → academic |
| Your custom hints | 2.5 | user-defined keyword → kind |
| Multi-word phrase | 2.5 | "research paper", "মোবাইল নাম্বার" |
| Keyword | 2.0 | "song", "গান", "torrent", "dataset" |
| Structural | 2.0 | looks like an email / phone number |

~200 keywords across 18 data kinds, English and Bengali.

### Confidence

| Score gap | Confidence | UI behaviour |
|---|---|---|
| Forced via `kind:` operator | 1.00 | run immediately |
| Strong hit, no competition | 0.95 | run immediately |
| Clear winner (gap ≥ 2.0) | 0.85 | run, show the chip |
| Narrow winner (gap ≥ 1.0) | 0.70 | run, show the chip |
| Tie | 0.50 | **ask first** |
| No signal at all | 0.35 | **ask first**, default to web |

Below 0.55 the plan is marked `needsConfirmation` and the UI offers a one-tap
correction instead of guessing wrong silently.

### It explains itself

Every intent carries `reasoning`, surfaced in the UI:

```
"500 mp3 arijit singh"
  · Wants 500 results
  · 'mp3' is a music & audio format
```

So when it guesses wrong you can see *why* and fix it in one tap — rather than
staring at an opaque wrong answer.

## Operators

For when you want to be explicit:

| Operator | Effect |
|---|---|
| `site:example.com` / `from example.com` | restrict to a domain |
| `kind:audio` | force the data kind (confidence 1.0) |
| `as csv` / `to xlsx` / `export to json` | set export format |
| `500` / `৫০০` / `500 jon` / `hundred` | result count |

## Bengali support

First-class, not transliteration. Note a subtlety that cost a real bug: Bengali
vowel signs (মাত্রা) are Unicode **combining marks** (`\p{M}`), not letters. A
tokenizer built on `\p{L}` alone shreds "গান" into fragments and matches
nothing. The tokenizer includes `\p{M}`, and there is a regression test.

Bengali numerals (`০-৯`) and Arabic-Indic (`٠-٩`) are normalised to ASCII
before parsing, so `৫০০` is a count like `500`.

## Flow

```kotlin
val smart = SmartSearch(IntentParser(), ProviderRegistry, llm = null)

val plan = smart.plan("500 mp3 arijit singh")
println(plan.explain())
// Search 500 music & audio (mp3) — "arijit singh"
//   · Wants 500 results
//   · 'mp3' is a music & audio format
// Sources: Audio Index, Generic Web

if (!plan.needsConfirmation) {
    val (_, results) = smart.search("500 mp3 arijit singh", http)
}
```

`plan()` is side-effect free — it tells you what *would* happen so the UI can
confirm before spending network.

### When no source exists

If nothing is registered for the detected kind, the plan says so explicitly and
falls back to generic web search, rather than returning empty:

```
No dedicated torrents & magnets source installed —
falling back to Generic Web
```

## Ranking

Results are ordered by usefulness for *that* intent:

1. Downloadable (has `directUrl` or `magnet`) first
2. Matches a requested format (`mp3` when you asked for mp3)
3. Matches the detected kind
4. Title word overlap with your subject

## Extending

Add vocabulary without touching code — `IntentParser` takes custom hints:

```kotlin
IntentParser(customHints = mapOf(
    "beats"   to DataKind.AUDIO,
    "dataset" to DataKind.DATASET,
    "নথি"      to DataKind.DOCUMENT,
))
```

Adding a new `DataKind` is one enum entry plus keywords; every provider that
declares it becomes reachable from the search box automatically.

## Tests

`core/src/test/kotlin/dev/datakhoj/core/IntentTestMain.kt` — **33 assertions**,
all offline: media, documents, datasets, torrents, software, code, contacts,
Bengali, URL scrape-vs-download, operators, subject cleaning, ambiguity
detection, and explainability.

```
./gradlew :core:intentTests
```
