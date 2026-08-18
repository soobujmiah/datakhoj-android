package dev.datakhoj.core

import dev.datakhoj.core.repository.InMemoryStore
import dev.datakhoj.core.repository.testing.RepositoryContract
import kotlinx.coroutines.runBlocking

/** Runs the shared repository contract against InMemoryStore. */
object StoreContractMain {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val report = RepositoryContract { InMemoryStore() }.run("InMemoryStore")
        report.results.filter { !it.passed }.forEach {
            println("  FAIL  ${it.name}  ${it.detail}")
        }
        report.results.filter { it.passed }.forEach { println("  PASS  ${it.name}") }
        println("\n" + "=".repeat(60))
        println(report.summary())
        println("=".repeat(60))
        if (!report.ok) kotlin.system.exitProcess(1)
    }
}
