package com.logduck

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The default transport, against a real socket.
 *
 * Every other test injects a fake, so without this the code that actually
 * talks to the network is never executed. Two of its details fail silently if
 * they are wrong: a 4xx body arrives on `errorStream` and reading
 * `inputStream` instead throws, and `Retry-After` comes back under whatever
 * casing the server chose.
 */
class HttpUrlConnectionTransportTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val received = mutableListOf<Pair<Map<String, String>, String>>()

    /** Set per test to control what the stub server answers. */
    private var respond: (HttpExchange) -> Unit = { accept(it) }

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/events") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = exchange.requestHeaders.entries.associate { it.key to it.value.first() }
            received += headers to body
            respond(exchange)
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun accept(exchange: HttpExchange) {
        val payload =
            """{"success":true,"eventId":"evt_live","time":"2026-08-02T09:00:00.000Z"}"""
        respondWith(exchange, 200, payload)
    }

    private fun respondWith(
        exchange: HttpExchange,
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun client(throwOnError: Boolean = false) = LogDuckClient(
        options = LogDuckOptions(
            apiKey = "ld_live_key",
            source = "transport-test",
            throwOnError = throwOnError,
            retryEnabled = false,
            baseUrl = baseUrl,
            timeout = Duration.ofSeconds(5),
        ),
        logger = LogDuckLogger { _, _ -> },
    )

    @Test
    fun `sends a real request the server can read`() = runTest {
        val response = client().send(
            LogDuckEvent(type = "user.signup", subject = "user_42", data = mapOf("plan" to "pro")),
        )

        assertNotNull(response)
        assertEquals("evt_live", response.eventId)

        val (headers, body) = received.single()
        // HttpExchange normalises header names to Camel-Case.
        assertEquals("ld_live_key", headers["X-api-key"])
        assertTrue(headers["User-agent"]!!.startsWith("LogDuck-Kotlin/"))
        assertTrue(Regex("^[a-zA-Z0-9_-]{16,36}$").matches(headers["Idempotency-key"]!!))

        val payload = Json.parseToJsonElement(body).jsonObject
        assertEquals("user.signup", payload["type"]!!.jsonPrimitive.content)
        assertEquals("transport-test", payload["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reads a 4xx body from the error stream`() = runTest {
        // HttpURLConnection throws from inputStream on a 4xx and routes the
        // body to errorStream. Getting this wrong loses the server's
        // explanation of what was wrong with the request.
        respond = { respondWith(it, 400, """{"error":"Validation error","field":"type"}""") }

        val error = assertNotNull(
            runCatching { client(throwOnError = true).send(LogDuckEvent(type = "bad")) }
                .exceptionOrNull() as? LogDuckException,
        )

        assertEquals(400, error.status)
        assertTrue(
            error.body!!.contains("Validation error"),
            "the server's explanation should survive: ${error.body}",
        )
    }

    @Test
    fun `reads Retry-After off a real 429`() = runTest {
        respond = { respondWith(it, 429, """{"error":"rate limited"}""", mapOf("Retry-After" to "45")) }

        val error = assertNotNull(
            runCatching { client(throwOnError = true).send(LogDuckEvent(type = "user.signup")) }
                .exceptionOrNull() as? LogDuckException,
        )

        assertEquals(Duration.ofSeconds(45), error.retryAfter)
    }

    @Test
    fun `a connection failure is reported, not thrown raw`() = runTest {
        // Point at a port nothing is listening on.
        val unreachable = LogDuckClient(
            options = LogDuckOptions(
                apiKey = "ld_k",
                source = "s",
                retryEnabled = false,
                baseUrl = "http://127.0.0.1:1",
                timeout = Duration.ofSeconds(2),
            ),
            logger = LogDuckLogger { _, _ -> },
        )

        assertNull(unreachable.send(LogDuckEvent(type = "user.signup")))
    }
}
