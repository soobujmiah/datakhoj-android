# Transform & Deduplication

## Governing rule

**Never silently destroy data.** Every transform that changes a value keeps the
original in `Record.raw`. Every dedup decision that is not byte-exact is shown
to the user rather than acted on.

## Transforms (§7)

| Transform | Effect |
|---|---|
| `TrimWhitespace` | strip leading/trailing spaces |
| `NormalizeWhitespace` | collapse internal whitespace runs |
| `NormalizeEmail` | lowercase + trim — **only if it matches an email pattern** |
| `NormalizePhone` | strip separators, keep `+` — **only if ≥6 digits** |
| `NormalizeUrl` | trim, drop `#fragment`, drop trailing `/` |
| `NormalizeDate` | ISO-8601 where recognisable |
| `NormalizeNumber` | strip currency symbols and separators |
| `ChangeCase` | lower / upper / title |
| `RemoveEmptyRows` | drop all-blank rows |
| `RenameField` | rename a column |
| `SelectFields` | keep a subset, in order |
| `MergeFields` | combine columns into one |
| `SplitField` | split one column into several |

### Conservative by design

The typed normalisers refuse to touch values that do not look like their type:

```kotlin
NormalizePhone().apply(d)   // "call us"      stays "call us"
NormalizeEmail().apply(d)   // "not-an-email" stays "not-an-email"
```

Tested as `non-phone left intact` / `non-email left intact`. An aggressive
implementation would turn `"call us"` into `""` and lose data permanently.

### Pipeline reporting

```kotlin
val result = TransformPipeline.run(dataset, standardCleanup())
result.totalChanged   // cells modified
result.totalRemoved   // rows dropped
result.steps          // per-transform breakdown for the UI
```

## Deduplication (§8)

Three verdicts, never two:

| Verdict | Meaning | Action |
|---|---|---|
| `UNIQUE` | no match | keep |
| `EXACT_DUPLICATE` | identical after normalisation | safe to auto-remove |
| `LIKELY_DUPLICATE` | similar above threshold | **ask the user** |

```kotlin
val analysis = Deduplicator(keys = listOf("email")).analyze(dataset)
analysis.summary()          // "2 unique, 1 exact duplicate"
analysis.matches            // per-record verdict + similarity + what matched

dedup.applyExact(dataset)   // safe: byte-identical only
dedup.applyAll(dataset)     // requires explicit user consent
dedup.remove(dataset, ids)  // what the UI calls after review
```

### Thresholds — measured, not guessed

```
"mohammed rahman" vs "mohammad rahman"  →  0.684   spelling variant
"sobuj miah"      vs "sobuj miah"       →  1.000   identical
"abc"             vs "xyz"              →  0.000   unrelated
```

| Preset | Value | Use |
|---|---|---|
| `LOOSE` | 0.60 | catches Mohammed/Mohammad; more false positives |
| `BALANCED` | 0.80 | default — case, spacing, punctuation |
| `STRICT` | 0.95 | emails, URLs, IDs |

A single global threshold cannot serve every field, which is why it is a
parameter with documented presets rather than a constant.

### Matching keys

```kotlin
Deduplicator(listOf("email"))              // one field
Deduplicator(listOf("name", "address"))    // composite
Deduplicator()                             // every field
```

Type-aware normalisation runs before comparison, so `"A@B.com "` and
`"a@b.com"` collide as they should.
