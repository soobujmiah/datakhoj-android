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

package dev.datakhoj.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.datakhoj.core.dataset.Dataset
import dev.datakhoj.core.dataset.Record
import dev.datakhoj.core.repository.*

/**
 * Room implementations of the Phase 1 persistence boundary.
 *
 * These must behave **identically** to `InMemoryStore` — the same contract
 * test suite runs against both, so any divergence is a build failure rather
 * than a bug discovered later on a device.
 */
@Database(
    entities = [
        DatasetEntity::class, RecordEntity::class,
        JobEntity::class, JobRunEntity::class, ExportEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DataKhojDatabase : RoomDatabase() {
    abstract fun datasets(): DatasetDao
    abstract fun jobs(): JobDao
    abstract fun exports(): ExportDao

    companion object {
        private const val NAME = "datakhoj.db"

        @Volatile private var instance: DataKhojDatabase? = null

        fun get(context: Context): DataKhojDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context) =
            Room.databaseBuilder(context, DataKhojDatabase::class.java, NAME)
                // No fallbackToDestructiveMigration: user data must never be
                // dropped on upgrade (§24). A missing migration should fail
                // loudly in development, not silently delete everything.
                .build()
    }
}

/**
 * Translate storage failures into typed [RepositoryException]s.
 *
 * `inline` so the lambda can contain suspend calls — a non-inline function
 * taking a plain `() -> T` cannot host them.
 */
private inline fun <T> mapErrors(operation: String, block: () -> T): T =
    try {
        block()
    } catch (e: RepositoryException) {
        throw e
    } catch (e: Exception) {
        val m = e.message.orEmpty()
        when {
            m.contains("disk", true) || m.contains("full", true) ->
                throw RepositoryException.StorageFull(e)
            m.contains("malformed", true) || m.contains("corrupt", true) ->
                throw RepositoryException.Corrupted(m, e)
            else -> throw RepositoryException.Failed(operation, e)
        }
    }

class RoomDatasetRepository(private val dao: DatasetDao) : DatasetRepository {

    override suspend fun listSummaries(order: DatasetOrder): List<DatasetSummary> =
        mapErrors("listSummaries") { dao.summaries(order.name).map(Mappers::summaryFromRow) }

    override suspend fun load(id: String): Dataset = mapErrors("load") {
        val e = dao.find(id) ?: throw RepositoryException.NotFound(id, "dataset")
        Mappers.datasetFromEntity(e, dao.allRecords(id))
    }

    override suspend fun summary(id: String): DatasetSummary = mapErrors("summary") {
        val row = dao.summary(id) ?: throw RepositoryException.NotFound(id, "dataset")
        Mappers.summaryFromRow(row)
    }

    override suspend fun loadRecords(
        datasetId: String,
        offset: Int,
        limit: Int,
        query: String?,
        sortField: String?,
        descending: Boolean,
    ): Page<Record> = mapErrors("loadRecords") {
        require(offset >= 0) { "offset must be >= 0" }
        require(limit > 0) { "limit must be > 0" }
        if (!dao.exists(datasetId)) throw RepositoryException.NotFound(datasetId, "dataset")

        val q = query?.takeIf { it.isNotBlank() }?.lowercase()

        // Sorting by an arbitrary field means comparing values inside JSON,
        // which SQLite cannot index. Do it in memory and page the result —
        // correct, and still bounded because sorting is a deliberate action.
        if (sortField != null) {
            val all = if (q == null) dao.allRecords(datasetId)
                      else dao.searchRecords(datasetId, q, 0, Int.MAX_VALUE)
            var domain = all.map(Mappers::recordFromEntity)
            val schema = Mappers.schemaFromJson(dao.find(datasetId)!!.schemaJson)
            if (sortField in schema) {
                val tmp = Dataset(datasetId, "", schema, domain).sortedBy(sortField, descending)
                domain = tmp.records
            }
            return@mapErrors Page(domain.drop(offset).take(limit), offset, limit, domain.size)
        }

        val total = if (q == null) dao.recordCount(datasetId) else dao.searchCount(datasetId, q)
        val rows = if (q == null) dao.pageRecords(datasetId, offset, limit)
                   else dao.searchRecords(datasetId, q, offset, limit)
        Page(rows.map(Mappers::recordFromEntity), offset, limit, total)
    }

    override suspend fun save(dataset: Dataset): String = mapErrors("save") {
        dao.replace(Mappers.datasetToEntity(dataset), Mappers.recordsToEntities(dataset))
        dataset.id
    }

    override suspend fun rename(id: String, name: String) = mapErrors("rename") {
        if (dao.rename(id, name, System.currentTimeMillis()) == 0) {
            throw RepositoryException.NotFound(id, "dataset")
        }
    }

    override suspend fun delete(id: String) = mapErrors("delete") {
        if (dao.delete(id) == 0) throw RepositoryException.NotFound(id, "dataset")
    }

    override suspend fun deleteRecords(datasetId: String, recordIds: Set<String>): Int =
        mapErrors("deleteRecords") {
            if (recordIds.isEmpty()) return@mapErrors 0
            // Chunked to stay under SQLite's variable limit.
            recordIds.chunked(500).sumOf { dao.deleteRecords(datasetId, it) }
        }

    override suspend fun duplicate(id: String, newName: String): String =
        mapErrors("duplicate") {
            val src = load(id)
            val newId = "${src.id}-copy-${System.currentTimeMillis()}"
            if (dao.exists(newId)) throw RepositoryException.AlreadyExists(newId)
            val copy = src.copy(
                id = newId,
                name = newName,
                records = src.records.map { it.copy(id = it.id.replace(src.id, newId)) },
            )
            save(copy)
            newId
        }

    override suspend fun exists(id: String) = mapErrors("exists") { dao.exists(id) }
    override suspend fun count() = mapErrors("count") { dao.count() }
}

class RoomJobRepository(private val dao: JobDao) : JobRepository {

    override suspend fun listJobs() = mapErrors("listJobs") { dao.list().map(Mappers::jobFromEntity) }

    override suspend fun getJob(id: String) = mapErrors("getJob") {
        Mappers.jobFromEntity(dao.find(id) ?: throw RepositoryException.NotFound(id, "job"))
    }

    override suspend fun saveJob(job: StoredJob) = mapErrors("saveJob") {
        dao.upsert(Mappers.jobToEntity(job)); job.id
    }

    override suspend fun deleteJob(id: String) = mapErrors("deleteJob") {
        if (dao.delete(id) == 0) throw RepositoryException.NotFound(id, "job")
        dao.deleteRunsFor(id)
    }

    override suspend fun listRuns(jobId: String?, limit: Int) =
        mapErrors("listRuns") { dao.listRuns(jobId, limit).map(Mappers::runFromEntity) }

    override suspend fun getRun(id: String) = mapErrors("getRun") {
        Mappers.runFromEntity(dao.findRun(id) ?: throw RepositoryException.NotFound(id, "run"))
    }

    override suspend fun saveRun(run: JobRun) = mapErrors("saveRun") {
        dao.upsertRun(Mappers.runToEntity(run)); run.id
    }

    override suspend fun updateRunStatus(id: String, status: JobRunStatus, error: String?) =
        mapErrors("updateRunStatus") {
            val existing = dao.findRun(id) ?: throw RepositoryException.NotFound(id, "run")
            dao.updateRunStatus(
                id, status.name, error ?: existing.error,
                if (status.isTerminal) existing.finishedAt ?: System.currentTimeMillis()
                else existing.finishedAt,
            )
        }

    override suspend fun saveCheckpoint(runId: String, checkpoint: String) =
        mapErrors("saveCheckpoint") {
            if (dao.findRun(runId) == null) throw RepositoryException.NotFound(runId, "run")
            dao.saveCheckpoint(runId, checkpoint)
        }

    override suspend fun findInterrupted() =
        mapErrors("findInterrupted") { dao.findInterrupted().map(Mappers::runFromEntity) }
}

class RoomExportHistoryRepository(private val dao: ExportDao) : ExportHistoryRepository {
    override suspend fun list(datasetId: String?, limit: Int) =
        mapErrors("list") { dao.list(datasetId, limit).map(Mappers::exportFromEntity) }

    override suspend fun record(entry: ExportRecord) =
        mapErrors("record") { dao.insert(Mappers.exportToEntity(entry)); entry.id }

    override suspend fun delete(id: String) = mapErrors("delete") {
        if (dao.delete(id) == 0) throw RepositoryException.NotFound(id, "export")
    }

    override suspend fun clear() = mapErrors("clear") { dao.clear() }
}

/** Room-backed [DataKhojStore]. */
class RoomStore(private val db: DataKhojDatabase) : DataKhojStore {
    override val datasets: DatasetRepository = RoomDatasetRepository(db.datasets())
    override val jobs: JobRepository = RoomJobRepository(db.jobs())
    override val exports: ExportHistoryRepository = RoomExportHistoryRepository(db.exports())

    override suspend fun verifyIntegrity(): List<String> = mapErrors("verifyIntegrity") {
        val problems = mutableListOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { c ->
            while (c.moveToNext()) {
                val r = c.getString(0)
                if (!r.equals("ok", ignoreCase = true)) problems += r
            }
        }
        problems
    }

    companion object {
        fun open(context: Context) = RoomStore(DataKhojDatabase.get(context))
    }
}
