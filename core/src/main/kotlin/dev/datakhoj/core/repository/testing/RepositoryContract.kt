/*
 * DataKhoj — a personal, unrestricted universal data collector.
 * Copyright (C) 2026 soobujmiah
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details: <https://www.gnu.org/licenses/>.
 *
 * "DataKhoj" and its logo are trademarks of the copyright holder and are NOT
 * licensed under the AGPL. Forks must use their own name and branding.
 */

package dev.datakhoj.core.repository.testing

import dev.datakhoj.core.dataset.*
import dev.datakhoj.core.model.FieldType
import dev.datakhoj.core.repository.*

/**
 * The behavioural contract every [DataKhojStore] must satisfy.
 *
 * Lives in `main` rather than `test` so the Android module can run the exact
 * same assertions against Room in an instrumentation test. InMemory and Room
 * are therefore held to one definition of correct — if they ever diverge, a
 * build fails instead of a bug reaching a device.
 *
 * Deliberately free of any test framework so it works under plain JUnit,
 * androidTest, or a bare `main()`.
 */
class RepositoryContract(private val newStore: suspend () -> DataKhojStore) {

    data class Result(val name: String, val passed: Boolean, val detail: String = "")
    data class Report(val implementation: String, val results: List<Result>) {
        val passed get() = results.count { it.passed }
        val failed get() = results.count { !it.passed }
        val ok get() = failed == 0
        fun summary() = "$implementation: $passed passed, $failed failed"
    }

    private val results = mutableListOf<Result>()

    private fun check(name: String, cond: Boolean, detail: String = "") {
        results += Result(name, cond, detail)
    }
    private fun <T> eq(name: String, want: T, got: T) =
        check(name, want == got, "want=$want got=$got")

    private fun sample(id: String, n: Int, name: String = "Contacts") = Dataset.of(
        id = id, name = name,
        schema = Schema(listOf(
            FieldDef("name", FieldType.TEXT),
            FieldDef("email", FieldType.EMAIL),
            FieldDef("city", FieldType.TEXT),
        )),
        rows = (1..n).map {
            mapOf("name" to "Person $it", "email" to "p$it@example.com",
                  "city" to if (it % 2 == 0) "Dhaka" else "Chittagong")
        },
        now = 1_000L + n,
    )

    suspend fun run(implementation: String): Report {
        results.clear()
        datasets()
        paging()
        search()
        mutation()
        errors()
        jobs()
        exports()
        integrity()
        return Report(implementation, results.toList())
    }

    private suspend fun datasets() {
        val s = newStore()
        eq("empty store count", 0, s.datasets.count())
        eq("empty summaries", 0, s.datasets.listSummaries().size)

        s.datasets.save(sample("a", 3))
        s.datasets.save(sample("b", 7, "Leads"))
        eq("count after save", 2, s.datasets.count())
        eq("exists true", true, s.datasets.exists("a"))
        eq("exists false", false, s.datasets.exists("zz"))

        val loaded = s.datasets.load("a")
        eq("round-trip id", "a", loaded.id)
        eq("round-trip name", "Contacts", loaded.name)
        eq("round-trip records", 3, loaded.records.size)
        eq("round-trip schema", listOf("name", "email", "city"), loaded.schema.names)
        eq("round-trip type", FieldType.EMAIL, loaded.schema.typeOf("email"))
        eq("round-trip value", "p1@example.com", loaded.records[0]["email"])
        eq("record order preserved", "Person 3", loaded.records[2]["name"])

        val sum = s.datasets.summary("b")
        eq("summary count", 7, sum.recordCount)
        eq("summary fields", 3, sum.fieldCount)
        eq("summary name", "Leads", sum.name)

        eq("order NAME", "Contacts", s.datasets.listSummaries(DatasetOrder.NAME)[0].name)
        eq("order LARGEST", "b", s.datasets.listSummaries(DatasetOrder.LARGEST)[0].id)

        // raw values and partial info must survive storage
        val withRaw = sample("r", 1).let { d ->
            d.copy(
                records = d.records.map { it.with("name", "Cleaned") },
                partial = PartialInfo(pagesProcessed = 10, failed = 2, skipped = 1, reason = "429"),
            )
        }
        s.datasets.save(withRaw)
        val back = s.datasets.load("r")
        eq("normalised value stored", "Cleaned", back.records[0]["name"])
        eq("raw original stored", "Person 1", back.records[0].rawOf("name"))
        eq("wasModified survives", true, back.records[0].wasModified("name"))
        eq("partial survives", 2, back.partial?.failed)
        eq("isPartial survives", true, back.isPartial)
        eq("summary flags partial", true, s.datasets.summary("r").isPartial)
    }

    private suspend fun paging() {
        val s = newStore()
        s.datasets.save(sample("big", 250))

        val p1 = s.datasets.loadRecords("big", 0, 100)
        eq("page size", 100, p1.items.size)
        eq("page total", 250, p1.total)
        eq("hasMore", true, p1.hasMore)
        eq("nextOffset", 100, p1.nextOffset)
        eq("first item", "Person 1", p1.items[0]["name"])

        val p3 = s.datasets.loadRecords("big", 200, 100)
        eq("last page size", 50, p3.items.size)
        eq("last hasMore", false, p3.hasMore)
        eq("last nextOffset", null, p3.nextOffset)
        eq("last item", "Person 250", p3.items.last()["name"])

        val beyond = s.datasets.loadRecords("big", 9999, 10)
        eq("offset past end is empty", 0, beyond.items.size)
        eq("total still correct", 250, beyond.total)

        val sorted = s.datasets.loadRecords("big", 0, 5, sortField = "name")
        eq("sorted page size", 5, sorted.items.size)
        eq("sorted total", 250, sorted.total)
        val desc = s.datasets.loadRecords("big", 0, 1, sortField = "name", descending = true)
        check("descending differs from ascending",
            desc.items[0]["name"] != sorted.items[0]["name"],
            "asc=${sorted.items[0]["name"]} desc=${desc.items[0]["name"]}")
    }

    private suspend fun search() {
        val s = newStore()
        s.datasets.save(sample("q", 10))
        val dhaka = s.datasets.loadRecords("q", 0, 100, query = "Dhaka")
        eq("search matches", 5, dhaka.total)
        eq("search returns matches", 5, dhaka.items.size)
        val ci = s.datasets.loadRecords("q", 0, 100, query = "dhaka")
        eq("search is case-insensitive", 5, ci.total)
        val none = s.datasets.loadRecords("q", 0, 100, query = "nowhere")
        eq("no matches", 0, none.total)
        val paged = s.datasets.loadRecords("q", 0, 2, query = "Dhaka")
        eq("search respects paging", 2, paged.items.size)
        eq("search total ignores paging", 5, paged.total)
    }

    private suspend fun mutation() {
        val s = newStore()
        s.datasets.save(sample("m", 5))

        s.datasets.rename("m", "Renamed")
        eq("rename applied", "Renamed", s.datasets.summary("m").name)
        eq("rename kept records", 5, s.datasets.summary("m").recordCount)

        val removed = s.datasets.deleteRecords("m", setOf("m-1", "m-2"))
        eq("deleteRecords count", 2, removed)
        eq("records actually removed", 3, s.datasets.summary("m").recordCount)
        eq("deleteRecords empty set", 0, s.datasets.deleteRecords("m", emptySet()))

        val copyId = s.datasets.duplicate("m", "Copy")
        check("duplicate new id", copyId != "m", "got $copyId")
        eq("duplicate record count", 3, s.datasets.summary(copyId).recordCount)
        eq("duplicate name", "Copy", s.datasets.summary(copyId).name)
        eq("original untouched", 3, s.datasets.summary("m").recordCount)

        // saving over an id must replace, not append
        s.datasets.save(sample("m", 2))
        eq("save replaces records", 2, s.datasets.summary("m").recordCount)

        s.datasets.delete("m")
        eq("deleted", false, s.datasets.exists("m"))
    }

    private suspend fun errors() {
        val s = newStore()
        val load = runCatching { s.datasets.load("ghost") }.exceptionOrNull()
        check("load missing -> NotFound", load is RepositoryException.NotFound,
            "got ${load?.javaClass?.simpleName}")
        val sum = runCatching { s.datasets.summary("ghost") }.exceptionOrNull()
        check("summary missing -> NotFound", sum is RepositoryException.NotFound)
        val del = runCatching { s.datasets.delete("ghost") }.exceptionOrNull()
        check("delete missing -> NotFound", del is RepositoryException.NotFound)
        val ren = runCatching { s.datasets.rename("ghost", "x") }.exceptionOrNull()
        check("rename missing -> NotFound", ren is RepositoryException.NotFound)
        val pg = runCatching { s.datasets.loadRecords("ghost") }.exceptionOrNull()
        check("page missing -> NotFound", pg is RepositoryException.NotFound)
        val job = runCatching { s.jobs.getJob("ghost") }.exceptionOrNull()
        check("job missing -> NotFound", job is RepositoryException.NotFound)
        val run = runCatching { s.jobs.getRun("ghost") }.exceptionOrNull()
        check("run missing -> NotFound", run is RepositoryException.NotFound)
    }

    private suspend fun jobs() {
        val s = newStore()
        s.jobs.saveJob(StoredJob("j1", "Nightly", """{"spec_version":1}""", 1L, 1L))
        s.jobs.saveJob(StoredJob("j2", "Weekly", """{"spec_version":1}""", 2L, 2L))
        eq("jobs listed", 2, s.jobs.listJobs().size)
        eq("job round-trip", "Nightly", s.jobs.getJob("j1").name)
        eq("spec round-trip", """{"spec_version":1}""", s.jobs.getJob("j1").specJson)

        s.jobs.saveRun(JobRun("r1", "j1", JobRunStatus.RUNNING, startedAt = 10L))
        s.jobs.saveRun(JobRun("r2", "j1", JobRunStatus.COMPLETED, startedAt = 20L, finishedAt = 30L))
        s.jobs.saveRun(JobRun("r3", "j2", JobRunStatus.QUEUED, startedAt = 5L))
        eq("all runs", 3, s.jobs.listRuns().size)
        eq("runs by job", 2, s.jobs.listRuns("j1").size)
        eq("runs newest first", "r2", s.jobs.listRuns("j1")[0].id)
        eq("run limit honoured", 1, s.jobs.listRuns(limit = 1).size)

        s.jobs.saveCheckpoint("r1", """{"page":17}""")
        eq("checkpoint persisted", """{"page":17}""", s.jobs.getRun("r1").checkpoint)

        eq("interrupted found", 2, s.jobs.findInterrupted().size)
        s.jobs.updateRunStatus("r1", JobRunStatus.COMPLETED)
        s.jobs.updateRunStatus("r3", JobRunStatus.CANCELLED)
        eq("none interrupted after finish", 0, s.jobs.findInterrupted().size)
        check("finishedAt set on terminal", s.jobs.getRun("r1").finishedAt != null)

        s.jobs.updateRunStatus("r2", JobRunStatus.FAILED, "network down")
        eq("error recorded", "network down", s.jobs.getRun("r2").error)

        s.jobs.deleteJob("j1")
        eq("job deleted", 1, s.jobs.listJobs().size)
        eq("its runs deleted too", 0, s.jobs.listRuns("j1").size)
    }

    private suspend fun exports() {
        val s = newStore()
        s.exports.record(ExportRecord("e1", "d1", "Contacts", "csv", "c.csv", 5, 120L, 10L))
        s.exports.record(ExportRecord("e2", "d1", "Contacts", "json", "c.json", 5, 300L, 20L))
        s.exports.record(ExportRecord("e3", "d2", "Leads", "csv", "l.csv", 9, 400L, 30L))
        eq("all exports", 3, s.exports.list().size)
        eq("newest first", "e3", s.exports.list()[0].id)
        eq("filtered by dataset", 2, s.exports.list("d1").size)
        eq("filter miss", 0, s.exports.list("zz").size)
        eq("limit honoured", 1, s.exports.list(limit = 1).size)
        val e = s.exports.list("d1").first { it.id == "e2" }
        eq("format round-trip", "json", e.formatId)
        eq("size round-trip", 300L, e.byteSize)
        s.exports.delete("e1")
        eq("deleted", 2, s.exports.list().size)
        s.exports.clear()
        eq("cleared", 0, s.exports.list().size)
    }

    private suspend fun integrity() {
        val s = newStore()
        s.datasets.save(sample("i", 2))
        eq("integrity clean", 0, s.verifyIntegrity().size)
    }
}
