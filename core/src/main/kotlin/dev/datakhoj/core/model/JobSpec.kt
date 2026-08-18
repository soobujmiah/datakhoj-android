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

package dev.datakhoj.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * JobSpec v1 — the portable job definition.
 *
 * Normative schema: `spec/jobspec-v1.schema.json`. A `.dkjob` file written by
 * this engine runs unchanged on the Python engine (Linux/Termux) and vice
 * versa. Parity is enforced by `spec/conformance/`.
 *
 * Deliberately uses org.json rather than kotlinx.serialization so `core` stays
 * a plain JVM module — it compiles and unit-tests without the Android SDK,
 * which is what lets the whole engine be verified in CI on any machine.
 */
const val SPEC_VERSION = 1

/** Types a field can be coerced to. Must match Python's `fieldtypes.py`. */
enum class FieldType(val id: String) {
    TEXT("text"), NUMBER("number"), CURRENCY("currency"), DATE("date"),
    URL("url"), EMAIL("email"), PHONE("phone"), BOOLEAN("boolean"),
    LIST("list"), IMAGE("image");

    companion object {
        fun from(s: String?): FieldType =
            entries.firstOrNull { it.id == (s ?: "text").lowercase() } ?: TEXT
    }
}

data class FieldSpec(
    val name: String,
    val selector: String,
    val type: FieldType = FieldType.TEXT,
    val required: Boolean = false,
    val default: String? = null,
    val regex: String? = null,
    val trim: Boolean = true,
) {
    companion object {
        fun from(o: JSONObject): FieldSpec {
            val name = o.optString("name").ifBlank {
                throw ConfigException("Field is missing 'name': $o")
            }
            val selector = o.optString("selector").ifBlank {
                throw ConfigException("Field '$name' is missing 'selector'.")
            }
            return FieldSpec(
                name = name,
                selector = selector,
                type = FieldType.from(o.optString("type", "text")),
                required = o.optBoolean("required", false),
                default = if (o.has("default")) o.optString("default") else null,
                regex = if (o.has("regex")) o.optString("regex") else null,
                trim = o.optBoolean("trim", true),
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("selector", selector)
        if (type != FieldType.TEXT) put("type", type.id)
        if (required) put("required", true)
        default?.let { put("default", it) }
        regex?.let { put("regex", it) }
        if (!trim) put("trim", false)
    }
}

enum class PaginationMode(val id: String) {
    NONE("none"), NEXT_LINK("next_link"), URL_PATTERN("url_pattern"),
    LOAD_MORE("load_more"), INFINITE_SCROLL("infinite_scroll");

    companion object {
        fun from(s: String?) =
            entries.firstOrNull { it.id == (s ?: "none").lowercase() } ?: NONE
    }
}

data class Pagination(
    val mode: PaginationMode = PaginationMode.NONE,
    val nextSelector: String? = null,
    val urlPattern: String? = null,
    val startPage: Int = 1,
    val maxPages: Int = 10,
)

data class Limits(
    val maxRows: Int = 1000,
    val delayMs: Long = 1500,
    val concurrency: Int = 3,
    val timeoutMs: Long = 30_000,
    val retryMax: Int = 3,
)

data class Policy(
    val respectRobots: Boolean = true,
    val userAgent: String = "DataKhoj",
)

data class JobSpec(
    val name: String,
    val url: String,
    val fields: List<FieldSpec>,
    val container: String? = null,
    val specVersion: Int = SPEC_VERSION,
    val description: String = "",
    val render: String = "none",
    val headers: Map<String, String> = emptyMap(),
    val pagination: Pagination = Pagination(),
    val limits: Limits = Limits(),
    val policy: Policy = Policy(),
    val formats: List<String> = listOf("csv"),
    val dedupKeys: List<String> = emptyList(),
) {
    val fieldNames: List<String> get() = fields.map { it.name }

    /** Selector map consumed by [dev.datakhoj.core.extract.Extractor]. */
    val selectorMap: Map<String, String>
        get() = buildMap {
            fields.forEach { put(it.name, it.selector) }
            container?.let { put("container", it) }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("spec_version", specVersion)
        put("name", name)
        if (description.isNotBlank()) put("description", description)
        put("source", JSONObject().apply {
            put("url", url)
            if (render != "none") put("render", render)
            if (headers.isNotEmpty()) put("headers", JSONObject(headers))
        })
        put("extract", JSONObject().apply {
            container?.let { put("container", it) }
            put("fields", JSONArray(fields.map { it.toJson() }))
        })
        if (pagination.mode != PaginationMode.NONE) {
            put("pagination", JSONObject().apply {
                put("mode", pagination.mode.id)
                pagination.nextSelector?.let { put("next_selector", it) }
                pagination.urlPattern?.let { put("url_pattern", it) }
                put("max_pages", pagination.maxPages)
            })
        }
    }

    fun serialize(indent: Int = 2): String = toJson().toString(indent)

    companion object {
        fun parse(json: String): JobSpec = from(JSONObject(json))

        fun from(o: JSONObject): JobSpec {
            if (!o.has("spec_version")) {
                throw ConfigException("Missing 'spec_version'. Not a valid .dkjob file.")
            }
            val ver = o.getInt("spec_version")
            if (ver != SPEC_VERSION) {
                throw ConfigException(
                    "JobSpec version $ver is not supported by this engine " +
                        "(implements v$SPEC_VERSION). Update DataKhoj."
                )
            }
            val src = o.optJSONObject("source")
                ?: throw ConfigException("'source' is required.")
            val url = src.optString("url").ifBlank {
                throw ConfigException("source.url is required.")
            }
            val ext = o.optJSONObject("extract")
                ?: throw ConfigException("'extract' is required.")
            val rawFields = ext.optJSONArray("fields")
            if (rawFields == null || rawFields.length() == 0) {
                throw ConfigException("extract.fields must contain at least one field.")
            }
            val fields = (0 until rawFields.length())
                .map { FieldSpec.from(rawFields.getJSONObject(it)) }

            val headers = src.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { h.getString(it) }
            } ?: emptyMap()

            val pg = o.optJSONObject("pagination")
            val lim = o.optJSONObject("limits")
            val pol = o.optJSONObject("policy")
            val out = o.optJSONObject("output")

            return JobSpec(
                name = o.optString("name", "untitled"),
                description = o.optString("description", ""),
                url = url,
                render = src.optString("render", "none"),
                headers = headers,
                container = ext.optString("container").ifBlank { null },
                fields = fields,
                pagination = Pagination(
                    mode = PaginationMode.from(pg?.optString("mode")),
                    nextSelector = pg?.optString("next_selector")?.ifBlank { null },
                    urlPattern = pg?.optString("url_pattern")?.ifBlank { null },
                    startPage = pg?.optInt("start_page", 1) ?: 1,
                    maxPages = pg?.optInt("max_pages", 10) ?: 10,
                ),
                limits = Limits(
                    maxRows = lim?.optInt("max_rows", 1000) ?: 1000,
                    delayMs = lim?.optLong("delay_ms", 1500) ?: 1500,
                    concurrency = lim?.optInt("concurrency", 3) ?: 3,
                    timeoutMs = lim?.optLong("timeout_ms", 30_000) ?: 30_000,
                    retryMax = lim?.optInt("retry_max", 3) ?: 3,
                ),
                policy = Policy(
                    respectRobots = pol?.optBoolean("respect_robots", true) ?: true,
                    userAgent = pol?.optString("user_agent", "DataKhoj") ?: "DataKhoj",
                ),
                formats = out?.optJSONArray("formats")
                    ?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: listOf("csv"),
                dedupKeys = out?.optJSONArray("dedup_keys")
                    ?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: emptyList(),
            )
        }
    }
}
