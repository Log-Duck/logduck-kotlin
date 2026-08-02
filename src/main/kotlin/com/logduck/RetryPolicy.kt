package com.logduck

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * What to do about a response.
 *
 * Kept apart from the client so the rules — what counts as retryable, how long
 * to wait, which error to surface — can be tested without any networking.
 */
internal sealed interface RetryDecision {
    /** The response is a 2xx; the caller parses the body. */
    data object Succeed : RetryDecision

    /** Wait, then send the same request again. */
    data class Retry(val delay: Duration, val reason: String) : RetryDecision

    /** Give up. */
    data class Fail(val error: LogDuckException, val warning: String) : RetryDecision
}

internal object RetryPolicy {
    /** Delay before retrying a 5xx or a network failure. */
    val TRANSIENT_RETRY_DELAY: Duration = Duration.ofSeconds(1)

    /**
     * The server rate-limits in fixed one-minute windows, so a missing or
     * unparseable `Retry-After` cannot mean less than a full window. Assume the
     * maximum rather than retrying immediately and adding load to a server that
     * just asked us to back off.
     */
    val RETRY_AFTER_FALLBACK: Duration = Duration.ofSeconds(60)

    fun decide(
        status: Int,
        retryAfterHeader: String?,
        body: String,
        attempt: Int,
        maxAttempts: Int,
        maxRetryDelay: Duration,
    ): RetryDecision {
        if (status in 200..299) return RetryDecision.Succeed

        // 429 is the one 4xx worth retrying: the server tells us when its
        // window resets, and the unchanged Idempotency-Key makes it safe.
        if (status == 429) {
            val retryAfter = parseRetryAfter(retryAfterHeader)
            val seconds = retryAfter.seconds

            if (attempt < maxAttempts && retryAfter <= maxRetryDelay) {
                return RetryDecision.Retry(retryAfter, "rate limited, retrying in ${seconds}s")
            }

            // Out of attempts, or the wait is longer than a caller should be
            // blocked for. Surface retryAfter so the event can be queued.
            return RetryDecision.Fail(
                LogDuckException(
                    "Rate limit exceeded. Retry after ${seconds}s.",
                    status = status,
                    body = body,
                    retryAfter = retryAfter,
                ),
                "event dropped: rate limit exceeded, retry after ${seconds}s",
            )
        }

        // Any other 4xx is our fault and will fail identically next time.
        if (status in 400..499) {
            val message = clientErrorMessage(status)
            return RetryDecision.Fail(
                LogDuckException(message, status = status, body = body),
                "event failed: $message",
            )
        }

        if (attempt < maxAttempts) {
            return RetryDecision.Retry(
                TRANSIENT_RETRY_DELAY,
                "request failed with status $status, retrying",
            )
        }

        return RetryDecision.Fail(
            LogDuckException(
                "Request failed with status $status after $maxAttempts attempts",
                status = status,
                body = body,
            ),
            "event failed after $maxAttempts attempts",
        )
    }

    /** Classifies a network failure — no response ever arrived. */
    fun decideTransportFailure(
        error: Throwable,
        attempt: Int,
        maxAttempts: Int,
    ): RetryDecision {
        if (attempt < maxAttempts) {
            return RetryDecision.Retry(
                TRANSIENT_RETRY_DELAY,
                "request failed (${error.message}), retrying",
            )
        }

        return RetryDecision.Fail(
            LogDuckException(
                "${error.message} (after $maxAttempts attempts)",
                cause = error,
            ),
            "event failed after $maxAttempts attempts: ${error.message}",
        )
    }

    /**
     * How long to wait, from either form the spec allows.
     *
     * `Retry-After` may be delta-seconds or an HTTP-date. Treating the date
     * form as unparseable would fall back to a full minute and delay events
     * that were only seconds from being sendable.
     */
    fun parseRetryAfter(header: String?, now: Instant = Instant.now()): Duration {
        val value = header?.trim()
        if (value.isNullOrEmpty()) return RETRY_AFTER_FALLBACK

        value.toLongOrNull()?.let { seconds ->
            return if (seconds > 0) Duration.ofSeconds(seconds) else Duration.ZERO
        }

        return try {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            val wait = Duration.between(now, date.toInstant())
            if (wait.isNegative) Duration.ZERO else wait
        } catch (_: DateTimeParseException) {
            RETRY_AFTER_FALLBACK
        }
    }

    private fun clientErrorMessage(status: Int): String = when (status) {
        400 -> "Validation error"
        401 -> "Invalid API key"
        403 -> "API key expired or unauthorized"
        else -> "Request failed with status $status"
    }
}
