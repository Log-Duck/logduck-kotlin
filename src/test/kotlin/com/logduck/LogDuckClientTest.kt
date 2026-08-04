package com.logduck

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A transport that answers from a script and records what it was asked to send. */
private class FakeTransport(replies: List<Reply>) : LogDuckTransport {
    sealed interface Reply {
        data class Http(
            val status: Int,
            val body: String = "{}",
            val headers: Map<String, String> = emptyMap(),
        ) : Reply

        data class Failure(val error: Exception) : Reply
    }

    private val queue = replies.toMutableList()
    val requests = mutableListOf<TransportRequest>()

    val callCount: Int get() = requests.size

    override fun perform(request: TransportRequest): TransportResponse {
        requests += request
        // The last scripted reply repeats, so a test asserting "never retries"
        // does not have to know how many attempts it is guarding against.
        val reply = if (queue.size > 1) queue.removeAt(0) else queue[0]

        return when (reply) {
            is Reply.Failure -> throw reply.error
            is Reply.Http -> TransportResponse(reply.status, reply.body, reply.headers)
        }
    }

    companion object {
        fun ok() = Reply.Http(
            200,
            """{"success":true,"eventId":"evt_1","time":"2026-08-02T09:00:00.000Z"}""",
        )

        fun status(code: Int, headers: Map<String, String> = emptyMap()) =
            Reply.Http(code, "{}", headers)

        fun failure() = Reply.Failure(IOException("connection refused"))
    }
}

/** Discards warnings so the test output stays readable. */
private val silent = LogDuckLogger { _, _ -> }

class LogDuckClientTest {

    private fun client(
        transport: FakeTransport,
        throwOnError: Boolean = false,
        retryEnabled: Boolean = true,
        // Zero by default so a rate-limit retry does not actually sleep.
        maxRetryDelay: Duration = Duration.ZERO,
        source: String = "test-suite",
    ) = LogDuckClient(
        options = LogDuckOptions(
            apiKey = "ld_test_key",
            source = source,
            throwOnError = throwOnError,
            retryEnabled = retryEnabled,
            maxRetryDelay = maxRetryDelay,
        ),
        transport = transport,
        logger = silent,
    )

    private fun TransportRequest.json(): JsonObject =
        Json.parseToJsonElement(body).jsonObject

    // -- the request it builds ---------------------------------------------

    @Test
    fun `posts to v1 events with auth headers`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        client(transport).send(LogDuckEvent(type = "user.signup"))

        val request = transport.requests.single()
        assertEquals("https://api.logduck.com/v1/events", request.url)
        assertEquals("ld_test_key", request.headers["X-API-Key"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("LogDuck-Kotlin/"))
    }

    @Test
    fun `sends an idempotency key the server will accept`() = runTest {
        // The server enforces ^[a-zA-Z0-9_-]{16,36}$ and rejects the request
        // outright without it, so this is part of the contract, not a nicety.
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        client(transport).send(LogDuckEvent(type = "user.signup"))

        val key = transport.requests.single().headers["Idempotency-Key"]!!
        assertTrue(Regex("^[a-zA-Z0-9_-]{16,36}$").matches(key), "$key would be rejected")
    }

    @Test
    fun `source comes from the client not the event`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        client(transport, source = "checkout-api").send(LogDuckEvent(type = "order.placed"))

        assertEquals(
            "checkout-api",
            transport.requests.single().json()["source"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `omits absent fields rather than sending null`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        client(transport).send(LogDuckEvent(type = "user.signup"))

        val body = transport.requests.single().json()
        assertFalse(body.containsKey("subject"))
        assertFalse(body.containsKey("emoji"))
        assertFalse(body.containsKey("time"))
        assertFalse(body.containsKey("message"))
        assertFalse(body.containsKey("sessionid"))
    }

    /**
     * The wire names are the contract, and they are not the Kotlin property
     * names. A CloudEvents extension attribute name must be lowercase
     * alphanumeric, so sessionId goes out as sessionid. The server discards
     * what it does not recognise rather than erroring, so getting this wrong
     * loses the field silently - hence asserting the camelCase spelling is
     * absent, not just that the lowercase one is present.
     */
    @Test
    fun `sends CloudEvents wire names`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        client(transport).send(
            LogDuckEvent(
                type = "order.placed",
                subject = "order_9127",
                sessionId = "sess_8f21c",
                message = "Order placed on the Pro plan",
            ),
        )

        val body = transport.requests.single().json()
        assertEquals("sess_8f21c", body["sessionid"]!!.jsonPrimitive.content)
        assertEquals("Order placed on the Pro plan", body["message"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("sessionId"))
        assertFalse(body.containsKey("metadata"))
    }

    @Test
    fun `rejects an over-long message without touching the network`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        val result = client(transport).send(
            LogDuckEvent(type = "order.placed", message = "x".repeat(501)),
        )

        assertNull(result)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `encodes structured data including nesting`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        client(transport).send(
            LogDuckEvent(
                type = "order.placed",
                data = mapOf(
                    "total" to 4999,
                    "gift" to false,
                    "customer" to mapOf("id" to "cus_1"),
                    "tags" to listOf("new", "eu"),
                ),
            ),
        )

        val data = transport.requests.single().json()["data"]!!.jsonObject
        assertEquals(4999, data["total"]!!.jsonPrimitive.int)
        assertEquals(false, data["gift"]!!.jsonPrimitive.boolean)
        assertEquals("cus_1", data["customer"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `serialises time as ISO 8601`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        client(transport).send(
            LogDuckEvent(type = "user.signup", time = Instant.parse("2026-08-02T09:00:00Z")),
        )

        assertEquals(
            "2026-08-02T09:00:00Z",
            transport.requests.single().json()["time"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `returns the parsed response`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        val response = client(transport).send(LogDuckEvent(type = "user.signup"))

        assertNotNull(response)
        assertTrue(response.success)
        assertEquals("evt_1", response.eventId)
    }

    @Test
    fun `tolerates unknown fields in the response`() = runTest {
        // A field the server adds later must not break an older client.
        val transport = FakeTransport(
            listOf(
                FakeTransport.Reply.Http(
                    200,
                    """{"success":true,"eventId":"evt_1","time":"t","futureField":42}""",
                ),
            ),
        )

        assertNotNull(client(transport).send(LogDuckEvent(type = "user.signup")))
    }

    // -- validation ---------------------------------------------------------

    @Test
    fun `rejects a blank type without touching the network`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.ok()))

        assertNull(client(transport).send(LogDuckEvent(type = "   ")))
        assertEquals(0, transport.callCount)
    }

    @Test
    fun `rejects over-long fields`() = runTest {
        val cases = listOf(
            LogDuckEvent(type = "a".repeat(101)),
            LogDuckEvent(type = "ok", subject = "a".repeat(501)),
            LogDuckEvent(type = "ok", sessionId = "a".repeat(257)),
            LogDuckEvent(type = "ok", emoji = "a".repeat(11)),
        )

        for (event in cases) {
            val transport = FakeTransport(listOf(FakeTransport.ok()))
            assertNull(client(transport).send(event))
            assertEquals(0, transport.callCount, "${event.type} should not reach the network")
        }
    }

    @Test
    fun `flags an api key without the ld prefix`() {
        val invalid = LogDuckClient(
            options = LogDuckOptions(apiKey = "oops", source = "s"),
            transport = FakeTransport(listOf(FakeTransport.ok())),
            logger = silent,
        )

        assertFalse(invalid.validateConfiguration())
    }

    @Test
    fun `accepts a well-formed configuration`() {
        assertTrue(client(FakeTransport(listOf(FakeTransport.ok()))).validateConfiguration())
    }

    // -- failure handling ---------------------------------------------------

    @Test
    fun `returns null instead of throwing by default`() = runTest {
        // A logging SDK must not take down the code it was meant to observe.
        val transport = FakeTransport(listOf(FakeTransport.status(401)))

        assertNull(client(transport).send(LogDuckEvent(type = "user.signup")))
    }

    @Test
    fun `throws when throwOnError is set`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.status(401)))

        val error = assertFailsWith<LogDuckException> {
            client(transport, throwOnError = true).send(LogDuckEvent(type = "user.signup"))
        }

        assertEquals(401, error.status)
    }

    @Test
    fun `does not retry a 4xx`() = runTest {
        // A validation error will fail identically the second time; retrying it
        // only doubles the load.
        val transport = FakeTransport(listOf(FakeTransport.status(400)))

        client(transport).send(LogDuckEvent(type = "user.signup"))

        assertEquals(1, transport.callCount)
    }

    @Test
    fun `retries a 5xx once and succeeds`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.status(503), FakeTransport.ok()))

        val response = client(transport).send(LogDuckEvent(type = "user.signup"))

        assertNotNull(response)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `retries a network failure once`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.failure(), FakeTransport.ok()))

        val response = client(transport).send(LogDuckEvent(type = "user.signup"))

        assertNotNull(response)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `reuses the same idempotency key across a retry`() = runTest {
        // This is what makes retrying safe: the server deduplicates on the key,
        // so a retry after a timeout cannot double-post the event.
        val transport = FakeTransport(listOf(FakeTransport.status(503), FakeTransport.ok()))

        client(transport).send(LogDuckEvent(type = "user.signup"))

        assertEquals(2, transport.requests.size)
        assertEquals(
            transport.requests[0].headers["Idempotency-Key"],
            transport.requests[1].headers["Idempotency-Key"],
        )
    }

    @Test
    fun `sends exactly once when retries are disabled`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.status(503)))

        assertNull(client(transport, retryEnabled = false).send(LogDuckEvent(type = "u.s")))
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `a malformed success body is a failure not a crash`() = runTest {
        val transport = FakeTransport(listOf(FakeTransport.Reply.Http(200, "not json")))

        assertNull(client(transport).send(LogDuckEvent(type = "user.signup")))
    }

    // -- rate limits --------------------------------------------------------

    @Test
    fun `retries a rate limit within the cap`() = runTest {
        val transport = FakeTransport(
            listOf(
                FakeTransport.status(429, mapOf("Retry-After" to "0")),
                FakeTransport.ok(),
            ),
        )

        val response = client(transport, maxRetryDelay = Duration.ofSeconds(10))
            .send(LogDuckEvent(type = "user.signup"))

        assertNotNull(response)
        assertEquals(2, transport.callCount)
    }

    @Test
    fun `gives up immediately when retry-after exceeds the cap`() = runTest {
        // The server rate-limits in one-minute windows, so Retry-After can be
        // 60s. Blocking a caller that long is worse than dropping the event.
        val transport = FakeTransport(
            listOf(FakeTransport.status(429, mapOf("Retry-After" to "45"))),
        )

        val error = assertFailsWith<LogDuckException> {
            client(transport, throwOnError = true, maxRetryDelay = Duration.ofSeconds(10))
                .send(LogDuckEvent(type = "user.signup"))
        }

        assertEquals(429, error.status)
        assertEquals(Duration.ofSeconds(45), error.retryAfter)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `finds retry-after whatever its casing`() = runTest {
        val transport = FakeTransport(
            listOf(FakeTransport.status(429, mapOf("retry-after" to "45"))),
        )

        val error = assertFailsWith<LogDuckException> {
            client(transport, throwOnError = true, maxRetryDelay = Duration.ofSeconds(10))
                .send(LogDuckEvent(type = "user.signup"))
        }

        assertEquals(Duration.ofSeconds(45), error.retryAfter)
    }
}
