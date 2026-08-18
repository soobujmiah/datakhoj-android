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

package dev.datakhoj.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.datakhoj.app.data.DataKhojDatabase
import dev.datakhoj.app.data.RoomStore
import dev.datakhoj.core.repository.testing.RepositoryContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the SAME contract as InMemoryStore, against real Room on a real
 * Android runtime.
 *
 * This is the check that makes Phase 2 verifiable without a physical device:
 * an x86_64 emulator on a KVM-enabled CI runner exercises actual SQLite,
 * actual Room codegen, and actual transaction behaviour.
 *
 * If Room and InMemory ever disagree, this fails the build.
 *
 * Note the explicit `: Unit` on each test: `runBlocking` returns its last
 * expression, and JUnit rejects a test method that does not return void with
 * a confusing "Failed to instantiate test runner class".
 */
@RunWith(AndroidJUnit4::class)
class RoomContractTest {

    private fun freshStore(): RoomStore {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // In-memory Room: real SQLite, real DAOs, discarded per test.
        val db = Room.inMemoryDatabaseBuilder(ctx, DataKhojDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return RoomStore(db)
    }

    @Test
    fun roomSatisfiesTheRepositoryContract(): Unit = runBlocking {
        val report = RepositoryContract { freshStore() }.run("RoomStore")
        val failures = report.results.filter { !it.passed }
        failures.forEach { println("FAIL  ${it.name}  ${it.detail}") }
        println(report.summary())
        assertTrue(
            "Room diverged from the repository contract:\n" +
                failures.joinToString("\n") { " - ${it.name}: ${it.detail}" },
            report.ok,
        )
    }

    @Test
    fun dataSurvivesReopeningTheDatabase(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "persistence-check-${System.currentTimeMillis()}.db"
        ctx.deleteDatabase(name)

        val schema = dev.datakhoj.core.dataset.Schema(
            listOf(dev.datakhoj.core.dataset.FieldDef("name"))
        )
        val dataset = dev.datakhoj.core.dataset.Dataset.of(
            id = "persist", name = "Survivors",
            rows = (1..25).map { mapOf("name" to "Row $it") },
            schema = schema, now = 42L,
        )

        // Write, then fully close — simulating process death.
        val db1 = Room.databaseBuilder(ctx, DataKhojDatabase::class.java, name).build()
        RoomStore(db1).datasets.save(dataset)
        db1.close()

        // Reopen from disk.
        val db2 = Room.databaseBuilder(ctx, DataKhojDatabase::class.java, name).build()
        val store = RoomStore(db2)
        val loaded = store.datasets.load("persist")
        assertTrue("records lost across reopen", loaded.records.size == 25)
        assertTrue("name lost", loaded.name == "Survivors")
        assertTrue("values lost", loaded.records[0]["name"] == "Row 1")
        assertTrue("order lost", loaded.records[24]["name"] == "Row 25")
        db2.close()
        ctx.deleteDatabase(name)
    }

    @Test
    fun cascadeDeleteRemovesOrphanedRecords(): Unit = runBlocking {
        val store = freshStore()
        val d = dev.datakhoj.core.dataset.Dataset.of(
            "cascade", "C", (1..10).map { mapOf("a" to "$it") })
        store.datasets.save(d)
        store.datasets.delete("cascade")
        // Records are removed by the foreign key, not by application code.
        val page = runCatching { store.datasets.loadRecords("cascade") }
        assertTrue("dataset should be gone", page.isFailure)
        assertTrue("count should be zero", store.datasets.count() == 0)
    }

    @Test
    fun largeDatasetPagesWithoutLoadingEverything(): Unit = runBlocking {
        val store = freshStore()
        val big = dev.datakhoj.core.dataset.Dataset.of(
            "big", "Big", (1..5_000).map { mapOf("n" to "$it", "v" to "value $it") })
        val started = System.currentTimeMillis()
        store.datasets.save(big)
        val saveMs = System.currentTimeMillis() - started

        val t0 = System.currentTimeMillis()
        val page = store.datasets.loadRecords("big", offset = 4_900, limit = 50)
        val pageMs = System.currentTimeMillis() - t0

        println("save 5000 rows: ${saveMs}ms   page 50 of 5000: ${pageMs}ms")
        assertTrue("page size wrong", page.items.size == 50)
        assertTrue("total wrong", page.total == 5_000)
        // Paging must be far cheaper than a full load; generous bound to stay
        // reliable on a slow emulator.
        assertTrue("paging too slow (${pageMs}ms)", pageMs < 2_000)
    }
}
