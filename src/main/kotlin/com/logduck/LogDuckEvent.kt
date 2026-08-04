package com.logduck

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * An event to send to LogDuck.
 *
 * `@JvmOverloads` so a Java caller can write `new LogDuckEvent("order.placed")`
 * rather than passing all six arguments — Kotlin default arguments generate no
 * overloads for Java on their own.
 */
public data class LogDuckEvent @JvmOverloads constructor(
    /**
     * What happened, e.g. `user.signup` (required, max 100 characters).
     *
     * The server lowercases this and requires it to match `^[a-z][a-z0-9_.]*$`
     * — letters, digits, underscores and dots, starting with a letter. A hyphen
     * is rejected with a 400.
     */
    public val type: String,

    /** The resource the event is about, e.g. a user or order id (max 500). */
    public val subject: String? = null,

    /**
     * Groups related events within a session (max 256).
     *
     * Sent as `sessionid`: a CloudEvents extension attribute name must be
     * lowercase alphanumeric, so the wire name differs from this one.
     */
    public val sessionId: String? = null,

    /**
     * Human-readable summary of the event (max 500 characters).
     *
     * This is what a push notification shows. Without it the body falls back
     * to listing the first few [data] keys.
     */
    public val message: String? = null,

    /** When it happened. Defaults to server time if unset. */
    public val time: Instant? = null,

    /**
     * Arbitrary JSON payload.
     *
     * Values may be strings, numbers, booleans, nulls, lists or nested maps.
     * Anything else is recorded as its `toString()` rather than dropped —
     * losing a field silently is worse than recording an approximation.
     */
    public val data: Map<String, Any?>? = null,

    /** Shown alongside the event in the dashboard (max 10 characters). */
    public val emoji: String? = null,
)

/** What LogDuck returns for an accepted event. */
@Serializable
public data class LogDuckResponse(
    public val success: Boolean = false,
    public val eventId: String = "",
    public val time: String = "",
)

/** Converts a loosely typed payload value into JSON. */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}
