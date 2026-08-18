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

/**
 * On-device AI abstractions.
 *
 * ## What the NPU can and cannot do for DataKhoj
 *
 * Be clear-eyed about this. DataKhoj's hot path is HTTP fetching, Jsoup CSS
 * matching, string normalisation and file writing. Those are I/O- and
 * branch-bound. **A neural accelerator cannot make a regex or a socket read
 * faster.** Roughly 95% of the engine gains exactly nothing from an NPU.
 *
 * Where it genuinely wins is the small set of problems that are *fuzzy* rather
 * than mechanical:
 *
 *  - resolving an ambiguous query ("dhaka" — places? contacts? news?)
 *  - proposing CSS selectors for a page nobody has profiled yet
 *  - deciding two results are the same item despite different titles
 *  - ranking by meaning instead of keyword overlap
 *  - reading text out of images
 *
 * Every one of those is optional. The rule enforced throughout this package:
 * **AI is an enhancement, never a dependency.** If no model is installed, the
 * deterministic path still runs and the app still works.
 */

/** Where a model actually executed. Surfaced in Settings so the user can see. */
enum class Accelerator { NPU, GPU, CPU, NONE }

/**
 * What this device can actually do right now.
 *
 * Determined at runtime by probing for the QNN driver (`libQnnHtp.so`) and
 * installed models — never assumed from the chipset name.
 */
data class AiCapability(
    val accelerator: Accelerator = Accelerator.NONE,
    val hasLlm: Boolean = false,
    val hasEmbedder: Boolean = false,
    val hasOcr: Boolean = false,
    val modelName: String? = null,
    val detail: String = "",
) {
    val hasAny: Boolean get() = hasLlm || hasEmbedder || hasOcr

    fun describe(): String = when {
        !hasAny -> "No on-device model installed — using deterministic mode."
        else -> buildString {
            append("Running on $accelerator")
            modelName?.let { append(" · $it") }
            val caps = buildList {
                if (hasLlm) add("reasoning")
                if (hasEmbedder) add("semantic")
                if (hasOcr) add("OCR")
            }
            if (caps.isNotEmpty()) append(" · ${caps.joinToString(", ")}")
        }
    }

    companion object {
        val NONE = AiCapability()
    }
}

/**
 * Turns text into a vector.
 *
 * This is the highest value-per-megabyte AI feature in the whole app. A
 * sentence-embedding model is ~25 MB in INT8 and runs in single-digit
 * milliseconds on a Hexagon NPU — cheap enough to embed every search result
 * without the user noticing, which unlocks semantic dedup and ranking.
 */
interface Embedder {
    val dimensions: Int
    val accelerator: Accelerator get() = Accelerator.CPU

    suspend fun embed(text: String): FloatArray

    /** Batch form — NPUs are dramatically faster per item on batches. */
    suspend fun embedAll(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
}

/** Cosine similarity, the only vector maths this app needs. */
object Vectors {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0f || nb == 0f) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).coerceIn(-1f, 1f)
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        if (n == 0f) return v
        val inv = 1f / kotlin.math.sqrt(n)
        return FloatArray(v.size) { v[it] * inv }
    }
}

/**
 * A local text-generation model (Gemma-class, INT4, via LiteRT-LM + QNN).
 *
 * Used only for genuinely fuzzy jobs. Every call site must tolerate this being
 * absent, slow, or returning nonsense — hence the strict, validated JSON
 * contracts in [dev.datakhoj.core.ai.prompts].
 */
interface OnDeviceLlm {
    val modelName: String
    val accelerator: Accelerator get() = Accelerator.CPU

    /**
     * @param maxTokens keep small; every token costs battery and latency.
     * @param temperature 0.0 for structured extraction — we want determinism.
     */
    suspend fun complete(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.0f,
    ): String
}

/** Text recognition in images (ML Kit on Android, NPU-backed). */
interface OcrEngine {
    suspend fun readText(imageBytes: ByteArray): String
}
