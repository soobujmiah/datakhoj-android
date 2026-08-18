# Engine Parity

Two engines implement DataKhoj:

| Engine | Repo | Stack |
|---|---|---|
| Python | [`soobujmiah/datakhoj`](https://github.com/soobujmiah/datakhoj) | httpx + selectolax, Linux/Termux |
| Kotlin | this repo | OkHttp + Jsoup, Android |

They must produce **byte-identical output** for identical input. Not "roughly
the same" — identical. Otherwise a job built on the phone silently yields
different data on the desktop, and the tool becomes untrustworthy.

## The mechanism

`spec/conformance/cases/` holds triples:

```
NN-name.html            input document
NN-name.job.json        JobSpec v1
NN-name.expected.json   rows BOTH engines must produce
```

* Python runs them via `tests/test_conformance.py`
* Kotlin runs them via `ConformanceMain` / `./gradlew :core:conformance`
* CI diffs `python-results.json` against `kotlin-results.json` and **fails the
  build on any difference**

## Verified

Last full cross-check — all five cases byte-identical:

```
identical: 01-product-grid   (container rows, relative+absolute URL resolution)
identical: 02-table          (header-row drop, email lowercase, +880 phone)
identical: 03-detail         (no container, regex group, protocol-relative URL)
identical: 04-required       (required-field drop, default value, list join)
identical: 05-nomatch        (NoResults error, exit 66, diagnostic present)

RESULT: 5 cases, 0 divergent
```

## Workflow for behaviour changes

**Add the case first.**

1. Write `.html` + `.job.json` + `.expected.json` for the new rule
2. Both suites now fail — correct; you have specified something unimplemented
3. Implement in Python → Python green, Kotlin red
4. Implement in Kotlin → both green → merge

Never fix a bug in one engine alone. The fix is *a case plus two
implementations*, otherwise drift is reintroduced by hand.

The corpus only grows; a test asserts it never shrinks.

## Shared vs. native

| Layer | Shared? |
|---|---|
| `.dkjob` format | ✅ byte-identical |
| Extraction semantics | ✅ enforced by corpus |
| Field coercion | ✅ enforced by corpus |
| SQLite schema | ✅ same file works on both |
| Export bytes | ✅ enforced |
| HTTP client | ❌ httpx vs OkHttp |
| HTML parser | ❌ selectolax vs Jsoup |
| JS rendering | ❌ Playwright vs WebView |
| UI | ❌ Typer/Rich vs Compose |

Sharing *semantics* rather than *code* is the point. Sharing code would force
Chaquopy, and Chaquopy cannot run `lxml` or `selectolax` on Android.
