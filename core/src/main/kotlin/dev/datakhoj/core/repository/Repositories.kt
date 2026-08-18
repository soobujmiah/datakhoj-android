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

package dev.datakhoj.core.repository

import dev.datakhoj.core.dataset.Dataset
import dev.datakhoj.core.export.ExportFormat

/**
 * The persistence boundary.
 *
 * **Purpose:** keep the core domain storage-independent. `Dataset`, `Record`,
 * `Schema` and the transform/dedup/export layers must never import Room,
 * SQLite, or any Android type. They talk to these interfaces instead.
 *
 * ```
 *   :core domain          :core repository          :app
 *   ─────────────         ────────────────          ────────────────
 *   Dataset        ─────► DatasetRepository ◄─────  RoomDatasetRepository
 *   Transform             (interface)               InMemory… (tests)
 *   Export                                          File… (future desktop)
 * ```
 *
 * Why define this *before* Room rather than after: retrofitting an abstraction
 * onto code that already imports `@Entity` and `@Dao` means touching every
 * call site and re-verifying every test. Defining it first means Phase 2 is
 * additive — write `RoomDatasetRepository`, register it, done.
 *
 * ### Rules
 *
 * 1. No Android or Room types in any signature here.
 * 2. Domain models in, domain models out — no DAO entities leak upward.
 * 3. Suspend functions: implementations do I/O, callers must not block.
 * 4. Failures are typed [RepositoryException]s, not raw SQL exceptions.
 * 5. Paging is explicit — a 10,000-row dataset must never be loaded whole
 *    just to render 20 rows (§37, §38).
 */

/** Storage failures the domain can reason about, free of SQL specifics. */
sealed class RepositoryException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class NotFound(val id: String, type: String = "record") :
        RepositoryException("No $type with id '$id'.")

    class AlreadyExists(val id: String) :
        RepositoryException("An entry with id '$id' already exists.")

    class StorageFull(cause: Throwable? = null) :
        RepositoryException("Not enough storage space.", cause)

    class Corrupted(detail: String, cause: Throwable? = null) :
        RepositoryException("Stored data is unreadable: $detail", cause)

    class Failed(operation: String, cause: Throwable? = null) :
        RepositoryException("Storage operation failed: $operation", cause)
}

/** One page of results. Keeps large datasets off the heap and out of Compose. */
data class Page<T>(
    val items: List<T>,
    val offset: Int,
    val limit: Int,
    val total: Int,
) {
    val hasMore: Boolean get() = offset + items.size < total
    val nextOffset: Int? get() = if (hasMore) offset + items.size else null
}

/** Lightweight dataset header for list screens — avoids loading records. */
data class DatasetSummary(
    val id: String,
    val name: String,
    val recordCount: Int,
    val fieldCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceKind: String = "",
    val isPartial: Boolean = false,
)

/** How to order a dataset listing. */
enum class DatasetOrder { NEWEST, OLDEST, NAME, LARGEST }

/**
 * Datasets and their records.
 *
 * Implementations: `InMemoryDatasetRepository` (here, for tests and previews)
 * and `RoomDatasetRepository` (Phase 2, in `:app`).
 */
interface DatasetRepository {

    /** Headers only — never loads records. Use for the datasets list. */
    suspend fun listSummaries(order: DatasetOrder = DatasetOrder.NEWEST): List<DatasetSummary>

    /** Full dataset including every record. Avoid for large datasets. */
    suspend fun load(id: String): Dataset

    /** Header only. */
    suspend fun summary(id: String): DatasetSummary

    /**
     * One page of records. The correct way to render a large dataset (§38).
     * @param query optional case-insensitive substring filter.
     */
    suspend fun loadRecords(
        datasetId: String,
        offset: Int = 0,
        limit: Int = 100,
        query: String? = null,
        sortField: String? = null,
        descending: Boolean = false,
    ): Page<dev.datakhoj.core.dataset.Record>

    /** Insert or replace. Returns the stored id. */
    suspend fun save(dataset: Dataset): String

    /** Rename without rewriting records. */
    suspend fun rename(id: String, name: String)

    suspend fun delete(id: String)

    /** Delete specific rows after the user reviews duplicates. */
    suspend fun deleteRecords(datasetId: String, recordIds: Set<String>): Int

    /** Copy under a new id — used by "duplicate" before a destructive edit. */
    suspend fun duplicate(id: String, newName: String): String

    suspend fun exists(id: String): Boolean

    suspend fun count(): Int
}

// ---------------------------------------------------------------- job records

/** Lifecycle of one execution (§4). `PARTIALLY_COMPLETED` is deliberate. */
enum class JobRunStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELLED;

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELLED)
    val isActive: Boolean get() = this in setOf(QUEUED, RUNNING, PAUSED)
}

/** A stored, re-runnable job definition. `specJson` is a JobSpec v1 document. */
data class StoredJob(
    val id: String,
    val name: String,
    val specJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunId: String? = null,
)

/** One execution of a [StoredJob]. */
data class JobRun(
    val id: String,
    val jobId: String,
    val status: JobRunStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val pagesProcessed: Int = 0,
    val pagesFailed: Int = 0,
    val recordsCollected: Int = 0,
    val recordsSkipped: Int = 0,
    val datasetId: String? = null,
    val error: String? = null,
    /** Serialised progress so a killed process can resume (§25). */
    val checkpoint: String? = null,
) {
    val durationMs: Long? get() = finishedAt?.let { it - startedAt }
}

interface JobRepository {
    suspend fun listJobs(): List<StoredJob>
    suspend fun getJob(id: String): StoredJob
    suspend fun saveJob(job: StoredJob): String
    suspend fun deleteJob(id: String)

    suspend fun listRuns(jobId: String? = null, limit: Int = 50): List<JobRun>
    suspend fun getRun(id: String): JobRun
    suspend fun saveRun(run: JobRun): String
    suspend fun updateRunStatus(id: String, status: JobRunStatus, error: String? = null)

    /** Persist resume state. Called periodically during a long run. */
    suspend fun saveCheckpoint(runId: String, checkpoint: String)

    /** Runs left dangling by process death, so they can be resumed or failed. */
    suspend fun findInterrupted(): List<JobRun>
}

// -------------------------------------------------------------- export history

/** A completed export (§15) — enough to repeat it without re-scraping. */
data class ExportRecord(
    val id: String,
    val datasetId: String,
    val datasetName: String,
    val formatId: String,
    val filename: String,
    val recordCount: Int,
    val byteSize: Long,
    val createdAt: Long,
    val succeeded: Boolean = true,
    val error: String? = null,
    /** Opaque destination handle (a SAF Uri string on Android). */
    val destination: String? = null,
)

interface ExportHistoryRepository {
    suspend fun list(datasetId: String? = null, limit: Int = 50): List<ExportRecord>
    suspend fun record(entry: ExportRecord): String
    suspend fun delete(id: String)
    suspend fun clear()
}

/** Everything the app can persist, in one injectable place. */
interface DataKhojStore {
    val datasets: DatasetRepository
    val jobs: JobRepository
    val exports: ExportHistoryRepository

    /** Integrity check (§24). Returns human-readable problems, empty if healthy. */
    suspend fun verifyIntegrity(): List<String> = emptyList()
}

/** Formats available for a dataset, for the export picker. */
fun ExportFormat.isBinaryOnly(): Boolean = !isText
