package dev.datakhoj.core.provider

/**
 * The plugin contract for "search anything".
 *
 * A [SearchProvider] knows how to find one *kind* of thing — audio, video,
 * torrents, documents, images, datasets, code, people, APIs. The app ships a
 * registry of them and dispatches a query to whichever providers declare they
 * can serve it. Adding a new data type later means adding one class and
 * registering it — no changes to the UI, the runner, or the export layer.
 *
 * This is the structural answer to "we will add many tools later on".
 */

/** Broad category of thing being searched for. Drives UI filtering and icons. */
enum class DataKind(val id: String, val label: String) {
    WEB("web", "Web pages"),
    AUDIO("audio", "Music & audio"),
    VIDEO("video", "Video"),
    IMAGE("image", "Images"),
    DOCUMENT("document", "Documents & books"),
    DATASET("dataset", "Datasets"),
    TORRENT("torrent", "Torrents & magnets"),
    SOFTWARE("software", "Apps & packages"),
    CODE("code", "Source code"),
    ACADEMIC("academic", "Papers & research"),
    CONTACT("contact", "Contact details"),
    SOCIAL("social", "Social profiles"),
    NEWS("news", "News & articles"),
    PRODUCT("product", "Products & prices"),
    GEO("geo", "Places & maps"),
    FONT("font", "Fonts"),
    SUBTITLE("subtitle", "Subtitles"),
    API("api", "APIs & endpoints"),
    OTHER("other", "Other");

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

/** A single search hit, normalised across every provider. */
data class SearchResult(
    val title: String,
    val url: String,
    val kind: DataKind,
    val provider: String,
    val snippet: String = "",
    /** Direct download/stream URL when the provider can supply one. */
    val directUrl: String? = null,
    val magnet: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val format: String? = null,
    val durationSec: Int? = null,
    val published: String? = null,
    val author: String? = null,
    val license: String? = null,
    /** Provider-specific extras (seeders, bitrate, resolution, DOI...). */
    val extra: Map<String, String> = emptyMap(),
) {
    val isDownloadable: Boolean get() = directUrl != null || magnet != null

    fun humanSize(): String {
        val b = sizeBytes ?: return ""
        val u = listOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "${b} B" else String.format("%.1f %s", v, u[i])
    }
}

/** What the user asked for, plus filters. */
data class SearchQuery(
    val text: String,
    val kinds: Set<DataKind> = emptySet(),
    val limit: Int = 50,
    val page: Int = 1,
    val filters: Map<String, String> = emptyMap(),
) {
    fun wants(kind: DataKind) = kinds.isEmpty() || kind in kinds
}

/** Where a provider's results come from — surfaced in the UI for trust. */
enum class ProviderTrust { OFFICIAL_API, PUBLIC_INDEX, SCRAPED, USER_DEFINED }

/**
 * Implement this to teach DataKhoj a new place to look.
 *
 * Providers must be side-effect free and cancellable; the runner may call
 * several concurrently and abandon slow ones.
 */
interface SearchProvider {
    val id: String
    val displayName: String
    val kinds: Set<DataKind>
    val trust: ProviderTrust get() = ProviderTrust.SCRAPED

    /** Requires a key/token the user must supply in settings. */
    val requiresAuth: Boolean get() = false

    /** Cheap pre-check so the registry can skip irrelevant providers. */
    fun canHandle(query: SearchQuery): Boolean =
        kinds.any { query.wants(it) } && query.text.isNotBlank()

    /** Perform the search. Throw on hard failure; return empty for no hits. */
    suspend fun search(query: SearchQuery, http: HttpClient): List<SearchResult>
}

/**
 * Minimal HTTP abstraction so providers stay testable and platform-agnostic.
 * Backed by OkHttp on Android; a trivial JDK implementation is used in tests.
 */
interface HttpClient {
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): String
}
