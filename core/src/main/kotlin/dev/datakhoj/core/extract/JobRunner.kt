package dev.datakhoj.core.extract

import dev.datakhoj.core.model.JobSpec
import dev.datakhoj.core.model.NoResultsException

/**
 * Applies a [JobSpec] to a document: extract -> coerce types -> drop rows that
 * are missing a required field.
 *
 * Kept free of any network or Android dependency so it is unit-testable on a
 * plain JVM, which is what allows the conformance suite to run in CI.
 */
object JobRunner {

    /** Extract and type-coerce, returning full diagnostics. */
    fun extract(spec: JobSpec, html: String, baseUrl: String? = null): Extractor.Result {
        val url = baseUrl ?: spec.url
        val res = Extractor().extract(
            html = html,
            selectors = spec.selectorMap,
            baseUrl = url,
            fields = spec.fieldNames,
            limit = spec.limits.maxRows,
        )
        val typed = applyTypes(spec, res.rows)
        res.rows.clear()
        res.rows.addAll(typed)
        return res
    }

    /**
     * Coerce every field to its declared type and drop rows where a
     * `required` field came out empty.
     */
    fun applyTypes(spec: JobSpec, rows: List<Map<String, String>>): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        outer@ for (row in rows) {
            val next = linkedMapOf<String, String>()
            for (f in spec.fields) {
                val v = FieldTypes.coerce(
                    value = row[f.name] ?: "",
                    type = f.type,
                    regex = f.regex,
                    trim = f.trim,
                    default = f.default,
                )
                if (f.required && v.isEmpty()) continue@outer
                next[f.name] = v
            }
            out.add(next)
        }
        return out
    }

    /**
     * Run against supplied HTML, throwing [NoResultsException] when nothing is
     * extracted. Never returns an empty list — an empty result is an error
     * carrying a diagnostic, not a silent success.
     */
    fun runOffline(spec: JobSpec, html: String, baseUrl: String? = null): List<Map<String, String>> {
        val res = extract(spec, html, baseUrl)
        if (res.rows.isEmpty()) {
            throw NoResultsException(
                "Job '${spec.name}' extracted 0 rows.",
                res.diagnostic(baseUrl ?: spec.url),
            )
        }
        return res.rows
    }
}
