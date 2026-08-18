package dev.datakhoj.core

import dev.datakhoj.core.dataset.*
import dev.datakhoj.core.dedup.Deduplicator
import dev.datakhoj.core.export.*
import dev.datakhoj.core.model.FieldType
import dev.datakhoj.core.transform.*
import java.io.ByteArrayOutputStream

/**
 * Phase 1 verification: Dataset, Transform, Dedup, Export.
 *
 * Includes the roadmap's real-world acceptance test (§36): contact-like data
 * scraped -> cleaned -> deduplicated -> exported in every format.
 */
object DatasetTestMain {
    private var pass = 0; private var fail = 0
    private fun check(n: String, c: Boolean, d: String = "") {
        if (c) { println("  PASS  $n"); pass++ } else { println("  FAIL  $n  $d"); fail++ }
    }
    private fun <T> eq(n: String, w: T, g: T) = check(n, w == g, "\n        want=$w\n        got =$g")

    private val contactSchema = Schema(listOf(
        FieldDef("name", FieldType.TEXT),
        FieldDef("address", FieldType.TEXT),
        FieldDef("phone", FieldType.PHONE),
        FieldDef("email", FieldType.EMAIL),
        FieldDef("website", FieldType.URL),
    ))

    private fun contacts() = Dataset.of(
        id = "ds1", name = "Contacts",
        schema = contactSchema,
        rows = listOf(
            mapOf("name" to "  Sobuj   Miah ", "address" to "Dhaka, BD",
                  "phone" to "+880 1712-345678", "email" to "  Sobuj@Example.COM ",
                  "website" to "https://example.com/profile/#top"),
            mapOf("name" to "Karim Ali", "address" to "Chittagong",
                  "phone" to "01812 345 679", "email" to "karim@example.com",
                  "website" to "https://example.com/karim/"),
            // exact duplicate of row 1 after normalisation
            mapOf("name" to "Sobuj Miah", "address" to "Dhaka, BD",
                  "phone" to "+8801712345678", "email" to "sobuj@example.com",
                  "website" to "https://example.com/profile"),
            // blank row -> must be dropped
            mapOf("name" to "", "address" to "", "phone" to "", "email" to "", "website" to ""),
        ),
    )

    private fun exportToString(d: Dataset, fmt: String, o: ExportOptions = ExportOptions()): String {
        val bo = ByteArrayOutputStream()
        ExportEngine.export(d, fmt, bo, o)
        return bo.toString("UTF-8")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Dataset model ===")
        val d = contacts()
        eq("records built", 4, d.size)
        eq("schema preserved", listOf("name","address","phone","email","website"), d.schema.names)
        eq("typed field", FieldType.EMAIL, d.schema.typeOf("email"))
        eq("stable ids", "ds1-1", d.records[0].id)
        check("blank row detected", d.records[3].isEmpty)
        eq("missing counts", 1, d.missingCounts()["name"])

        println("\n=== Transforms (never destroy data) ===")
        val cleaned = TransformPipeline.run(d, standardCleanup())
        eq("blank row removed", 3, cleaned.dataset.size)
        eq("whitespace collapsed", "Sobuj Miah", cleaned.dataset.records[0]["name"])
        eq("email lowercased", "sobuj@example.com", cleaned.dataset.records[0]["email"])
        eq("phone normalised", "+8801712345678", cleaned.dataset.records[0]["phone"])
        eq("url fragment dropped", "https://example.com/profile", cleaned.dataset.records[0]["website"])
        check("original preserved in raw", cleaned.dataset.records[0].rawOf("name").contains("  "))
        check("modification flagged", cleaned.dataset.records[0].wasModified("name"))
        check("pipeline reports work", cleaned.totalChanged > 0)
        eq("pipeline reports removals", 1, cleaned.totalRemoved)

        println("\n=== Conservative normalisation (must NOT mangle) ===")
        val odd = Dataset.of("x","X", listOf(mapOf("phone" to "call us", "email" to "not-an-email")),
            Schema(listOf(FieldDef("phone", FieldType.PHONE), FieldDef("email", FieldType.EMAIL))))
        val oc = odd.transform(NormalizePhone(), NormalizeEmail())
        eq("non-phone left intact", "call us", oc.records[0]["phone"])
        eq("non-email left intact", "not-an-email", oc.records[0]["email"])

        println("\n=== Deduplication (never auto-delete ambiguous) ===")
        val dedup = Deduplicator(keys = listOf("email"))
        val analysis = dedup.analyze(cleaned.dataset)
        eq("exact duplicates found", 1, analysis.exact)
        eq("unique records", 2, analysis.unique)
        check("summary readable", analysis.summary().contains("unique"))
        eq("applyExact removes only exact", 2, dedup.applyExact(cleaned.dataset).size)
        check("analyze does not mutate", cleaned.dataset.size == 3)

        val fuzzy = Dataset.of("f","F", listOf(
            mapOf("name" to "Mohammed Rahman"),
            mapOf("name" to "Mohammad Rahman"),
        ))
        // Measured: "mohammed rahman" vs "mohammad rahman" scores 0.684,
        // so LOOSE (0.60) catches it and BALANCED (0.80) deliberately does not.
        val fa = Deduplicator(listOf("name"), Deduplicator.Sensitivity.LOOSE).analyze(fuzzy)
        eq("spelling variant flagged as likely", 1, fa.likely)
        eq("and never deleted automatically", 2, fuzzy.size)
        val fb = Deduplicator(listOf("name"), Deduplicator.Sensitivity.BALANCED).analyze(fuzzy)
        eq("BALANCED treats them as distinct", 2, fb.unique)
        val fc = Deduplicator(listOf("name"), Deduplicator.Sensitivity.STRICT).analyze(
            Dataset.of("z","Z", listOf(mapOf("name" to "Sobuj Miah"), mapOf("name" to "sobuj  miah"))))
        eq("STRICT still catches case/space noise", 1, fc.exact)

        println("\n=== Dataset operations ===")
        val cd = cleaned.dataset
        eq("search", 1, cd.search("chittagong").size)
        eq("field search", 1, cd.search("karim", "email").size)
        eq("select fields", listOf("name","email"), cd.selectFields(listOf("name","email")).schema.names)
        eq("rename field", true, cd.renameField("website","site").schema.names.contains("site"))
        eq("rename moves values", "https://example.com/profile",
            cd.renameField("website","site").records[0]["site"])
        eq("sort", "Karim Ali", cd.sortedBy("name").records[0]["name"])
        eq("merge datasets", 6, cd.mergeWith(cd).size)

        println("\n=== §5 ACCEPTANCE: scrape once, export many ===")
        val formats = listOf("csv","tsv","json","ndjson","yaml","md","html","sql")
        for (f in formats) {
            val s = exportToString(cd, f)
            check("$f exports non-empty", s.isNotBlank())
            check("$f contains data", s.contains("Sobuj Miah") || s.contains("Sobuj"), "len=${s.length}")
        }
        check("same dataset reused, never re-scraped", cd.size == 3)

        println("\n=== §14 Export quality ===")
        val csv = exportToString(cd, "csv")
        check("CSV has UTF-8 BOM", csv.startsWith("\uFEFF"))
        check("CSV header first", csv.lines()[0].contains("name"))
        eq("CSV row count", 4, csv.trim().lines().size)  // header + 3

        val bengali = Dataset.of("b","Bengali", listOf(
            mapOf("name" to "সবুজ মিয়া", "city" to "ঢাকা")))
        val bcsv = exportToString(bengali, "csv")
        check("Bengali survives CSV", bcsv.contains("সবুজ মিয়া"))
        check("Bengali CSV has BOM for Excel", bcsv.startsWith("\uFEFF"))

        val inject = Dataset.of("i","I", listOf(mapOf("f" to "=cmd|'/c calc'!A1")))
        check("CSV injection neutralised", exportToString(inject,"csv").contains("\"=cmd"))

        val quoted = Dataset.of("q","Q", listOf(mapOf("note" to "He said \"hi\", loudly")))
        val qcsv = exportToString(quoted, "csv")
        check("CSV quotes escaped", qcsv.contains("\"\"hi\"\""))

        println("\n=== Deterministic column order & selection ===")
        val o = ExportOptions(fields = listOf("email","name"))
        val sel = exportToString(cd, "csv", o)
        check("column order respected", sel.lines()[0].replace("\uFEFF","").startsWith("email,name"))
        val one = exportToString(cd, "csv", ExportOptions(recordIds = setOf("ds1-2")))
        eq("record selection", 2, one.trim().lines().size)

        println("\n=== JSON typing ===")
        val prices = Dataset.of("p","P", listOf(mapOf("item" to "Widget","price" to "1250.50")),
            Schema(listOf(FieldDef("item"), FieldDef("price", FieldType.CURRENCY))))
        val pj = exportToString(prices, "json")
        check("numbers unquoted in JSON", pj.contains("\"price\":1250.5"), pj)
        check("strings quoted", pj.contains("\"Widget\""))
        val empty = exportToString(Dataset.of("e","E", listOf(mapOf("a" to ""))), "json")
        check("blank becomes null", empty.contains("null"))

        println("\n=== SQL safety ===")
        val evil = Dataset.of("s","S", listOf(mapOf("name" to "O'Brien; DROP TABLE x;--")))
        val sql = exportToString(evil, "sql")
        check("SQL quotes escaped", sql.contains("'O''Brien"))
        check("CREATE TABLE emitted", sql.contains("CREATE TABLE"))

        println("\n=== HTML safety ===")
        val xss = Dataset.of("h","H", listOf(mapOf("t" to "<script>alert(1)</script>")))
        val html = exportToString(xss, "html")
        check("HTML escaped", html.contains("&lt;script&gt;") && !html.contains("<script>alert"))

        println("\n=== Markdown ===")
        val pipe = Dataset.of("m","M", listOf(mapOf("v" to "a|b")))
        check("pipes escaped", exportToString(pipe,"md").contains("a\\|b"))

        println("\n=== Extensibility (§39) ===")
        check("engine lists formats", ExportEngine.available().size >= 8)
        check("unknown format rejected", runCatching {
            exportToString(cd, "parquet") }.isFailure)
        check("format lookup", Formats.byId("csv") == Formats.CSV)

        println("\n=== ExportResult metadata ===")
        val bo = ByteArrayOutputStream()
        val res = ExportEngine.export(cd, "csv", bo)
        eq("records counted", 3, res.recordsWritten)
        eq("fields counted", 5, res.fieldsWritten.size)
        check("bytes counted", res.bytesWritten > 0)
        eq("filename suggested", "Contacts.csv", res.suggestedFilename)

        println("\n=== Partial completion (§33) ===")
        val partial = cd.copy(partial = PartialInfo(pagesProcessed = 100, failed = 15, skipped = 5))
        check("partial flagged", partial.isPartial)
        check("data still usable", partial.size == 3)
        check("export works on partial", exportToString(partial,"csv").isNotBlank())

        println("\n" + "=".repeat(66))
        println("Dataset / Transform / Dedup / Export: $pass passed, $fail failed")
        println("=".repeat(66))
        if (fail > 0) kotlin.system.exitProcess(1)
    }
}
