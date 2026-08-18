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
import dev.datakhoj.core.dataset.Record

/**
 * Reference implementation of the persistence boundary.
 *
 * Three jobs:
 *  1. proves the interfaces are actually implementable
 *  2. gives every test a fast fake, with no Room and no emulator
 *  3. defines the semantics `RoomDatasetRepository` must match in Phase 2
 *
 * Not suitable for real use — it holds everything in memory and does not
 * survive process death. That is precisely what Phase 2 fixes.
 */
class InMemoryDatasetRepository : DatasetRepository {

    private val store = linkedMapOf<String, Dataset>()

    override suspend fun listSummaries(order: DatasetOrder): List<DatasetSummary> {
        val all = store.values.map { it.toSummary() }
        return when (order) {
            DatasetOrder.NEWEST -> all.sortedByDescending { it.updatedAt }
            DatasetOrder.OLDEST -> all.sortedBy { it.updatedAt }
            DatasetOrder.NAME -> all.sortedBy { it.name.lowercase() }
            DatasetOrder.LARGEST -> all.sortedByDescending { it.recordCount }
        }
    }

    override suspend fun load(id: String): Dataset =
        store[id] ?: throw RepositoryException.NotFound(id, "dataset")

    override suspend fun summary(id: String): DatasetSummary = load(id).toSummary()

    override suspend fun loadRecords(
        datasetId: String,
        offset: Int,
        limit: Int,
        query: String?,
        sortField: String?,
        descending: Boolean,
    ): Page<Record> {
        require(offset >= 0) { "offset must be >= 0" }
        require(limit > 0) { "limit must be > 0" }
        var d = load(datasetId)
        if (!query.isNullOrBlank()) d = d.search(query)
        if (sortField != null && sortField in d.schema) d = d.sortedBy(sortField, descending)
        val total = d.records.size
        val slice = d.records.drop(offset).take(limit)
        return Page(slice, offset, limit, total)
    }

    override suspend fun save(dataset: Dataset): String {
        store[dataset.id] = dataset
        return dataset.id
    }

    override suspend fun rename(id: String, name: String) {
        store[id] = load(id).rename(name)
    }

    override suspend fun delete(id: String) {
        if (store.remove(id) == null) throw RepositoryException.NotFound(id, "dataset")
    }

    override suspend fun deleteRecords(datasetId: String, recordIds: Set<String>): Int {
        val d = load(datasetId)
        val kept = d.records.filterNot { it.id in recordIds }
        val removed = d.records.size - kept.size
        store[datasetId] = d.copy(records = kept)
        return removed
    }

    override suspend fun duplicate(id: String, newName: String): String {
        val src = load(id)
        val newId = "${src.id}-copy-${store.size + 1}"
        if (store.containsKey(newId)) throw RepositoryException.AlreadyExists(newId)
        store[newId] = src.copy(
            id = newId,
            name = newName,
            records = src.records.map { it.copy(id = it.id.replace(src.id, newId)) },
        )
        return newId
    }

    override suspend fun exists(id: String) = store.containsKey(id)
    override suspend fun count() = store.size

    private fun Dataset.toSummary() = DatasetSummary(
        id = id, name = name, recordCount = records.size,
        fieldCount = schema.fields.size, createdAt = createdAt, updatedAt = updatedAt,
        sourceKind = source.kind, isPartial = isPartial,
    )
}

class InMemoryJobRepository : JobRepository {
    private val jobs = linkedMapOf<String, StoredJob>()
    private val runs = linkedMapOf<String, JobRun>()

    override suspend fun listJobs() = jobs.values.sortedByDescending { it.updatedAt }
    override suspend fun getJob(id: String) = jobs[id] ?: throw RepositoryException.NotFound(id, "job")
    override suspend fun saveJob(job: StoredJob): String { jobs[job.id] = job; return job.id }
    override suspend fun deleteJob(id: String) {
        if (jobs.remove(id) == null) throw RepositoryException.NotFound(id, "job")
        runs.values.filter { it.jobId == id }.forEach { runs.remove(it.id) }
    }

    override suspend fun listRuns(jobId: String?, limit: Int) =
        runs.values.filter { jobId == null || it.jobId == jobId }
            .sortedByDescending { it.startedAt }.take(limit)

    override suspend fun getRun(id: String) = runs[id] ?: throw RepositoryException.NotFound(id, "run")
    override suspend fun saveRun(run: JobRun): String { runs[run.id] = run; return run.id }

    override suspend fun updateRunStatus(id: String, status: JobRunStatus, error: String?) {
        val r = getRun(id)
        runs[id] = r.copy(
            status = status,
            error = error ?: r.error,
            finishedAt = if (status.isTerminal) (r.finishedAt ?: System.currentTimeMillis()) else r.finishedAt,
        )
    }

    override suspend fun saveCheckpoint(runId: String, checkpoint: String) {
        runs[runId] = getRun(runId).copy(checkpoint = checkpoint)
    }

    /** RUNNING or PAUSED with no finish time means the process died mid-run. */
    override suspend fun findInterrupted() =
        runs.values.filter { it.status.isActive && it.finishedAt == null }
}

class InMemoryExportHistoryRepository : ExportHistoryRepository {
    private val entries = linkedMapOf<String, ExportRecord>()

    override suspend fun list(datasetId: String?, limit: Int) =
        entries.values.filter { datasetId == null || it.datasetId == datasetId }
            .sortedByDescending { it.createdAt }.take(limit)

    override suspend fun record(entry: ExportRecord): String {
        entries[entry.id] = entry; return entry.id
    }

    override suspend fun delete(id: String) {
        if (entries.remove(id) == null) throw RepositoryException.NotFound(id, "export")
    }

    override suspend fun clear() = entries.clear()
}

/** In-memory [DataKhojStore] for tests, previews and development. */
class InMemoryStore(
    override val datasets: DatasetRepository = InMemoryDatasetRepository(),
    override val jobs: JobRepository = InMemoryJobRepository(),
    override val exports: ExportHistoryRepository = InMemoryExportHistoryRepository(),
) : DataKhojStore
