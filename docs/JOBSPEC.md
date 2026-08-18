# JobSpec v1

The portable job definition. A `.dkjob` written on Android runs unchanged on
the Python engine and vice versa.

Normative schema: [`../spec/jobspec-v1.schema.json`](../spec/jobspec-v1.schema.json).
Parity enforced by [`../spec/conformance/`](../spec/conformance/).

## Minimal example

```json
{
  "spec_version": 1,
  "name": "Product grid",
  "source": { "url": "https://shop.example.com/list/" },
  "extract": {
    "container": ".product-card",
    "fields": [
      { "name": "title", "selector": ".product-title" },
      { "name": "price", "selector": ".price", "type": "currency" },
      { "name": "link",  "selector": "a@href",  "type": "url" }
    ]
  }
}
```

## Selector syntax

| Form | Meaning |
|---|---|
| `.price` | text content |
| `a@href` | attribute (href/src auto-absolutised) |
| `.tag@textall` | join every match with `" | "` |
| `.body@html` | inner HTML |

Field selectors resolve **inside** `container`, so rows never bleed. Omit
`container` and the whole document is one row.

## Field types

`text` `number` `currency` `date` `url` `email` `phone` `boolean` `list` `image`

Coercion rules are shared with the Python engine and pinned by the conformance
corpus. See [`TRANSFORM.md`](TRANSFORM.md).

## Sections

| Key | Purpose |
|---|---|
| `source` | url, method, headers, `render: none\|js` |
| `extract` | container + fields |
| `pagination` | `none\|next_link\|url_pattern\|load_more\|infinite_scroll` |
| `limits` | max_rows, delay_ms, concurrency, timeout_ms, retry_max |
| `policy` | respect_robots, user_agent |
| `output` | formats, dedup_keys |

## Versioning

`spec_version` is checked on load. An engine that does not implement a version
**refuses the file** rather than guessing:

```
JobSpec version 99 is not supported by this engine (implements v1).
```

## Status

| Capability | State |
|---|---|
| Parse / serialise / round-trip | ✅ |
| Container + field extraction | ✅ 5/5 parity |
| Type coercion | ✅ |
| `pagination` | 🟡 modelled, execution Phase 6 |
| `limits.concurrency` | 🟡 modelled, unused |
| `render: js` | ⬜ Phase 6 (WebView) |
