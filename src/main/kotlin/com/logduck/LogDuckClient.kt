package com.logduck

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Sends events to LogDuck.
 *
 * ```kotlin
 * val logduck = LogDuckClient(LogDuckOptions(apiKey = "ld_...", source = "checkout-api"))
 * logduck.send(LogDuckEvent(type = "order.placed", subject = "order_9127"))
 * ```
 *
 * One instance is enough for a process — it holds no per-request state and is
 * safe to share between threads.
 */
public class LogDuckClient(
    private val options: LogDuckOptions,
    private val transport: LogDuckTransport = HttpUrlConnectionTransport(options.timeout),
    private val logger: LogDuckLogger = StderrLogger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val userAgent = "LogDuck-Kotlin/$SDK_VERSION"

    private val json = Json {
        // A field the server adds later must not break an older client.
        ignoreUnknownKeys = true
    }

    init {
        if (options.apiKey.isEmpty()) {
            logger.warn("API key is not configured - all requests will fail", null)
        }
        if (options.source.isEmpty()) {
            logger.warn("source is not configured - all requests will fail", null)
        }
    }

    /**
     * Checks the configuration without sending anything.
     *
     * Returns `false` and logs a warning for each problem, which makes it
     * useful as a startup assertion.
     */
    public fun validateConfiguration(): Boolean {
        var valid = true

        if (options.apiKey.isEmpty()) {
            logger.warn("validation failed: API key is not configured", null)
            valid = false
        } else if (!options.apiKey.startsWith("ld_")) {
            logger.warn("validation failed: API key must start with 'ld_'", null)
            valid = false
        }

        if (options.source.isEmpty()) {
            logger.warn("validation failed: source is not configured", null)
            valid = false
        } else if (options.source.length > SOURCE_LIMIT) {
            logger.warn("validation failed: source exceeds $SOURCE_LIMIT characters", null)
            valid = false
        }

        return valid
    }

    /**
     * Sends one event.
     *
     * Returns the created event, or `null` when the send failed and
     * [LogDuckOptions.throwOnError] is off (the default).
     */
    public suspend fun send(event: LogDuckEvent): LogDuckResponse? {
        validate(event)?.let { problem ->
            return fail(LogDuckException(problem, status = 400), "validation failed: $problem")
        }

        // One key for the whole call, reused across retries: that is what makes
        // a retry safe, since the server deduplicates on it.
        val request = TransportRequest(
            url = "${options.baseUrl.trimEnd('/')}/v1/events",
            headers = mapOf(
                "Content-Type" to "application/json",
                "X-API-Key" to options.apiKey,
                // The server requires 16-36 characters of [A-Za-z0-9_-]. A UUID
                // is 36 with hyphens, so it fits exactly.
                "Idempotency-Key" to UUID.randomUUID().toString(),
                "User-Agent" to userAgent,
            ),
            body = json.encodeToString(JsonObject.serializer(), payload(event)),
        )

        val maxAttempts = if (options.retryEnabled) 2 else 1

        for (attempt in 1..maxAttempts) {
            val response = try {
                withContext(dispatcher) { transport.perform(request) }
            } catch (error: Exception) {
                when (
                    val decision =
                        RetryPolicy.decideTransportFailure(error, attempt, maxAttempts)
                ) {
                    is RetryDecision.Retry -> {
                        logger.warn(decision.reason, null)
                        delay(decision.delay.toMillis())
                        continue
                    }
                    is RetryDecision.Fail -> return fail(decision.error, decision.warning)
                    RetryDecision.Succeed -> error("a transport failure cannot succeed")
                }
            }

            val decision = RetryPolicy.decide(
                status = response.status,
                retryAfterHeader = response.header("Retry-After"),
                body = response.body,
                attempt = attempt,
                maxAttempts = maxAttempts,
                maxRetryDelay = options.maxRetryDelay,
            )

            when (decision) {
                RetryDecision.Succeed -> return parse(response.body, response.status)
                is RetryDecision.Retry -> {
                    logger.warn(decision.reason, null)
                    delay(decision.delay.toMillis())
                }
                is RetryDecision.Fail -> return fail(decision.error, decision.warning)
            }
        }

        return null
    }

    /**
     * Blocking [send], for Java callers and anywhere without a coroutine scope.
     */
    public fun sendBlocking(event: LogDuckEvent): LogDuckResponse? = runBlocking { send(event) }

    // -- request construction ------------------------------------------------

    private fun validate(event: LogDuckEvent): String? = when {
        event.type.isBlank() -> "type is required"
        event.type.length > TYPE_LIMIT -> "type exceeds $TYPE_LIMIT characters"
        options.source.isEmpty() -> "source is not configured"
        options.source.length > SOURCE_LIMIT -> "source exceeds $SOURCE_LIMIT characters"
        (event.subject?.length ?: 0) > SUBJECT_LIMIT ->
            "subject exceeds $SUBJECT_LIMIT characters"
        (event.sessionId?.length ?: 0) > SESSION_ID_LIMIT ->
            "sessionId exceeds $SESSION_ID_LIMIT characters"
        (event.emoji?.length ?: 0) > EMOJI_LIMIT -> "emoji exceeds $EMOJI_LIMIT characters"
        else -> null
    }

    /**
     * Absent fields are omitted rather than sent as null, so the payload stays
     * honest about what the caller actually set.
     */
    private fun payload(event: LogDuckEvent): JsonObject = buildMap {
        put("type", JsonPrimitive(event.type))
        put("source", JsonPrimitive(options.source))
        event.subject?.let { put("subject", JsonPrimitive(it)) }
        event.sessionId?.let { put("sessionId", JsonPrimitive(it)) }
        event.time?.let { put("time", JsonPrimitive(ISO_INSTANT.format(it))) }
        event.data?.let { put("data", it.toJsonElement()) }
        event.emoji?.let { put("emoji", JsonPrimitive(it)) }
    }.let(::JsonObject)

    // -- outcomes ------------------------------------------------------------

    private fun parse(body: String, status: Int): LogDuckResponse? = try {
        json.decodeFromString(LogDuckResponse.serializer(), body)
    } catch (error: Exception) {
        fail(
            LogDuckException("Failed to parse response", status = status, body = body, cause = error),
            "event failed: could not parse response",
        )
    }

    /** Throw or warn-and-return-null, depending on [LogDuckOptions.throwOnError]. */
    private fun fail(error: LogDuckException, warning: String): LogDuckResponse? {
        if (options.throwOnError) throw error
        logger.warn(warning, error)
        return null
    }

    internal companion object {
        /**
         * Goes out in the User-Agent of every request. The release workflow
         * checks the git tag against it and refuses to publish on a mismatch,
         * so the traffic cannot lie about which build sent it.
         */
        const val SDK_VERSION: String = "1.0.0"

        const val TYPE_LIMIT = 100
        const val SOURCE_LIMIT = 200
        const val SUBJECT_LIMIT = 500
        const val SESSION_ID_LIMIT = 256
        const val EMOJI_LIMIT = 10

        private val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
