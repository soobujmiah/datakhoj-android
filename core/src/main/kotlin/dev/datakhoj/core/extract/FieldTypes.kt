package dev.datakhoj.core.extract

import dev.datakhoj.core.model.FieldType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Field type coercion — the Kotlin half of a contract shared with Python.
 *
 * Every rule here is pinned by `spec/conformance/cases/`. If this file and
 * Python's `datakhoj/core/fieldtypes.py` ever disagree, the conformance suite
 * goes red on both engines. That is the entire mechanism preventing drift.
 *
 * Normalisation contract (v1):
 * ```
 * text      collapse whitespace, trim
 * number    strip all but digits, '-', '.'
 * currency  strip symbols and thousands separators, keep decimals
 * date      ISO-8601 (yyyy-MM-dd) where parseable, else original
 * url       absolutised by the extractor
 * email     lowercase, trim
 * phone     keep leading '+', strip all other non-digits
 * boolean   "true"/"false" from common affirmatives (incl. Bengali)
 * list      pipe-joined " | "
 * ```
 */
object FieldTypes {

    private val WS = Regex("\\s+")
    private val NUM = Regex("[-+]?\\d[\\d,\\s]*(?:\\.\\d+)?")
    private val EMAIL = Regex("[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}")
    private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val DMY = Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})")

    private val TRUTHY = setOf("true", "yes", "y", "1", "in stock", "available", "হ্যাঁ")
    private val FALSY = setOf("false", "no", "n", "0", "out of stock", "unavailable", "না")

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy",
        "d MMMM yyyy", "d MMM yyyy", "MMMM d, yyyy", "MMM d, yyyy",
        "yyyy/MM/dd", "yyyy.MM.dd",
    )

    /**
     * Bengali (০-৯) and Arabic-Indic (٠-٩) digits to ASCII, so numeric types
     * work on localised pages. Matches Python's `_DIGIT_MAP`.
     */
    fun normalizeDigits(s: String): String = buildString(s.length) {
        for (ch in s) {
            append(
                when (ch) {
                    in '\u09E6'..'\u09EF' -> '0' + (ch - '\u09E6')  // Bengali
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')  // Arabic-Indic
                    else -> ch
                }
            )
        }
    }

    private fun asText(v: String) = WS.replace(v, " ").trim()

    private fun asNumber(v: String): String =
        NUM.find(normalizeDigits(v))?.value?.replace(",", "")?.replace(" ", "") ?: ""

    private fun asCurrency(v: String): String = asNumber(v)

    private fun asDate(v: String): String {
        val t = asText(normalizeDigits(v))
        if (t.isBlank()) return ""
        for (p in DATE_PATTERNS) {
            try {
                return LocalDate.parse(t, DateTimeFormatter.ofPattern(p, Locale.ENGLISH))
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: Exception) { /* try next */ }
        }
        ISO_DATE.find(t)?.let { return it.value }
        DMY.find(t)?.let { m ->
            val (d, mo, y) = m.destructured
            try {
                return LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: Exception) { /* fall through */ }
        }
        return t
    }

    private fun asEmail(v: String): String {
        val t = asText(v).lowercase()
        return EMAIL.find(t)?.value ?: t
    }

    private fun asPhone(v: String): String {
        val t = normalizeDigits(asText(v))
        val plus = t.trimStart().startsWith("+")
        val digits = t.filter { it.isDigit() }
        return if (plus) "+$digits" else digits
    }

    private fun asBoolean(v: String): String {
        val t = asText(v).lowercase()
        return when {
            t in TRUTHY -> "true"
            t in FALSY -> "false"
            t.isNotEmpty() -> "true"
            else -> "false"
        }
    }

    /** Apply regex post-processing, then type coercion, then default. */
    fun coerce(
        value: String,
        type: FieldType = FieldType.TEXT,
        regex: String? = null,
        trim: Boolean = true,
        default: String? = null,
    ): String {
        var v = value
        if (!regex.isNullOrBlank()) {
            v = try {
                val m = Regex(regex).find(v)
                when {
                    m == null -> ""
                    m.groupValues.size > 1 -> m.groupValues[1]
                    else -> m.value
                }
            } catch (_: Exception) { v }
        }
        v = when (type) {
            FieldType.TEXT, FieldType.URL, FieldType.IMAGE, FieldType.LIST -> asText(v)
            FieldType.NUMBER -> asNumber(v)
            FieldType.CURRENCY -> asCurrency(v)
            FieldType.DATE -> asDate(v)
            FieldType.EMAIL -> asEmail(v)
            FieldType.PHONE -> asPhone(v)
            FieldType.BOOLEAN -> asBoolean(v)
        }
        if (trim) v = v.trim()
        return if (v.isEmpty() && default != null) default else v
    }
}
