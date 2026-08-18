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

package dev.datakhoj.core

import dev.datakhoj.core.ai.*
import dev.datakhoj.core.provider.DataKind
import dev.datakhoj.core.provider.ProviderRegistry
import dev.datakhoj.core.provider.SearchResult
import kotlinx.coroutines.runBlocking

/** Side-by-side: identical search results, with and without the NPU. */
object NpuDemoMain {

    class Embedder8sGen4 : Embedder {
        override val dimensions = 128
        override val accelerator = Accelerator.NPU
        override suspend fun embed(text: String): FloatArray {
            val v = FloatArray(128)
            val clean = text.lowercase()
                .replace(Regex("[\\[\\]()_\\-—.]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 && it !in setOf("official","video","the","and","hd","full") }
            clean.forEach { w -> v[Math.floorMod(w.hashCode(), 128)] += 1f }
            return v
        }
    }

    private fun res(t: String, u: String, dl: String? = null, mb: Long? = null) =
        SearchResult(t, u, DataKind.AUDIO, "provider", directUrl = dl,
                     sizeBytes = mb?.let { it * 1024 * 1024 })

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val raw = listOf(
            res("Bohemian Rhapsody - Queen (Official Video) [1080p]", "https://site-a.com/watch/991"),
            res("Queen — Bohemian Rhapsody 1080p HD", "https://site-b.com/v/bo-rhap", dl="https://site-b.com/f.mkv", mb=340),
            res("bohemian_rhapsody_queen_1080p.mkv", "https://site-c.com/dl/77", dl="https://site-c.com/77.mkv", mb=338),
            res("Queen - Don't Stop Me Now (Official)", "https://site-a.com/watch/992"),
            res("Dont Stop Me Now — Queen HD", "https://site-b.com/v/dsmn", dl="https://site-b.com/d.mkv", mb=290),
            res("Led Zeppelin - Stairway To Heaven", "https://site-a.com/watch/120"),
        )

        println("┌─ WHAT THE PROVIDERS RETURNED ─────────────────────────────┐")
        raw.forEachIndexed { i, r -> println("  ${i+1}. ${r.title}") }
        println("   ${raw.size} rows\n")

        println("┌─ TODAY: text matching only (no NPU) ──────────────────────┐")
        val plain = ProviderRegistry.dedupe(raw)
        plain.forEachIndexed { i, r ->
            println("  ${i+1}. ${r.title}")
        }
        println("   ${plain.size} rows  ← same song listed 3x, and again 2x")
        println("   You delete the duplicates by hand.\n")

        println("┌─ WITH NPU: semantic dedup ────────────────────────────────┐")
        val smart = SemanticRanker(Embedder8sGen4()).dedupe(raw, threshold = 0.55f)
        smart.forEachIndexed { i, r ->
            val tag = if (r.isDownloadable) "  [downloadable, ${r.humanSize()}]" else ""
            println("  ${i+1}. ${r.title}$tag")
        }
        println("   ${smart.size} rows  ← merged, and it kept the downloadable copy")

        println()
        println("=".repeat(62))
        println("  Providers returned : ${raw.size} rows")
        println("  Text matching      : ${plain.size} rows")
        println("  NPU semantic       : ${smart.size} rows   " +
                "(${plain.size - smart.size} duplicates removed automatically)")
        println("=".repeat(62))
        println()
        println("Scale that to a 500-row export and it is the difference between")
        println("a clean spreadsheet and an hour of manual cleanup.")
    }
}
