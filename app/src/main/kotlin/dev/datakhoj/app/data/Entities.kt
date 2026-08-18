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

/**
 * Room entities.
 *
 * These are **storage types, not domain types**. They live in `:app` and never
 * escape this package — [Mappers] converts them to and from the `:core`
 * domain models. That is what keeps `:core` free of any Android dependency.
 *
 * Records are stored as individual rows rather than a JSON blob on the
 * dataset, so a 10,000-row dataset can be paged with LIMIT/OFFSET instead of
 * being deserialised whole (§37, §38).
 */

@Entity(tableName = "datasets")
data class DatasetEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Schema as JSON: [{name,type,label,origin,required}]. */
    val schemaJson: String,
    val sourceKind: String,
    val sourceJobId: String,
    val sourceProviderId: String,
    val sourceUrl: String,
    val createdAt: Long,
    val updatedAt: Long,
    val partialPages: Int?,
    val partialFailed: Int?,
    val partialSkipped: Int?,
    val partialReason: String?,
)

@Entity(
    tableName = "records",
    foreignKeys = [ForeignKey(
        entity = DatasetEntity::class,
        parentColumns = ["id"],
        childColumns = ["datasetId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("datasetId"),
        Index(value = ["datasetId", "position"]),
    ],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    val datasetId: String,
    /** Preserves insertion order across paging. */
    val position: Int,
    val valuesJson: String,
    val rawJson: String,
    val sourceUrl: String,
    val collectedAt: Long,
    /** Lowercased concatenation of all values, for fast LIKE search. */
    val searchBlob: String,
)

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val name: String,
    val specJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunId: String?,
)

@Entity(
    tableName = "job_runs",
    indices = [Index("jobId"), Index("status")],
)
data class JobRunEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val pagesProcessed: Int,
    val pagesFailed: Int,
    val recordsCollected: Int,
    val recordsSkipped: Int,
    val datasetId: String?,
    val error: String?,
    val checkpoint: String?,
)

@Entity(tableName = "export_history", indices = [Index("datasetId")])
data class ExportEntity(
    @PrimaryKey val id: String,
    val datasetId: String,
    val datasetName: String,
    val formatId: String,
    val filename: String,
    val recordCount: Int,
    val byteSize: Long,
    val createdAt: Long,
    val succeeded: Boolean,
    val error: String?,
    val destination: String?,
)

/** Projection for the datasets list — never loads records. */
data class DatasetSummaryRow(
    val id: String,
    val name: String,
    val recordCount: Int,
    val schemaJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceKind: String,
    val partialFailed: Int?,
)
