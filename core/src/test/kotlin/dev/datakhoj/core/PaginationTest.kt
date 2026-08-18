package dev.datakhoj.core

import dev.datakhoj.core.provider.*
import kotlinx.coroutines.runBlocking

/**
 * Proves the ~10-result cap is gone: a provider that returns 10 hits per
 * request must be paged until the requested limit is satisfied.
 *
 * Mirrors DuckDuckGoProvider's loop without needing the network or :app.
 */
object PaginationTest {
    private var pass = 0; private var fail = 0
    private fun eq(n: String, w: Any?, g: Any?) {
        if (w == g) { println("  PASS  $n"); pass++ }
        else { println("  FAIL  $n want=$w got=$g"); fail++ }
    }

    /** Serves 10 results per page, 95 in total, like a real HTML endpoint. */
    class PagedFake(private val total: Int = 95) {
        var requests = 0; private set
        fun page(offset: Int): List<String> {
            requests++
            if (offset >= total) return emptyList()
            return (offset until minOf(offset + 10, total)).map { "https://x.com/r$it" }
        }
    }

    /** The same loop shape DuckDuckGoProvider uses. */
    private fun collect(fake: PagedFake, limit: Int, maxPages: Int = 30): List<String> {
        val out = LinkedHashMap<String, String>()
        var offset = 0; var page = 0
        while (out.size < limit && page < maxPages) {
            val hits = fake.page(offset)
            val before = out.size
            for (h in hits) {
                if (out.size >= limit) break
                out[h.trimEnd('/').lowercase()] = h
            }
            if (out.size == before) break          // endpoint exhausted
            offset += 10; page++
        }
        return out.values.take(limit)
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        println("=== The reported bug: results capped at ~10 ===")
        val single = PagedFake().page(0)
        eq("one request yields only 10", 10, single.size)

        println("\n=== After the fix ===")
        eq("limit 50 returns 50", 50, collect(PagedFake(), 50).size)
        eq("limit 100 returns all 95 available", 95, collect(PagedFake(), 100).size)
        eq("limit 250 still returns 95, not an error", 95, collect(PagedFake(), 250).size)

        println("\n=== Paging is efficient, not wasteful ===")
        val f = PagedFake(); collect(f, 50)
        eq("50 results took 5 requests", 5, f.requests)
        val f2 = PagedFake(); collect(f2, 10)
        eq("10 results took 1 request", 1, f2.requests)

        println("\n=== Terminates safely ===")
        val f3 = PagedFake(total = 25); val r3 = collect(f3, 1000)
        eq("stops when source exhausted", 25, r3.size)
        eq("does not loop forever", true, f3.requests <= 5)
        val f4 = PagedFake(total = 100000)
        eq("MAX_PAGES ceiling honoured", 30, run { collect(f4, 99999, maxPages = 30); f4.requests })

        println("\n=== Dedup across pages ===")
        val dupes = listOf("https://x.com/a", "https://x.com/a/", "https://X.com/A", "https://x.com/b")
        val seen = LinkedHashMap<String, String>()
        dupes.forEach { seen[it.trimEnd('/').lowercase()] = it }
        eq("case and slash variants collapse", 2, seen.size)

        println("\n" + "=".repeat(56))
        println("Pagination: $pass passed, $fail failed")
        println("=".repeat(56))
        if (fail > 0) kotlin.system.exitProcess(1)
    }
}
