package com.logduck

import java.time.Duration

/**
 * Configuration for [LogDuckClient].
 *
 * `@JvmOverloads` is load-bearing, not decoration: Kotlin default arguments do
 * not cross the Java boundary, so without it a Java caller has to pass all
 * seven arguments to set the two that are required.
 */
public data class LogDuckOptions @JvmOverloads constructor(
    /** Your LogDuck API key (required). Must start with `ld_`. */
    public val apiKey: String,

    /**
     * Identifies the app or service sending events (required, max 200).
     * Set once; it is attached to every event.
     */
    public val source: String,

    /**
     * When false (the default) errors are logged and `send` returns `null`.
     * When true they are thrown as [LogDuckException].
     *
     * The default is deliberate: a logging SDK that throws can take down the
     * code it was only meant to observe.
     */
    public val throwOnError: Boolean = false,

    /** Retry once on transient failures — 5xx, network errors and rate limits. */
    public val retryEnabled: Boolean = true,

    /**
     * Longest the client will wait before retrying a rate-limited (429)
     * request.
     *
     * The server rate-limits per API key in fixed one-minute windows, so its
     * `Retry-After` can be as much as 60 seconds. Blocking a caller that long
     * is rarely acceptable, so when `Retry-After` exceeds this cap the client
     * gives up immediately. The failure carries [LogDuckException.retryAfter]
     * so the application can queue the event instead of losing it.
     */
    public val maxRetryDelay: Duration = Duration.ofSeconds(10),

    /** Request timeout. */
    public val timeout: Duration = Duration.ofSeconds(30),

    /** API base URL. Override only for a self-hosted deployment or in tests. */
    public val baseUrl: String = "https://api.logduck.com",
)

/** Where the client sends its warnings. */
public fun interface LogDuckLogger {
    public fun warn(message: String, error: Throwable?)
}

/** The default logger: writes to stderr. */
public object StderrLogger : LogDuckLogger {
    override fun warn(message: String, error: Throwable?) {
        System.err.println("[LogDuck] $message")
        error?.printStackTrace()
    }
}
