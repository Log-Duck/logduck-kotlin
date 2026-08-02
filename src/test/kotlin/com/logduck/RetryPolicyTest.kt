package com.logduck

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The retry rules, tested without any networking. */
class RetryPolicyTest {

    private fun decide(
        status: Int,
        attempt: Int = 1,
        retryAfter: String? = null,
        cap: Duration = Duration.ofSeconds(10),
    ) = RetryPolicy.decide(
        status = status,
        retryAfterHeader = retryAfter,
        body = "",
        attempt = attempt,
        maxAttempts = 2,
        maxRetryDelay = cap,
    )

    @Test
    fun `2xx succeeds`() {
        assertIs<RetryDecision.Succeed>(decide(200))
        assertIs<RetryDecision.Succeed>(decide(204))
    }

    @Test
    fun `5xx retries while attempts remain`() {
        assertIs<RetryDecision.Retry>(decide(503, attempt = 1))
    }

    @Test
    fun `5xx fails once attempts run out`() {
        assertIs<RetryDecision.Fail>(decide(503, attempt = 2))
    }

    @Test
    fun `4xx never retries`() {
        // It will fail identically the second time; retrying only doubles the load.
        for (status in listOf(400, 401, 403, 404, 422)) {
            assertIs<RetryDecision.Fail>(decide(status, attempt = 1), "$status should not retry")
        }
    }

    @Test
    fun `429 retries when the wait is within the cap`() {
        val decision = assertIs<RetryDecision.Retry>(decide(429, retryAfter = "5"))

        assertEquals(Duration.ofSeconds(5), decision.delay)
    }

    @Test
    fun `429 gives up when the wait exceeds the cap`() {
        // The server rate-limits in one-minute windows. Blocking a caller for a
        // full window is worse than dropping the event, so the failure carries
        // retryAfter and lets the application queue it instead.
        val decision = assertIs<RetryDecision.Fail>(decide(429, retryAfter = "45"))

        assertEquals(Duration.ofSeconds(45), decision.error.retryAfter)
    }

    @Test
    fun `missing retry-after assumes a full window`() {
        assertEquals(Duration.ofSeconds(60), RetryPolicy.parseRetryAfter(null))
        assertEquals(Duration.ofSeconds(60), RetryPolicy.parseRetryAfter(""))
    }

    @Test
    fun `unparseable retry-after assumes a full window`() {
        // Anything else would risk rounding down to zero and hammering a server
        // that just asked us to back off.
        assertEquals(Duration.ofSeconds(60), RetryPolicy.parseRetryAfter("soon"))
    }

    @Test
    fun `retry-after accepts delta seconds`() {
        assertEquals(Duration.ofSeconds(30), RetryPolicy.parseRetryAfter("30"))
    }

    @Test
    fun `retry-after accepts an http date`() {
        // The spec allows either form. Treating the date form as unparseable
        // would fall back to a full minute and delay events needlessly.
        val now = Instant.parse("2026-08-02T09:00:00Z")

        val wait = RetryPolicy.parseRetryAfter("Sun, 02 Aug 2026 09:00:45 GMT", now)

        assertEquals(Duration.ofSeconds(45), wait)
    }

    @Test
    fun `a retry-after in the past is clamped to zero`() {
        val now = Instant.parse("2026-08-02T09:00:00Z")

        assertEquals(
            Duration.ZERO,
            RetryPolicy.parseRetryAfter("Sun, 02 Aug 2026 08:59:30 GMT", now),
        )
    }
}
