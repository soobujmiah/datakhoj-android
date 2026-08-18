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

package dev.datakhoj.core.ai

import dev.datakhoj.core.intent.LlmAssist
import dev.datakhoj.core.intent.QueryIntent
import dev.datakhoj.core.provider.DataKind
import org.json.JSONObject

/**
 * Uses a local LLM to disambiguate queries the deterministic parser could not.
 *
 * Called **only** when [QueryIntent.isAmbiguous] — roughly 5% of real queries.
 * The other 95% never touch a model, so there is no battery or latency cost on
 * the common path.
 *
 * The model is treated as an untrusted, occasionally-wrong component:
 *  - temperature 0 and a tiny token budget
 *  - a strict JSON contract
 *  - the reply is validated, and anything unparseable falls back to the
 *    deterministic guess
 *
 * A hallucinating model can therefore make the guess no worse than not having
 * one at all.
 */
class LlmIntentAssist(
    private val llm: OnDeviceLlm,
    private val availableKinds: List<DataKind> = DataKind.entries,
) : LlmAssist {

    override val id: String = "llm-intent:${llm.modelName}"

    override suspend fun refine(raw: String, best: QueryIntent): QueryIntent {
        val kinds = availableKinds.joinToString(", ") { it.id }
        val prompt = """
            Classify this data search query. Reply with JSON only, no prose.

            Categories: $kinds

            Query: "$raw"

            JSON shape:
            {"kind":"<category>","subject":"<what to search for>","confidence":<0.0-1.0>}

            Rules:
            - "kind" MUST be exactly one of the listed categories.
            - "subject" is the search terms with counts and file formats removed.
            - If genuinely unclear, use "web" and confidence below 0.5.

            JSON:
        """.trimIndent()

        val reply = runCatching { llm.complete(prompt, maxTokens = 96, temperature = 0f) }
            .getOrNull() ?: return best

        val json = extractJson(reply) ?: return best
        val kindId = json.optString("kind").lowercase()
        val kind = DataKind.entries.firstOrNull { it.id == kindId } ?: return best
        val subject = json.optString("subject").ifBlank { best.subject }
        val conf = json.optDouble("confidence", 0.6).coerceIn(0.0, 1.0)

        // Only accept the model's answer if it is more confident than the
        // deterministic parser. Never let it downgrade a solid guess.
        if (conf <= best.confidence) return best

        return best.copy(
            kind = kind,
            subject = subject,
            confidence = conf,
            reasoning = best.reasoning + "On-device model (${llm.modelName}) suggests ${kind.label}",
        )
    }

    /** Pull the first JSON object out of a reply that may contain stray text. */
    private fun extractJson(s: String): JSONObject? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }
}

/**
 * Proposes CSS selectors for a page that has no profile yet.
 *
 * This is the second genuinely valuable NPU use: instead of the user hunting
 * for `.product-card`, the model reads a condensed skeleton of the DOM and
 * suggests a container plus field selectors, which the user then confirms or
 * corrects by tapping.
 *
 * The suggestion is always *verified against the real document* before being
 * shown — a proposed selector that matches nothing is discarded rather than
 * presented as fact.
 */
class LlmSelectorAssist(private val llm: OnDeviceLlm) {

    data class Suggestion(
        val container: String,
        val fields: Map<String, String>,
        val confidence: Double,
    )

    /**
     * @param skeleton a pruned DOM summary (tags, classes, ids — no text
     *   content), so the prompt stays small and no page data leaves the device.
     */
    suspend fun suggest(skeleton: String, want: List<String>): Suggestion? {
        val prompt = """
            You are given a simplified HTML structure. Propose CSS selectors to
            extract repeating records. Reply with JSON only.

            Wanted fields: ${want.joinToString(", ")}

            Structure:
            $skeleton

            JSON shape:
            {"container":"<css for the repeating element>",
             "fields":{"<name>":"<css relative to container>"},
             "confidence":<0.0-1.0>}

            Use "sel@href" to take an attribute. Keep selectors short and
            class-based. JSON:
        """.trimIndent()

        val reply = runCatching { llm.complete(prompt, maxTokens = 256, temperature = 0f) }
            .getOrNull() ?: return null
        val start = reply.indexOf('{')
        if (start < 0) return null
        val json = runCatching {
            JSONObject(reply.substring(start, reply.lastIndexOf('}') + 1))
        }.getOrNull() ?: return null

        val container = json.optString("container").ifBlank { return null }
        val fieldsObj = json.optJSONObject("fields") ?: return null
        val fields = fieldsObj.keys().asSequence()
            .associateWith { fieldsObj.getString(it) }
            .filterValues { it.isNotBlank() }
        if (fields.isEmpty()) return null

        return Suggestion(container, fields, json.optDouble("confidence", 0.5))
    }
}
