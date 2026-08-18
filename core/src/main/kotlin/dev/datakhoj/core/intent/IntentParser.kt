package dev.datakhoj.core.intent

import dev.datakhoj.core.extract.FieldTypes
import dev.datakhoj.core.provider.DataKind

/**
 * Turns free text into a [QueryIntent] — deterministically, offline, instantly.
 *
 * This is what makes the app a single search box. The user types whatever they
 * mean and DataKhoj works out *what kind of thing* they want and *which
 * sources can serve it*, then routes the query automatically.
 *
 * Design rules:
 *  - **No network, no API key, no model.** Runs in microseconds on-device.
 *  - **Evidence-based scoring**, not first-match-wins. Every signal contributes
 *    weight; the winning kind must beat the runner-up to earn high confidence.
 *  - **Explains itself.** [QueryIntent.reasoning] is shown in the UI so the
 *    user can see why it chose Audio, and correct it in one tap.
 *  - **Bengali is first class**, not transliterated as an afterthought.
 *
 * When confidence is low the caller may consult an [LlmAssist]; that is an
 * enhancement, never a dependency.
 */
class IntentParser(
    /** Extra user-defined keyword hints, e.g. "beats" -> AUDIO. */
    private val customHints: Map<String, DataKind> = emptyMap(),
) {

    fun parse(input: String): QueryIntent {
        val raw = input.trim()
        if (raw.isBlank()) {
            return QueryIntent(DataKind.WEB, "", confidence = 0.0, raw = raw)
        }

        val reasoning = mutableListOf<String>()
        var work = FieldTypes.normalizeDigits(raw)

        // --- 1. A bare URL short-circuits everything -----------------------
        URL_RE.find(work)?.let { m ->
            val url = m.value
            val rest = work.replace(url, " ").trim()
            val ext = FILE_EXT_RE.find(url)?.groupValues?.get(1)?.lowercase()
            val kind = ext?.let { extKind(it) }
            val looksLikeFile = kind != null && kind != DataKind.WEB
            reasoning += if (looksLikeFile) {
                "Detected a direct file URL (.$ext) → download"
            } else {
                "Detected a URL → scrape that page"
            }
            return QueryIntent(
                kind = kind ?: DataKind.WEB,
                subject = rest.ifBlank { url },
                action = if (looksLikeFile) QueryIntent.Action.DOWNLOAD
                         else QueryIntent.Action.SCRAPE,
                formats = listOfNotNull(ext),
                url = url,
                exportFormat = findExportFormat(work)?.also {
                    reasoning += "Export format requested: $it"
                },
                confidence = 0.95,
                reasoning = reasoning,
                raw = raw,
            )
        }

        // --- 2. Explicit operators: site:, kind:, count: --------------------
        var site: String? = null
        SITE_RE.find(work)?.let {
            site = it.groupValues[1].removePrefix("www.")
            work = work.replace(it.value, " ")
            reasoning += "Restricted to site ${site}"
        }
        var forcedKind: DataKind? = null
        KIND_RE.find(work)?.let {
            val k = DataKind.from(it.groupValues[1].lowercase())
            if (k != DataKind.OTHER) {
                forcedKind = k
                work = work.replace(it.value, " ")
                reasoning += "Kind forced by operator: ${k.label}"
            }
        }

        // --- 3. Count: "500", "৫০০", "500 jon", "five hundred" -------------
        // A DOI ("10.1038/nature12373") must not have its "10" read as a count.
        val hasDoi = DOI_RE.containsMatchIn(work)
        var count: Int? = null
        if (!hasDoi) COUNT_RE.find(work)?.let {
            count = it.groupValues[1].toIntOrNull()?.coerceIn(1, 100_000)
            work = work.replaceFirst(it.value, " ")
        }
        if (count == null && !hasDoi) {
            for ((word, n) in WORD_NUMBERS) {
                if (work.contains(word, ignoreCase = true)) {
                    count = n
                    work = work.replace(word, " ", ignoreCase = true)
                    break
                }
            }
        }
        count?.let { reasoning += "Wants $it results" }

        // --- 4. Export format: "as csv", "to xlsx" -------------------------
        val exportFormat = findExportFormat(work)?.also { fmt ->
            EXPORT_RE.find(work)?.let { work = work.replace(it.value, " ") }
            reasoning += "Export as $fmt"
        }

        // --- 5. Score every kind against the remaining text ----------------
        val tokens = tokenize(work)
        val scores = linkedMapOf<DataKind, Double>()
        val hits = linkedMapOf<DataKind, MutableList<String>>()

        fun award(kind: DataKind, weight: Double, why: String) {
            scores[kind] = (scores[kind] ?: 0.0) + weight
            hits.getOrPut(kind) { mutableListOf() }.add(why)
        }

        // File extensions and format words are the strongest signal.
        val formats = mutableListOf<String>()
        for (t in tokens) {
            val bare = t.removePrefix(".")
            extKind(bare)?.let { k ->
                award(k, 3.0, "'$bare' is a ${k.label.lowercase()} format")
                formats += bare
            }
        }
        // Keyword vocabulary (English + Bengali).
        for (t in tokens) {
            KEYWORDS[t]?.let { k -> award(k, 2.0, "keyword '$t'") }
            customHints[t]?.let { k -> award(k, 2.5, "your hint '$t'") }
        }
        // Multi-word phrases.
        val lower = work.lowercase()
        for ((phrase, kind) in PHRASES) {
            if (lower.contains(phrase)) award(kind, 2.5, "phrase '$phrase'")
        }
        // Structural signals.
        if (EMAIL_LIKE.containsMatchIn(work)) award(DataKind.CONTACT, 2.0, "looks like an email")
        if (PHONE_LIKE.containsMatchIn(work)) award(DataKind.CONTACT, 2.0, "looks like a phone number")
        if (MAGNET_RE.containsMatchIn(raw)) award(DataKind.TORRENT, 4.0, "magnet link")
        if (DOI_RE.containsMatchIn(work)) award(DataKind.ACADEMIC, 3.0, "DOI reference")

        // --- 6. Decide ------------------------------------------------------
        val ranked = scores.entries.sortedByDescending { it.value }
        val top = forcedKind ?: ranked.firstOrNull()?.key ?: DataKind.WEB
        val topScore = if (forcedKind != null) 10.0 else (ranked.firstOrNull()?.value ?: 0.0)
        val runnerUp = ranked.drop(1).firstOrNull()?.value ?: 0.0

        hits[top]?.take(3)?.forEach { reasoning += it }

        val confidence = when {
            forcedKind != null -> 1.0
            topScore == 0.0 -> 0.35            // nothing matched: generic web search
            topScore >= 3.0 && runnerUp == 0.0 -> 0.95
            topScore - runnerUp >= 2.0 -> 0.85
            topScore - runnerUp >= 1.0 -> 0.7
            else -> 0.5                        // genuinely ambiguous
        }
        if (topScore == 0.0) reasoning += "No specific data type detected — searching the web"
        if (confidence < QueryIntent.LOW_CONFIDENCE) {
            reasoning += "Ambiguous: ${ranked.take(2).joinToString(" vs ") { it.key.label }}"
        }

        val subject = cleanSubject(work, formats)

        return QueryIntent(
            kind = top,
            subject = subject,
            action = QueryIntent.Action.SEARCH,
            count = count,
            formats = formats.distinct(),
            site = site,
            exportFormat = exportFormat,
            confidence = confidence,
            reasoning = reasoning,
            raw = raw,
        )
    }

    // -- helpers -----------------------------------------------------------

    private fun tokenize(s: String): List<String> =
        s.lowercase().split(TOKEN_SPLIT).filter { it.isNotBlank() }

    private fun findExportFormat(s: String): String? =
        EXPORT_RE.find(s)?.groupValues?.get(2)?.lowercase()

    /** Strip format/filler words so the subject is what you'd actually search. */
    private fun cleanSubject(s: String, formats: List<String>): String {
        var out = s
        for (f in formats) out = out.replace(Regex("(?i)\\b\\.?$f\\b"), " ")
        out = out.replace(FILLER_RE, " ")
        return out.replace(Regex("\\s+"), " ").trim().trim('-', ',', ':').trim()
    }

    companion object {
        private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{M}\\p{N}.+#]+")
        private val URL_RE = Regex("""https?://[^\s]+|(?:www\.)[^\s]+\.[a-z]{2,}(?:/[^\s]*)?""", RegexOption.IGNORE_CASE)
        private val SITE_RE = Regex("""(?:site:|from\s+)([a-z0-9-]+(?:\.[a-z0-9-]+)+)""", RegexOption.IGNORE_CASE)
        private val KIND_RE = Regex("""kind:([a-z]+)""", RegexOption.IGNORE_CASE)
        private val COUNT_RE = Regex("""\b(\d{1,6})\s*(?:x|pcs?|items?|results?|jon|জন|টা|টি)?\b""")
        private val EXPORT_RE = Regex("""\b(as|to|in|export\s+(?:as|to)?)\s+(csv|xlsx|xls|json|yaml|yml|md|markdown|html|sqlite|txt)\b""", RegexOption.IGNORE_CASE)
        private val FILE_EXT_RE = Regex("""\.([a-z0-9]{2,5})(?:\?|#|$)""", RegexOption.IGNORE_CASE)
        private val EMAIL_LIKE = Regex("""[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}""", RegexOption.IGNORE_CASE)
        private val PHONE_LIKE = Regex("""(?:\+?88)?01[3-9]\d{8}|\+\d{7,15}""")
        private val MAGNET_RE = Regex("""magnet:\?xt=urn:btih:""", RegexOption.IGNORE_CASE)
        private val DOI_RE = Regex("""\b10\.\d{4,9}/\S+""")
        private val FILLER_RE = Regex(
            """(?i)\b(find|search|get|download|give|show|me|some|any|all|the|a|an|of|for|please|dao|দাও|খুঁজে|চাই|files?|file)\b"""
        )

        private val WORD_NUMBERS = linkedMapOf(
            "hundred" to 100, "thousand" to 1000, "dozen" to 12,
            "একশ" to 100, "একশত" to 100, "পাঁচশ" to 500, "হাজার" to 1000,
        )

        /** File extension -> data kind. The strongest single signal. */
        private val EXT_KIND: Map<String, DataKind> = buildMap {
            listOf("mp3","flac","wav","aac","ogg","opus","m4a","wma","aiff","alac","dsd","mid","midi")
                .forEach { put(it, DataKind.AUDIO) }
            listOf("mp4","mkv","avi","mov","webm","flv","wmv","m4v","mpg","mpeg","3gp","ts","m3u8")
                .forEach { put(it, DataKind.VIDEO) }
            listOf("jpg","jpeg","png","gif","webp","bmp","tiff","svg","heic","raw","psd","ai")
                .forEach { put(it, DataKind.IMAGE) }
            listOf("pdf","epub","mobi","azw3","djvu","doc","docx","odt","rtf","txt","pptx","ppt")
                .forEach { put(it, DataKind.DOCUMENT) }
            listOf("csv","xlsx","xls","json","xml","parquet","sqlite","db","tsv","jsonl","arrow")
                .forEach { put(it, DataKind.DATASET) }
            listOf("torrent") .forEach { put(it, DataKind.TORRENT) }
            listOf("apk","exe","dmg","deb","rpm","msi","appimage","xapk","ipa","zip","7z","rar","tar")
                .forEach { put(it, DataKind.SOFTWARE) }
            listOf("srt","vtt","ass","ssa","sub").forEach { put(it, DataKind.SUBTITLE) }
            listOf("ttf","otf","woff","woff2","eot").forEach { put(it, DataKind.FONT) }
            listOf("py","kt","java","js","ts","go","rs","c","cpp","rb","php","sh","swift")
                .forEach { put(it, DataKind.CODE) }
        }

        fun extKind(ext: String): DataKind? = EXT_KIND[ext.lowercase()]

        /** Single-word vocabulary, English + Bengali. */
        private val KEYWORDS: Map<String, DataKind> = buildMap {
            listOf("music","song","songs","audio","album","track","podcast","ost","soundtrack",
                   "গান","সঙ্গীত","অডিও","গানের")
                .forEach { put(it, DataKind.AUDIO) }
            listOf("video","movie","movies","film","clip","series","episode","anime","drama","trailer",
                   "ভিডিও","সিনেমা","মুভি","নাটক")
                .forEach { put(it, DataKind.VIDEO) }
            listOf("image","images","photo","photos","picture","pictures","wallpaper","logo","icon",
                   "ছবি","ছবিগুলো","ওয়ালপেপার")
                .forEach { put(it, DataKind.IMAGE) }
            listOf("book","books","ebook","novel","magazine","manual","document","documents","guide",
                   "বই","বইগুলো","ম্যাগাজিন","ডকুমেন্ট")
                .forEach { put(it, DataKind.DOCUMENT) }
            listOf("dataset","datasets","data","statistics","stats","records","database","spreadsheet",
                   "ডেটা","ডাটা","তথ্য","পরিসংখ্যান")
                .forEach { put(it, DataKind.DATASET) }
            listOf("torrent","torrents","magnet","seed","seeders","yts","1337x","টরেন্ট")
                .forEach { put(it, DataKind.TORRENT) }
            listOf("app","apps","apk","software","program","tool","package","installer",
                   "অ্যাপ","সফটওয়্যার")
                .forEach { put(it, DataKind.SOFTWARE) }
            listOf("code","repo","repository","library","snippet","github","gist","কোড")
                .forEach { put(it, DataKind.CODE) }
            listOf("paper","papers","research","thesis","journal","citation","preprint","arxiv","doi",
                   "গবেষণা","প্রবন্ধ")
                .forEach { put(it, DataKind.ACADEMIC) }
            listOf("email","emails","phone","phones","mobile","contact","contacts","number","numbers",
                   "ইমেইল","ফোন","নাম্বার","মোবাইল","যোগাযোগ")
                .forEach { put(it, DataKind.CONTACT) }
            listOf("profile","profiles","account","accounts","username","facebook","twitter","instagram",
                   "linkedin","প্রোফাইল")
                .forEach { put(it, DataKind.SOCIAL) }
            listOf("news","article","articles","headline","headlines","blog","খবর","সংবাদ","নিবন্ধ")
                .forEach { put(it, DataKind.NEWS) }
            listOf("price","prices","product","products","deal","deals","listing","listings",
                   "catalogue","catalog","sku","inventory",
                   "দাম","পণ্য","মূল্য")
                .forEach { put(it, DataKind.PRODUCT) }
            listOf("map","maps","location","locations","address","addresses","place","places",
                   "coordinates","gps","restaurant","restaurants","hotel","hotels","cafe",
                   "cafes","shop","shops","store","stores","nearby","directory","venue",
                   "venues","branch","branches","outlet","outlets",
                   "মানচিত্র","ঠিকানা","অবস্থান","রেস্টুরেন্ট","হোটেল","দোকান")
                .forEach { put(it, DataKind.GEO) }
            listOf("font","fonts","typeface","ফন্ট").forEach { put(it, DataKind.FONT) }
            listOf("subtitle","subtitles","caption","captions","সাবটাইটেল")
                .forEach { put(it, DataKind.SUBTITLE) }
            listOf("api","apis","endpoint","endpoints","swagger","openapi","rest","graphql")
                .forEach { put(it, DataKind.API) }
        }

        /** Phrases that beat single keywords. */
        private val PHRASES: Map<String, DataKind> = linkedMapOf(
            "phone number" to DataKind.CONTACT,
            "mobile number" to DataKind.CONTACT,
            "email address" to DataKind.CONTACT,
            "contact info" to DataKind.CONTACT,
            "contact information" to DataKind.CONTACT,
            "full movie" to DataKind.VIDEO,
            "web series" to DataKind.VIDEO,
            "song download" to DataKind.AUDIO,
            "research paper" to DataKind.ACADEMIC,
            "open data" to DataKind.DATASET,
            "data set" to DataKind.DATASET,
            "price list" to DataKind.PRODUCT,
            "source code" to DataKind.CODE,
            "sound track" to DataKind.AUDIO,
            "মোবাইল নাম্বার" to DataKind.CONTACT,
            "ফোন নাম্বার" to DataKind.CONTACT,
        )
    }
}
