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

import androidx.room.*

@Dao
interface DatasetDao {

    /** Summary projection — deliberately does not touch the records table. */
    @Query("""
        SELECT d.id, d.name,
               (SELECT COUNT(*) FROM records r WHERE r.datasetId = d.id) AS recordCount,
               d.schemaJson, d.createdAt, d.updatedAt, d.sourceKind, d.partialFailed
        FROM datasets d
        ORDER BY
          CASE WHEN :order = 'NEWEST'  THEN d.updatedAt END DESC,
          CASE WHEN :order = 'OLDEST'  THEN d.updatedAt END ASC,
          CASE WHEN :order = 'NAME'    THEN LOWER(d.name) END ASC,
          CASE WHEN :order = 'LARGEST' THEN (SELECT COUNT(*) FROM records r WHERE r.datasetId = d.id) END DESC
    """)
    suspend fun summaries(order: String): List<DatasetSummaryRow>

    @Query("SELECT * FROM datasets WHERE id = :id")
    suspend fun find(id: String): DatasetEntity?

    @Query("""
        SELECT d.id, d.name,
               (SELECT COUNT(*) FROM records r WHERE r.datasetId = d.id) AS recordCount,
               d.schemaJson, d.createdAt, d.updatedAt, d.sourceKind, d.partialFailed
        FROM datasets d WHERE d.id = :id
    """)
    suspend fun summary(id: String): DatasetSummaryRow?

    @Query("SELECT COUNT(*) FROM datasets")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM datasets WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DatasetEntity)

    @Query("UPDATE datasets SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long): Int

    @Query("DELETE FROM datasets WHERE id = :id")
    suspend fun delete(id: String): Int

    // ---- records ----

    @Query("SELECT * FROM records WHERE datasetId = :id ORDER BY position ASC")
    suspend fun allRecords(id: String): List<RecordEntity>

    @Query("SELECT COUNT(*) FROM records WHERE datasetId = :id")
    suspend fun recordCount(id: String): Int

    @Query("""
        SELECT COUNT(*) FROM records
        WHERE datasetId = :id AND searchBlob LIKE '%' || :q || '%'
    """)
    suspend fun searchCount(id: String, q: String): Int

    /** Paged, unsorted (insertion order) — the common case. */
    @Query("""
        SELECT * FROM records WHERE datasetId = :id
        ORDER BY position ASC LIMIT :limit OFFSET :offset
    """)
    suspend fun pageRecords(id: String, offset: Int, limit: Int): List<RecordEntity>

    @Query("""
        SELECT * FROM records
        WHERE datasetId = :id AND searchBlob LIKE '%' || :q || '%'
        ORDER BY position ASC LIMIT :limit OFFSET :offset
    """)
    suspend fun searchRecords(id: String, q: String, offset: Int, limit: Int): List<RecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<RecordEntity>)

    @Query("DELETE FROM records WHERE datasetId = :id")
    suspend fun clearRecords(id: String)

    @Query("DELETE FROM records WHERE datasetId = :id AND id IN (:ids)")
    suspend fun deleteRecords(id: String, ids: List<String>): Int

    /**
     * Replace a dataset and its records atomically, so a crash mid-save
     * cannot leave half a dataset behind.
     */
    @Transaction
    suspend fun replace(entity: DatasetEntity, records: List<RecordEntity>) {
        upsert(entity)
        clearRecords(entity.id)
        // Chunked: SQLite has a variable limit and a single huge insert of a
        // 10k-row dataset can exceed it.
        records.chunked(500).forEach { insertRecords(it) }
    }
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY updatedAt DESC")
    suspend fun list(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun find(id: String): JobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: JobEntity)

    @Query("DELETE FROM jobs WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM job_runs WHERE (:jobId IS NULL OR jobId = :jobId) ORDER BY startedAt DESC LIMIT :limit")
    suspend fun listRuns(jobId: String?, limit: Int): List<JobRunEntity>

    @Query("SELECT * FROM job_runs WHERE id = :id")
    suspend fun findRun(id: String): JobRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: JobRunEntity)

    @Query("UPDATE job_runs SET status = :status, error = :error, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateRunStatus(id: String, status: String, error: String?, finishedAt: Long?)

    @Query("UPDATE job_runs SET checkpoint = :checkpoint WHERE id = :id")
    suspend fun saveCheckpoint(id: String, checkpoint: String)

    /** Active status with no finish time = the process died mid-run. */
    @Query("SELECT * FROM job_runs WHERE status IN ('QUEUED','RUNNING','PAUSED') AND finishedAt IS NULL")
    suspend fun findInterrupted(): List<JobRunEntity>

    @Query("DELETE FROM job_runs WHERE jobId = :jobId")
    suspend fun deleteRunsFor(jobId: String)
}

@Dao
interface ExportDao {
    @Query("SELECT * FROM export_history WHERE (:datasetId IS NULL OR datasetId = :datasetId) ORDER BY createdAt DESC LIMIT :limit")
    suspend fun list(datasetId: String?, limit: Int): List<ExportEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ExportEntity)

    @Query("DELETE FROM export_history WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM export_history")
    suspend fun clear()
}
