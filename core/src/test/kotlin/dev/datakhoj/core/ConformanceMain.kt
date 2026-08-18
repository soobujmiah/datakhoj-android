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

import dev.datakhoj.core.extract.JobRunner
import dev.datakhoj.core.model.JobSpec
import dev.datakhoj.core.model.NoResultsException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cross-engine conformance runner — the Kotlin half.
 *
 * Reads the SAME files as the Python engine's `tests/test_conformance.py`:
 * ```
 * spec/conformance/cases/NN-name.html
 * spec/conformance/cases/NN-name.job.json
 * spec/conformance/cases/NN-name.expected.json
 * ```
 *
 * Also writes `kotlin-results.json` so CI can diff this engine's output
 * against Python's byte for byte. If the two ever disagree about whitespace,
 * URL resolution, phone normalisation or required-field dropping, the build
 * fails. That is the mechanism that keeps Android and Linux in lockstep.
 *
 * Runnable as a plain main() so it works without Gradle or an Android SDK.
 */
object ConformanceMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: "spec/conformance/cases")
        require(dir.isDirectory) { "Conformance corpus not found: ${dir.absolutePath}" }

        val cases = dir.listFiles { f -> f.name.endsWith(".job.json") }!!
            .sortedBy { it.name }
            .map { it.name.removeSuffix(".job.json") }

        var passed = 0
        var failed = 0
        val results = JSONObject()

        for (case in cases) {
            val html = File(dir, "$case.html").readText()
            val spec = JobSpec.parse(File(dir, "$case.job.json").readText())
            val expectedRaw = File(dir, "$case.expected.json").readText().trim()

            // Error cases declare the exception they require.
            if (expectedRaw.startsWith("{")) {
                val exp = JSONObject(expectedRaw)
                if (exp.has("__error__")) {
                    val wantCode = exp.getInt("__exit_code__")
                    val err = runCatching { JobRunner.runOffline(spec, html) }.exceptionOrNull()
                    if (err is NoResultsException && err.exitCode == wantCode &&
                        err.diagnostic.isNotBlank()
                    ) {
                        println("  PASS  $case  (correctly raised NoResults, exit $wantCode)")
                        passed++
                        results.put(case, JSONObject()
                            .put("__error__", "NoResultsError")
                            .put("__exit_code__", wantCode))
                    } else {
                        println("  FAIL  $case  expected NoResultsException(exit=$wantCode), " +
                            "got ${err?.javaClass?.simpleName ?: "success"}")
                        failed++
                    }
                    continue
                }
            }

            val expected = JSONArray(expectedRaw)
            val actual = try {
                JobRunner.runOffline(spec, html)
            } catch (e: Exception) {
                println("  FAIL  $case  threw ${e.javaClass.simpleName}: ${e.message}")
                failed++
                continue
            }

            val actualJson = JSONArray(actual.map { JSONObject(it as Map<*, *>) })
            results.put(case, actualJson)

            if (!sameRows(expected, actual)) {
                println("  FAIL  $case")
                println("        want: $expected")
                println("        got : $actualJson")
                failed++
            } else {
                println("  PASS  $case  (${actual.size} row(s))")
                passed++
            }
        }

        File("kotlin-results.json").writeText(results.toString(2))

        println()
        println("=".repeat(60))
        println("Kotlin engine conformance: $passed passed, $failed failed, ${cases.size} total")
        println("=".repeat(60))
        if (failed > 0) kotlin.system.exitProcess(1)
    }

    private fun sameRows(expected: JSONArray, actual: List<Map<String, String>>): Boolean {
        if (expected.length() != actual.size) return false
        for (i in 0 until expected.length()) {
            val want = expected.getJSONObject(i)
            val got = actual[i]
            if (want.length() != got.size) return false
            for (k in want.keys()) {
                if (want.getString(k) != got[k]) return false
            }
        }
        return true
    }
}
