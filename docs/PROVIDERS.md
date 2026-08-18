# Writing a Provider

A **provider** teaches DataKhoj a new place to look. This is the extension
point for "we will add many tools later" — a new data source is one class and
one `register()` call, with no change to the UI, runner, or export layer.

## Two ways to add a source

### 1. Data-defined (no code)

For any site whose results are plain HTML, describe it as JSON:

```json
{
  "id": "myindex",
  "name": "My Index",
  "kinds": ["audio"],
  "searchUrl": "https://example.com/search?q={query}&page={page}",
  "container": ".result",
  "fields": {
    "title": ".name",
    "url": ".name@href",
    "directUrl": "a.download@href",
    "sizeText": ".size",
    "format": ".fmt"
  }
}
```

`GenericHtmlProvider` turns that into a working source. Placeholders `{query}`
and `{page}` are substituted; `{query}` is URL-encoded.

**Recognised field names** map onto `SearchResult`:

| Field | Meaning |
|---|---|
| `title` | Display name (**required** — rows without it are dropped) |
| `url` | Landing page |
| `directUrl` | Direct download/stream link |
| `magnet` | Magnet URI |
| `sizeText` | Human size, parsed to bytes (`"1.4 GB"`) |
| `format` | `MP3`, `MKV`, `PDF`… |
| `author` | Artist, uploader, author |
| `snippet` | Description |

Anything else lands in `extra` and is preserved through export.

### 2. Code-defined (full control)

For JSON APIs, auth, pagination quirks, or signed URLs:

```kotlin
class MyApiProvider : SearchProvider {
    override val id = "myapi"
    override val displayName = "My API"
    override val kinds = setOf(DataKind.VIDEO)
    override val trust = ProviderTrust.OFFICIAL_API
    override val requiresAuth = true

    override suspend fun search(query: SearchQuery, http: HttpClient): List<SearchResult> {
        val json = http.getText("https://api.example.com/v1/search?q=${enc(query.text)}")
        return JSONObject(json).getJSONArray("items").map { item ->
            SearchResult(
                title = item.getString("name"),
                url = item.getString("page"),
                kind = DataKind.VIDEO,
                provider = id,
                directUrl = item.optString("stream").ifBlank { null },
                sizeBytes = item.optLong("bytes").takeIf { it > 0 },
                durationSec = item.optInt("seconds").takeIf { it > 0 },
            )
        }
    }
}
```

Register at startup:

```kotlin
ProviderRegistry.register(MyApiProvider())
```

## Contract

| Rule | Why |
|---|---|
| **Never throw for "no results"** | Return `emptyList()`. Throwing means a real failure. |
| **Be cancellable** | The runner may abandon slow providers. Use suspending I/O only. |
| **No shared mutable state** | Providers run concurrently. |
| **Set `trust` honestly** | The UI shows the user where data came from. |
| **Set `requiresAuth`** | Registry skips it until the user adds a key. |
| **Prefer `directUrl`** | Without it a hit cannot be downloaded, only opened. |

A provider that throws is reported to the user and **does not fail the search** —
other providers still return. A dead source degrades results, never destroys them.

## Testing

Providers must be testable without network. Expose a `parse()` taking raw
HTML/JSON so it can be verified against a fixture:

```kotlin
val hits = provider.parse(fixtureHtml, "https://example.com/s", SearchQuery("q"))
assertEquals("Bohemian Rhapsody", hits[0].title)
assertEquals(9856614L, hits[0].sizeBytes)
```

See `core/src/test/kotlin/dev/datakhoj/core/ProviderTestMain.kt` (32 assertions,
all offline).
