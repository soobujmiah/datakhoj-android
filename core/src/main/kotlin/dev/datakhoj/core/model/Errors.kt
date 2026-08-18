package dev.datakhoj.core.model

/**
 * Typed failures with sysexits-style codes, mirroring Python's `core/errors.py`.
 *
 * The UI maps these to distinct, actionable messages — the user must always be
 * able to tell "the site blocked me" from "my selector is wrong" from "there is
 * genuinely nothing here". Silent empty results are a bug, never a state.
 */
sealed class DataKhojException(
    message: String,
    val exitCode: Int,
    cause: Throwable? = null,
) : Exception(message, cause)

class ConfigException(message: String) :
    DataKhojException(message, 78)

class FetchException(
    val url: String,
    val reason: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : DataKhojException(
    "Failed to fetch $url — ${status?.let { "HTTP $it: " } ?: ""}$reason", 68, cause
)

class RobotsBlockedException(val url: String, val userAgent: String = "DataKhoj") :
    DataKhojException(
        "robots.txt disallows $url for user-agent '$userAgent'. " +
            "Disable 'Respect robots.txt' in job settings to override.",
        77
    )

class RateLimitedException(val url: String, val retryAfterSec: Double? = null) :
    DataKhojException(
        "Rate limited by $url." + (retryAfterSec?.let { " Retry-After: ${it}s." } ?: ""),
        75
    )

class ParseException(message: String) :
    DataKhojException(message, 65)

/**
 * Parsed fine, extracted zero rows.
 *
 * [diagnostic] explains *why* nothing matched — this is what stops the app
 * showing an empty table with no explanation.
 */
class NoResultsException(message: String, val diagnostic: String = "") :
    DataKhojException(if (diagnostic.isBlank()) message else "$message\n\n$diagnostic", 66)
