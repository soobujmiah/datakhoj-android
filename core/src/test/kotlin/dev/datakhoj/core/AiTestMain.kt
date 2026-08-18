package dev.datakhoj.core

import dev.datakhoj.core.ai.*
import dev.datakhoj.core.intent.*
import dev.datakhoj.core.provider.DataKind
import dev.datakhoj.core.provider.SearchResult
import kotlinx.coroutines.runBlocking

/**
 * Verifies the two things that matter about the AI layer:
 *  1. it improves results when a model is present
 *  2. everything still works when it is absent, or broken
 */
object AiTestMain {
    private var pass = 0; private var fail = 0
    private fun check(n: String, c: Boolean, d: String = "") {
        if (c) { println("  PASS  $n"); pass++ } else { println("  FAIL  $n  $d"); fail++ }
    }
    private fun <T> eq(n: String, w: T, g: T) = check(n, w == g, "want=$w got=$g")

    /** Fake embedder: bag-of-words vector. Deterministic, no model needed. */
    class FakeEmbedder : Embedder {
        override val dimensions = 64
        override val accelerator = Accelerator.NPU
        override suspend fun embed(text: String): FloatArray {
            val v = FloatArray(64)
            text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.forEach { w ->
                v[Math.floorMod(w.hashCode(), 64)] += 1f
            }
            return v
        }
    }

    /** Simulates a model that always fails — the resilience case. */
    class BrokenEmbedder : Embedder {
        override val dimensions = 64
        override suspend fun embed(text: String) = throw RuntimeException("NPU delegate crashed")
    }

    class FakeLlm(private val reply: String) : OnDeviceLlm {
        override val modelName = "fake-gemma-3n"
        override val accelerator = Accelerator.NPU
        override suspend fun complete(prompt: String, maxTokens: Int, temperature: Float) = reply
    }

    private fun r(title: String, url: String, dl: String? = null, size: Long? = null) =
        SearchResult(title, url, DataKind.AUDIO, "test", directUrl = dl, sizeBytes = size)

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        println("=== Vector maths ===")
        val a = floatArrayOf(1f, 0f, 0f); val b = floatArrayOf(1f, 0f, 0f)
        val c = floatArrayOf(0f, 1f, 0f)
        eq("identical vectors", 1.0f, Vectors.cosine(a, b))
        eq("orthogonal vectors", 0.0f, Vectors.cosine(a, c))
        eq("empty safe", 0.0f, Vectors.cosine(floatArrayOf(), a))
        eq("size mismatch safe", 0.0f, Vectors.cosine(floatArrayOf(1f), a))
        val n = Vectors.normalize(floatArrayOf(3f, 4f))
        check("normalize to unit", kotlin.math.abs(n[0] - 0.6f) < 1e-5 && kotlin.math.abs(n[1] - 0.8f) < 1e-5)
        eq("normalize zero-vector safe", 0f, Vectors.normalize(floatArrayOf(0f, 0f))[0])

        println("\n=== Semantic dedup WITH embedder ===")
        val dupes = listOf(
            r("Bohemian Rhapsody - Queen (Official Video) [1080p]", "https://a.com/1"),
            r("Queen — Bohemian Rhapsody 1080p HD", "https://b.com/2", dl = "https://b.com/2.mkv"),
            r("Stairway to Heaven - Led Zeppelin", "https://c.com/3"),
        )
        val sem = SemanticRanker(FakeEmbedder())
        val dd = sem.dedupe(dupes, threshold = 0.80f)
        check("semantic mode active", sem.isSemantic)
        eq("merged title variants", 2, dd.size)
        check("kept the downloadable variant", dd[0].isDownloadable, "got ${dd[0].title}")

        println("\n=== Same input, NO embedder (must still work) ===")
        val plain = SemanticRanker(null)
        check("not semantic", !plain.isSemantic)
        val dd2 = plain.dedupe(dupes)
        check("token fallback still dedupes", dd2.size <= 3)
        check("never crashes without a model", true)

        println("\n=== BROKEN model (must degrade, not explode) ===")
        val broken = SemanticRanker(BrokenEmbedder())
        val dd3 = broken.dedupe(dupes)
        check("survived embedder exception", dd3.isNotEmpty())
        val ranked = broken.rankByMeaning(dupes, "queen")
        eq("ranking survived failure", 3, ranked.size)
        val sc = broken.scores(dupes, "queen")
        eq("scores survived failure", 3, sc.size)

        println("\n=== Semantic ranking ===")
        val pool = listOf(
            r("Led Zeppelin Stairway", "https://x.com/1"),
            r("Queen Bohemian Rhapsody", "https://x.com/2"),
        )
        val byMeaning = SemanticRanker(FakeEmbedder()).rankByMeaning(pool, "queen bohemian")
        eq("most relevant first", "Queen Bohemian Rhapsody", byMeaning[0].title)

        println("\n=== LLM intent assist ===")
        val amb = IntentParser().parse("dhaka")
        check("'dhaka' is ambiguous", amb.isAmbiguous, "conf=${amb.confidence}")

        val good = LlmIntentAssist(FakeLlm("""{"kind":"geo","subject":"dhaka","confidence":0.9}"""))
        val refined = good.refine("dhaka", amb)
        eq("model upgraded the kind", DataKind.GEO, refined.kind)
        check("confidence raised", refined.confidence > amb.confidence)
        check("reasoning records the model", refined.reasoning.any { it.contains("model") })

        println("\n=== LLM safety: bad output must never corrupt the result ===")
        val garbage = LlmIntentAssist(FakeLlm("I think this is about Dhaka city!"))
        eq("non-JSON ignored", amb.kind, garbage.refine("dhaka", amb).kind)

        val badKind = LlmIntentAssist(FakeLlm("""{"kind":"telepathy","confidence":0.99}"""))
        eq("invalid category ignored", amb.kind, badKind.refine("dhaka", amb).kind)

        val lowConf = LlmIntentAssist(FakeLlm("""{"kind":"geo","subject":"x","confidence":0.1}"""))
        eq("lower-confidence answer rejected", amb.kind, lowConf.refine("dhaka", amb).kind)

        val strong = IntentParser().parse("arijit singh mp3")
        val hijack = LlmIntentAssist(FakeLlm("""{"kind":"geo","subject":"x","confidence":0.5}"""))
        eq("cannot downgrade a confident parse", DataKind.AUDIO, hijack.refine("arijit singh mp3", strong).kind)

        println("\n=== Prose wrapped around JSON still parses ===")
        val wrapped = LlmIntentAssist(FakeLlm("""Sure! Here you go: {"kind":"geo","subject":"dhaka","confidence":0.88} Hope that helps."""))
        eq("extracted embedded JSON", DataKind.GEO, wrapped.refine("dhaka", amb).kind)

        println("\n=== Capability reporting ===")
        eq("no model message", "No on-device model installed — using deterministic mode.",
            AiCapability.NONE.describe())
        val cap = AiCapability(Accelerator.NPU, hasLlm = true, hasEmbedder = true, modelName = "gemma-3n-int4")
        check("describes accelerator", cap.describe().contains("NPU"))
        check("describes model", cap.describe().contains("gemma-3n-int4"))
        check("hasAny true", cap.hasAny)

        println("\n" + "=".repeat(64))
        println("AI layer: $pass passed, $fail failed")
        println("=".repeat(64))
        if (fail > 0) kotlin.system.exitProcess(1)
    }
}
