package com.logduck

import java.time.Duration

/**
 * Thrown when a request fails and [LogDuckOptions.throwOnError] is enabled.
 *
 * When it is not (the default), the same object is passed to the configured
 * logger so the detail is still available.
 */
public class LogDuckException(
    message: String,
    /** HTTP status, or `null` when the request never got a response. */
    public val status: Int? = null,
    /** Raw response body, when there was one. */
    public val body: String? = null,
    /**
     * Set on a 429: how long the server asked us to wait.
     *
     * Present so the application can queue the event and send it later rather
     * than drop it — the client itself refuses to block for a full rate-limit
     * window.
     */
    public val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
