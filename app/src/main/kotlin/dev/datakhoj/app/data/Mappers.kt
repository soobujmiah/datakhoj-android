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

import dev.datakhoj.core.dataset.*
import dev.datakhoj.core.model.FieldType
import dev.datakhoj.core.repository.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage <-> domain conversion.
 *
 * The only place Room entities and core domain models meet. Nothing above this
 * file sees a `*Entity`, and nothing in `:core` knows Room exists.
 */
object Mappers {

    // ---- schema ----

    fun schemaToJson(schema: Schema): String = JSONArray().apply {
        schema.fields.forEach { f ->
            put(JSONObject().apply {
                put("name", f.name)
                put("type", f.type.id)
                if (f.label != f.name) put("label", f.label)
                if (f.origin.isNotEmpty()) put("origin", f.origin)
                if (f.required) put("required", true)
            })
        }
    }.toString()

    fun schemaFromJson(json: String): Schema {
        if (json.isBlank()) return Schema(emptyList())
        val arr = JSONArray(json)
        return Schema((0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val name = o.getString("name")
            FieldDef(
                name = name,
                type = FieldType.from(o.optString("type", "text")),
                label = o.optString("label", name),
                origin = o.optString("origin", ""),
                required = o.optBoolean("required", false),
            )
        })
    }

    // ---- maps ----

    fun mapToJson(m: Map<String, String>): String =
        JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()

    fun mapFromJson(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    // ---- dataset ----

    fun datasetToEntity(d: Dataset) = DatasetEntity(
        id = d.id,
        name = d.name,
        schemaJson = schemaToJson(d.schema),
        sourceKind = d.source.kind,
        sourceJobId = d.source.jobId,
        sourceProviderId = d.source.providerId,
        sourceUrl = d.source.url,
        createdAt = d.createdAt,
        updatedAt = d.updatedAt,
        partialPages = d.partial?.pagesProcessed,
        partialFailed = d.partial?.failed,
        partialSkipped = d.partial?.skipped,
        partialReason = d.partial?.reason,
    )

    fun recordsToEntities(d: Dataset): List<RecordEntity> =
        d.records.mapIndexed { i, r ->
            RecordEntity(
                id = r.id,
                datasetId = d.id,
                position = i,
                valuesJson = mapToJson(r.values),
                rawJson = if (r.raw.isEmpty()) "{}" else mapToJson(r.raw),
                sourceUrl = r.sourceUrl,
                collectedAt = r.collectedAt,
                // Pre-computed so search is a single indexed LIKE rather than
                // deserialising every row's JSON.
                searchBlob = r.values.values.joinToString(" ").lowercase(),
            )
        }

    fun recordFromEntity(e: RecordEntity) = Record(
        id = e.id,
        values = mapFromJson(e.valuesJson),
        raw = mapFromJson(e.rawJson),
        sourceUrl = e.sourceUrl,
        collectedAt = e.collectedAt,
    )

    fun datasetFromEntity(e: DatasetEntity, records: List<RecordEntity>) = Dataset(
        id = e.id,
        name = e.name,
        schema = schemaFromJson(e.schemaJson),
        records = records.map(::recordFromEntity),
        source = DatasetSource(e.sourceKind, e.sourceJobId, e.sourceProviderId, e.sourceUrl),
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        partial = e.partialFailed?.let {
            PartialInfo(
                pagesProcessed = e.partialPages ?: 0,
                failed = it,
                skipped = e.partialSkipped ?: 0,
                reason = e.partialReason.orEmpty(),
            )
        },
    )

    fun summaryFromRow(r: DatasetSummaryRow) = DatasetSummary(
        id = r.id,
        name = r.name,
        recordCount = r.recordCount,
        fieldCount = runCatching { JSONArray(r.schemaJson).length() }.getOrDefault(0),
        createdAt = r.createdAt,
        updatedAt = r.updatedAt,
        sourceKind = r.sourceKind,
        isPartial = (r.partialFailed ?: 0) > 0,
    )

    // ---- jobs ----

    fun jobToEntity(j: StoredJob) =
        JobEntity(j.id, j.name, j.specJson, j.createdAt, j.updatedAt, j.lastRunId)

    fun jobFromEntity(e: JobEntity) =
        StoredJob(e.id, e.name, e.specJson, e.createdAt, e.updatedAt, e.lastRunId)

    fun runToEntity(r: JobRun) = JobRunEntity(
        id = r.id, jobId = r.jobId, status = r.status.name,
        startedAt = r.startedAt, finishedAt = r.finishedAt,
        pagesProcessed = r.pagesProcessed, pagesFailed = r.pagesFailed,
        recordsCollected = r.recordsCollected, recordsSkipped = r.recordsSkipped,
        datasetId = r.datasetId, error = r.error, checkpoint = r.checkpoint,
    )

    fun runFromEntity(e: JobRunEntity) = JobRun(
        id = e.id, jobId = e.jobId,
        status = runCatching { JobRunStatus.valueOf(e.status) }
            .getOrDefault(JobRunStatus.FAILED),
        startedAt = e.startedAt, finishedAt = e.finishedAt,
        pagesProcessed = e.pagesProcessed, pagesFailed = e.pagesFailed,
        recordsCollected = e.recordsCollected, recordsSkipped = e.recordsSkipped,
        datasetId = e.datasetId, error = e.error, checkpoint = e.checkpoint,
    )

    // ---- exports ----

    fun exportToEntity(x: ExportRecord) = ExportEntity(
        x.id, x.datasetId, x.datasetName, x.formatId, x.filename,
        x.recordCount, x.byteSize, x.createdAt, x.succeeded, x.error, x.destination,
    )

    fun exportFromEntity(e: ExportEntity) = ExportRecord(
        e.id, e.datasetId, e.datasetName, e.formatId, e.filename,
        e.recordCount, e.byteSize, e.createdAt, e.succeeded, e.error, e.destination,
    )
}
