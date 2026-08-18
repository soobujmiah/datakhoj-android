package dev.datakhoj.core

import dev.datakhoj.core.provider.*
import dev.datakhoj.core.provider.impl.GenericHtmlProvider
import dev.datakhoj.core.extract.FieldTypes
import dev.datakhoj.core.model.FieldType

/** Unit checks for the provider layer and type coercion, no network needed. */
object ProviderTestMain {
    private var pass = 0
    private var fail = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) { println("  PASS  $name"); pass++ }
        else { println("  FAIL  $name  $detail"); fail++ }
    }

    private fun <T> eq(name: String, want: T, got: T) =
        check(name, want == got, "want=$want got=$got")

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== GenericHtmlProvider (data-defined source) ===")
        val html = """
        <html><body>
          <div class="result">
            <a class="name" href="/track/1">Bohemian Rhapsody</a>
            <a class="dl" href="/dl/1.mp3">get</a>
            <span class="size">9.4 MB</span><span class="fmt">MP3</span>
          </div>
          <div class="result">
            <a class="name" href="/track/2">Under Pressure</a>
            <a class="dl" href="https://cdn.x.com/2.flac">get</a>
            <span class="size">31.2 MB</span><span class="fmt">FLAC</span>
          </div>
        </body></html>""".trimIndent()

        val p = GenericHtmlProvider(
            id = "demo-audio", displayName = "Demo Audio", kinds = setOf(DataKind.AUDIO),
            searchUrl = "https://audio.example.com/s?q={query}",
            container = ".result",
            fields = mapOf(
                "title" to ".name", "url" to ".name@href",
                "directUrl" to "a.dl@href", "sizeText" to ".size", "format" to ".fmt",
            ),
        )
        val hits = p.parse(html, "https://audio.example.com/s", SearchQuery("queen", limit = 10))

        eq("provider returns 2 hits", 2, hits.size)
        eq("title parsed", "Bohemian Rhapsody", hits[0].title)
        eq("relative url absolutised", "https://audio.example.com/track/1", hits[0].url)
        eq("direct download link", "https://audio.example.com/dl/1.mp3", hits[0].directUrl)
        eq("absolute cdn link kept", "https://cdn.x.com/2.flac", hits[1].directUrl)
        eq("size parsed to bytes", 9856614L, hits[0].sizeBytes)
        eq("human size", "9.4 MB", hits[0].humanSize())
        eq("format", "FLAC", hits[1].format)
        check("marked downloadable", hits[0].isDownloadable)
        eq("kind tagged", DataKind.AUDIO, hits[0].kind)

        println()
        println("=== ProviderRegistry ===")
        ProviderRegistry.register(p)
        eq("registered", 1, ProviderRegistry.all().size)
        eq("lookup by kind", 1, ProviderRegistry.forKind(DataKind.AUDIO).size)
        eq("no video provider", 0, ProviderRegistry.forKind(DataKind.VIDEO).size)
        check("candidate for audio query",
            ProviderRegistry.candidates(SearchQuery("x", setOf(DataKind.AUDIO))).isNotEmpty())
        check("not candidate for video query",
            ProviderRegistry.candidates(SearchQuery("x", setOf(DataKind.VIDEO))).isEmpty())
        eq("availableKinds", listOf(DataKind.AUDIO), ProviderRegistry.availableKinds())

        val dupes = listOf(
            SearchResult("A", "https://x.com/1", DataKind.AUDIO, "p1"),
            SearchResult("A", "https://x.com/1/", DataKind.AUDIO, "p2", directUrl = "https://x.com/a.mp3"),
            SearchResult("B", "https://x.com/2", DataKind.AUDIO, "p1"),
        )
        val dd = ProviderRegistry.dedupe(dupes)
        eq("dedupe collapses trailing slash", 2, dd.size)
        check("dedupe prefers downloadable", dd[0].isDownloadable)

        println()
        println("=== Size parsing ===")
        eq("GB", 1503238553L, GenericHtmlProvider.parseSize("1.4 GB"))
        eq("GiB alias", 2147483648L, GenericHtmlProvider.parseSize("2 GiB"))
        eq("comma", 1258496L, GenericHtmlProvider.parseSize("1,229 KB"))
        eq("junk -> null", null, GenericHtmlProvider.parseSize("unknown"))

        println()
        println("=== Field coercion parity rules ===")
        eq("bengali digits", "1250.00", FieldTypes.coerce("৳১,২৫০.০০", FieldType.CURRENCY))
        eq("phone keeps +", "+8801712345678", FieldTypes.coerce(" +880 1712-345678 ", FieldType.PHONE))
        eq("phone local", "01812345679", FieldTypes.coerce("01812 345 679", FieldType.PHONE))
        eq("email lower", "a@b.com", FieldTypes.coerce("  A@B.CoM ", FieldType.EMAIL))
        eq("date iso", "2024-03-05", FieldTypes.coerce("05/03/2024", FieldType.DATE))
        eq("date words", "2024-01-15", FieldTypes.coerce("15 January 2024", FieldType.DATE))
        eq("bool stock", "false", FieldTypes.coerce("Out of stock", FieldType.BOOLEAN))
        eq("regex group", "1999", FieldTypes.coerce("Founded 1999", FieldType.NUMBER, regex="(\\d{4})"))
        eq("default applied", "n/a", FieldTypes.coerce("", FieldType.TEXT, default="n/a"))
        eq("whitespace", "a b c", FieldTypes.coerce("  a   b\n\tc ", FieldType.TEXT))

        println()
        println("=".repeat(60))
        println("Provider + coercion tests: $pass passed, $fail failed")
        println("=".repeat(60))
        if (fail > 0) kotlin.system.exitProcess(1)
    }
}
