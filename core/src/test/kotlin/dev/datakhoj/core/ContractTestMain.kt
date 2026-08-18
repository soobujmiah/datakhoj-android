package dev.datakhoj.core

import dev.datakhoj.core.dataset.*
import dev.datakhoj.core.export.*
import dev.datakhoj.core.model.FieldType
import dev.datakhoj.core.repository.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/**
 * Verifies the two contracts introduced before their implementations:
 *  - ExportRequest -> ExportEngine -> ExportWriter -> ExportResult
 *  - the persistence boundary (domain stays storage-independent)
 */
object ContractTestMain {
    private var pass = 0; private var fail = 0
    private fun check(n: String, c: Boolean, d: String = "") {
        if (c) { println("  PASS  $n"); pass++ } else { println("  FAIL  $n  $d"); fail++ }
    }
    private fun <T> eq(n: String, w: T, g: T) = check(n, w == g, "want=$w got=$g")

    private fun sample(id: String = "ds1", n: Int = 3) = Dataset.of(
        id = id, name = "Contacts",
        schema = Schema(listOf(
            FieldDef("name", FieldType.TEXT),
            FieldDef("email", FieldType.EMAIL),
        )),
        rows = (1..n).map { mapOf("name" to "Person $it", "email" to "p$it@example.com") },
        now = 1000L + n,
    )

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=== ExportRequest contract ===")
        val d = sample()
        val req = ExportRequest(d, "csv")
        check("valid request passes", req.validate().isValid)
        eq("effective fields", listOf("name","email"), req.effectiveFields)
        eq("effective record count", 3, req.effectiveRecordCount)
        check("describes itself", req.describe().contains("3 record"))

        println("\n=== Validation before execution (no half-written files) ===")
        val badFmt = ExportRequest(d, "parquet").validate()
        check("unknown format rejected", !badFmt.isValid)
        check("lists what IS available", badFmt.problems[0].contains("csv"))

        val emptyDs = ExportRequest(Dataset.of("e","Empty", emptyList()), "csv").validate()
        check("empty dataset rejected", !emptyDs.isValid)

        val noFields = ExportRequest(d, "csv", ExportOptions(fields = listOf("nope"))).validate()
        check("no resolvable fields rejected", !noFields.isValid)

        val badIds = ExportRequest(d, "csv", ExportOptions(recordIds = setOf("ghost"))).validate()
        check("nonexistent record ids rejected", !badIds.isValid)

        val badName = ExportRequest(d, "csv", filenameOverride = "a/b:c.csv").validate()
        check("unsafe filename rejected", !badName.isValid)

        println("\n=== Warnings do not block ===")
        val warn = ExportRequest(d, "csv", ExportOptions(fields = listOf("name","ghost"))).validate()
        check("unknown field warns", warn.hasWarnings)
        check("but export still allowed", warn.isValid)

        val partial = d.copy(partial = PartialInfo(pagesProcessed = 100, failed = 15, skipped = 5))
        val pv = ExportRequest(partial, "csv").validate()
        check("partial dataset warns", pv.hasWarnings && pv.warnings[0].contains("15"))
        check("partial dataset still exportable", pv.isValid)

        println("\n=== submit() honours the contract ===")
        val bo = ByteArrayOutputStream()
        val res = ExportEngine.submit(req, bo)
        eq("records written", 3, res.recordsWritten)
        eq("fields written", 2, res.fieldsWritten.size)
        check("bytes counted", res.bytesWritten > 0)
        eq("filename", "Contacts.csv", res.suggestedFilename)

        val over = ExportEngine.submit(
            ExportRequest(d, "csv", filenameOverride = "my-export.csv"), ByteArrayOutputStream())
        eq("filename override applied", "my-export.csv", over.suggestedFilename)

        val threw = runCatching {
            ExportEngine.submit(ExportRequest(d, "parquet"), ByteArrayOutputStream())
        }.exceptionOrNull()
        check("invalid request throws ExportException", threw is ExportException)
        check("failure explains itself", threw?.message?.contains("parquet") == true)

        val legacy = ExportEngine.export(d, "csv", ByteArrayOutputStream())
        eq("legacy overload still works", 3, legacy.recordsWritten)

        println("\n=== Persistence boundary: domain stays storage-independent ===")
        val store: DataKhojStore = InMemoryStore()
        store.datasets.save(sample("a", 3))
        store.datasets.save(sample("b", 5))
        eq("count", 2, store.datasets.count())
        eq("summaries avoid loading records", 2, store.datasets.listSummaries().size)
        eq("summary carries counts", 5, store.datasets.summary("b").recordCount)
        eq("ordering by size", "b", store.datasets.listSummaries(DatasetOrder.LARGEST)[0].id)

        println("\n=== Paging (large datasets never load whole) ===")
        store.datasets.save(sample("big", 250))
        val p1 = store.datasets.loadRecords("big", offset = 0, limit = 100)
        eq("page size respected", 100, p1.items.size)
        eq("total reported", 250, p1.total)
        check("hasMore", p1.hasMore)
        eq("nextOffset", 100, p1.nextOffset)
        val last = store.datasets.loadRecords("big", offset = 200, limit = 100)
        eq("final page", 50, last.items.size)
        check("no more after last", !last.hasMore)
        val filtered = store.datasets.loadRecords("big", query = "Person 7", limit = 500)
        check("query filters", filtered.total in 1..20)

        println("\n=== Typed failures, not raw SQL exceptions ===")
        val nf = runCatching { store.datasets.load("ghost") }.exceptionOrNull()
        check("NotFound typed", nf is RepositoryException.NotFound)
        check("message names the id", nf?.message?.contains("ghost") == true)

        println("\n=== Mutations ===")
        store.datasets.rename("a", "Renamed")
        eq("rename", "Renamed", store.datasets.summary("a").name)
        eq("deleteRecords returns count", 2, store.datasets.deleteRecords("a", setOf("a-1","a-2")))
        eq("records actually gone", 1, store.datasets.summary("a").recordCount)
        val dupId = store.datasets.duplicate("b", "Copy of B")
        check("duplicate creates new id", dupId != "b")
        eq("duplicate copies records", 5, store.datasets.summary(dupId).recordCount)
        store.datasets.delete("a")
        check("delete works", !store.datasets.exists("a"))

        println("\n=== Job + run lifecycle (Phase 2 prerequisite) ===")
        store.jobs.saveJob(StoredJob("j1","Nightly","{\"spec_version\":1}",1L,1L))
        eq("job saved", 1, store.jobs.listJobs().size)
        store.jobs.saveRun(JobRun("r1","j1",JobRunStatus.RUNNING, startedAt = 10L))
        check("RUNNING is active", JobRunStatus.RUNNING.isActive)
        check("COMPLETED is terminal", JobRunStatus.COMPLETED.isTerminal)
        check("PARTIALLY_COMPLETED is terminal", JobRunStatus.PARTIALLY_COMPLETED.isTerminal)
        store.jobs.saveCheckpoint("r1", "{\"page\":17}")
        eq("checkpoint persisted", "{\"page\":17}", store.jobs.getRun("r1").checkpoint)
        eq("interrupted runs found", 1, store.jobs.findInterrupted().size)
        store.jobs.updateRunStatus("r1", JobRunStatus.COMPLETED)
        eq("no longer interrupted", 0, store.jobs.findInterrupted().size)
        check("finish time set", store.jobs.getRun("r1").finishedAt != null)

        println("\n=== Export history (repeat without re-scraping) ===")
        store.exports.record(ExportRecord("e1","b","Contacts","csv","c.csv",5,120L,50L))
        store.exports.record(ExportRecord("e2","b","Contacts","json","c.json",5,300L,60L))
        eq("history listed", 2, store.exports.list().size)
        eq("newest first", "e2", store.exports.list()[0].id)
        eq("filter by dataset", 2, store.exports.list("b").size)
        eq("filter excludes others", 0, store.exports.list("zzz").size)

        println("\n=== No storage types leak into the domain ===")
        val srcFile = java.io.File("core/src/main/kotlin/dev/datakhoj/core/dataset/Dataset.kt")
        if (srcFile.exists()) {
            val txt = srcFile.readText()
            check("Dataset.kt imports no Room", !txt.contains("androidx.room"))
            check("Dataset.kt imports no Android", !txt.contains("import android."))
            check("Dataset.kt imports no SQL", !txt.contains("java.sql"))
        }

        println("\n" + "=".repeat(64))
        println("Export + persistence contracts: $pass passed, $fail failed")
        println("=".repeat(64))
        if (fail > 0) kotlin.system.exitProcess(1)
    }
}
