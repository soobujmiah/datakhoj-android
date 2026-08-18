package dev.datakhoj.core.intent

import dev.datakhoj.core.provider.DataKind

/**
 * What the user actually meant.
 *
 * The app has ONE input box. Whatever the user types — "500 mp3 arijit singh",
 * "৫০০ গান", "scrape prices from shop.com", a bare URL — is resolved into this
 * structure, and everything downstream (source selection, extraction, export)
 * is driven by it.
 *
 * Produced by [IntentParser] deterministically, with no network and no API key.
 * An optional [LlmAssist] can refine low-confidence results.
 */
data class QueryIntent(
    /** What kind of thing is wanted. */
    val kind: DataKind,
    /** The actual subject, with count/format/site noise stripped. */
    val subject: String,
    /** What to do with it. */
    val action: Action = Action.SEARCH,
    /** How many results, when the user said a number. */
    val count: Int? = null,
    /** Explicit file formats requested: mp3, pdf, torrent... */
    val formats: List<String> = emptyList(),
    /** Restrict to this domain, from "from x.com" or "site:x.com". */
    val site: String? = null,
    /** A URL typed directly — implies SCRAPE or DOWNLOAD, not SEARCH. */
    val url: String? = null,
    /** Preferred export format, from "as csv", "to xlsx". */
    val exportFormat: String? = null,
    /** 0.0–1.0. Below [LOW_CONFIDENCE] the UI should ask, or call an LLM. */
    val confidence: Double = 1.0,
    /** Human-readable trace of how this was derived — shown in the UI. */
    val reasoning: List<String> = emptyList(),
    /** The original text, untouched. */
    val raw: String = "",
) {
    enum class Action {
        /** Find sources and return results. */
        SEARCH,
        /** Extract structured rows from a specific page. */
        SCRAPE,
        /** Fetch the bytes of a specific file. */
        DOWNLOAD,
    }

    val isAmbiguous: Boolean get() = confidence < LOW_CONFIDENCE

    /** One-line summary for the confirmation chip in the UI. */
    fun describe(): String = buildString {
        append(
            when (action) {
                Action.SEARCH -> "Search"
                Action.SCRAPE -> "Scrape"
                Action.DOWNLOAD -> "Download"
            }
        )
        count?.let { append(" $it") }
        append(" ${kind.label.lowercase()}")
        if (formats.isNotEmpty()) append(" (${formats.joinToString("/")})")
        if (subject.isNotBlank()) append(" — \"$subject\"")
        site?.let { append(" from $it") }
        exportFormat?.let { append(" → $it") }
    }

    companion object {
        const val LOW_CONFIDENCE = 0.55
    }
}

/**
 * Optional hook for an LLM to disambiguate hard queries.
 *
 * DataKhoj never *requires* this. The deterministic parser handles the common
 * cases offline, instantly, for free. If the user configures a model, it is
 * consulted only when [QueryIntent.isAmbiguous] is true — so a missing key or
 * dead network degrades quality, never functionality.
 */
interface LlmAssist {
    val id: String
    suspend fun refine(raw: String, best: QueryIntent): QueryIntent
}
