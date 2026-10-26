package com.logduck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers every request with [status], or throws while [offline]; records what it was sent. */
private class UsageTransport(var status: Int = 204) : LogDuckTransport {
    @Volatile var offline = false
    val requests = java.util.concurrent.CopyOnWriteArrayList<TransportRequest>()

    override fun perform(request: TransportRequest): TransportResponse {
        requests += request
        if (offline) throw IOException("offline")
        return TransportResponse(status, "", emptyMap())
    }

    fun hits(): List<JsonObject> = requests.flatMap { request ->
        Json.parseToJsonElement(request.body).jsonObject["hits"]!!.jsonArray.map { it.jsonObject }
    }
}

private class MapStorage : UsageStorage {
    val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

private val quiet = LogDuckLogger { _, _ -> }

class LogDuckUsageTest {
    private val transport = UsageTransport()
    private val storage = MapStorage()
    private val now = Instant.parse("2026-10-26T09:00:00Z")

    // A flush interval of an hour: no timer fires during a test, so every send
    // is one the test asked for with flush() or background().
    private fun usage(
        queueDir: File? = null,
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        logger: LogDuckLogger = quiet,
    ) = LogDuckUsage(
        LogDuckUsageOptions(
            key = "ld_client",
            appId = "com.example.app",
            storage = storage,
            appVersion = "2.3.0",
            osVersion = "15",
            queueDir = queueDir,
            flushInterval = Duration.ofHours(1),
            retryEnabled = false,
            transport = transport,
            logger = logger,
            clock = clock,
        ),
    )

    private fun LogDuckUsage.flushNow() {
        flush()
        awaitIdle()
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `a screen view is posted with the contract's fields and chains previousscreen`() {
        val usage = usage()
        usage.screen("Home")
        usage.screen("Checkout")
        usage.screen("Receipt", previous = "Cart")
        usage.flushNow()

        val request = transport.requests.single()
        assertEquals("https://api.logduck.com/v1/usage", request.url)
        assertEquals("text/plain", request.headers["Content-Type"])
        assertEquals("ld_client", Json.parseToJsonElement(request.body).jsonObject.text("key"))

        val (home, checkout, receipt) = transport.hits()
        assertEquals("screen.view", home.text("type"))
        assertEquals("Home", home.text("screen"))
        assertNull(home["previousscreen"])
        assertEquals("Home", checkout.text("previousscreen"))
        assertEquals("Cart", receipt.text("previousscreen"))

        assertTrue(Regex("^[0-9a-f]{32}$").matches(home.text("id")!!))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(home.text("sessionid")!!))
        assertEquals(home.text("sessionid"), receipt.text("sessionid"))
        assertEquals("2026-10-26T09:00:00Z", home.text("time"))
        assertEquals("android", home.text("platform"))
        assertEquals("com.example.app", home.text("host"))
        assertEquals("2.3.0", home.text("appversion"))
        assertEquals("15", home.text("osversion"))
        assertEquals(LogDuckClient.SDK_VERSION, home.text("sdkversion"))
        assertEquals(1, home["samplerate"]!!.jsonPrimitive.int)
        assertNull(home["installid"])
        assertNull(home["userid"])
        assertNull(home["loggedin"])
    }

    @Test
    fun `app install is sent once ever, then app open once per process`() {
        val first = usage()
        first.open()
        first.open()
        first.flushNow()
        assertEquals(listOf("app.install", "app.open"), transport.hits().map { it.text("type") })
        assertEquals("1", storage.values["logduck.installed"])

        transport.requests.clear()
        val second = usage()
        second.open()
        second.flushNow()
        assertEquals(listOf("app.open"), transport.hits().map { it.text("type") })
    }

    @Test
    fun `a blank screen name is ignored and a name is trimmed`() {
        val usage = usage()
        usage.screen("  ")
        usage.screen(" Home ")
        usage.flushNow()
        assertEquals(listOf("Home"), transport.hits().map { it.text("screen") })
    }

    @Test
    fun `foreground on a cold start records nothing`() {
        val usage = usage()
        usage.open()
        usage.foreground()
        usage.flushNow()
        assertEquals(listOf("app.install", "app.open"), transport.hits().map { it.text("type") })
    }

    @Test
    fun `nothing is stored before consent, which mints and persists an install id`() {
        val usage = usage()
        usage.screen("Home")
        usage.awaitIdle()
        assertTrue(storage.values.isEmpty())

        usage.consent(true)
        usage.screen("Settings")
        usage.flushNow()
        val installId = storage.values["logduck.installid"]
        assertNotNull(installId)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(installId))
        val (before, after) = transport.hits()
        assertNull(before["installid"])
        assertEquals(installId, after.text("installid"))

        // A later launch picks the stored id up again.
        transport.requests.clear()
        val relaunched = usage()
        relaunched.screen("Home")
        relaunched.flushNow()
        assertEquals(installId, transport.hits().single().text("installid"))

        transport.requests.clear()
        relaunched.consent(false)
        relaunched.screen("Home")
        relaunched.flushNow()
        assertNull(storage.values["logduck.installid"])
        assertNull(transport.hits().single()["installid"])
    }

    @Test
    fun `identify attaches userid and loggedin, and an empty id clears both`() {
        val usage = usage()
        usage.identify("user_42")
        usage.screen("Home")
        usage.identify("")
        usage.screen("Home")
        usage.flushNow()

        val (identified, cleared) = transport.hits()
        assertEquals("user_42", identified.text("userid"))
        assertTrue(identified["loggedin"]!!.jsonPrimitive.boolean)
        assertNull(cleared["userid"])
        assertNull(cleared["loggedin"])
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun `the fiftieth hit sends a batch without waiting for the timer`() {
        val usage = usage()
        repeat(49) { usage.screen("S$it") }
        usage.awaitIdle()
        assertTrue(transport.requests.isEmpty())

        usage.screen("S49")
        usage.awaitIdle()
        assertEquals(1, transport.requests.size)
        assertEquals(50, transport.hits().size)
    }

    @Test
    fun `a failed flush keeps the hits and the next flush resends them`() {
        val usage = usage()
        transport.offline = true
        usage.screen("Home")
        usage.flushNow()
        assertEquals(1, transport.requests.size)

        transport.offline = false
        transport.requests.clear()
        usage.screen("Next")
        usage.flushNow()
        assertEquals(listOf("Home", "Next"), transport.hits().map { it.text("screen") })
    }

    @Test
    fun `a 5xx keeps the hits too`() {
        val usage = usage()
        transport.status = 503
        usage.screen("Home")
        usage.flushNow()

        transport.status = 204
        transport.requests.clear()
        usage.flushNow()
        assertEquals(listOf("Home"), transport.hits().map { it.text("screen") })
    }

    @Test
    fun `a 4xx drops the batch`() {
        val usage = usage()
        transport.status = 400
        usage.screen("Home")
        usage.flushNow()
        assertEquals(1, transport.requests.size)

        transport.status = 204
        transport.requests.clear()
        usage.flushNow()
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `hits that could not be sent survive a relaunch when queueDir is set`() {
        val dir = Files.createTempDirectory("logduck-usage").toFile()
        try {
            transport.offline = true
            val first = usage(queueDir = dir)
            first.screen("Home")
            first.flushNow()
            assertTrue(File(dir, "logduck-usage-queue.json").exists())

            transport.offline = false
            transport.requests.clear()
            val second = usage(queueDir = dir)
            second.flushNow()
            assertEquals(listOf("Home"), transport.hits().map { it.text("screen") })
            assertFalse(File(dir, "logduck-usage-queue.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `background sends at once in a single call, and a long background starts a new session`() {
        var at = now
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = at
        }
        val usage = usage(clock = clock)
        repeat(60) { usage.screen("S$it") } // 50 sent at once, ten left queued
        usage.awaitIdle()
        transport.requests.clear()

        transport.offline = true
        usage.background()
        usage.awaitIdle()
        assertEquals(1, transport.requests.size)

        transport.offline = false
        transport.requests.clear()
        at = now.plus(Duration.ofMinutes(31))
        usage.foreground()
        usage.screen("Home")
        usage.flushNow()
        val hits = transport.hits()
        val before = hits.first { it.text("type") == "app.background" }
        val after = hits.first { it.text("type") == "app.foreground" }
        assertNotEquals(before.text("sessionid"), after.text("sessionid"))
        // A new session's first screen has no previous screen.
        val home = hits.first { it.text("screen") == "Home" }
        assertEquals(after.text("sessionid"), home.text("sessionid"))
        assertNull(home["previousscreen"])
    }

    @Test
    fun `a short background keeps the session and the previous screen`() {
        var at = now
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = at
        }
        val usage = usage(clock = clock)
        usage.screen("Home")
        usage.background()
        at = now.plus(Duration.ofMinutes(5))
        usage.foreground()
        usage.screen("Next")
        usage.flushNow()
        val hits = transport.hits()
        assertEquals(1, hits.map { it.text("sessionid") }.toSet().size)
        assertEquals("Home", hits.first { it.text("screen") == "Next" }.text("previousscreen"))
    }

    @Test
    fun `withdrawing consent strips installid from hits still queued, on disk too`() {
        val dir = Files.createTempDirectory("logduck-usage").toFile()
        try {
            val queueFile = File(dir, "logduck-usage-queue.json")
            transport.offline = true
            val usage = usage(queueDir = dir)
            usage.consent(true)
            usage.screen("Home")
            usage.flushNow()
            assertTrue(queueFile.readText().contains("installid"))

            usage.consent(false)
            usage.awaitIdle()
            assertFalse(queueFile.readText().contains("installid"))

            transport.offline = false
            transport.requests.clear()
            usage.flushNow()
            val hit = transport.hits().single()
            assertEquals("Home", hit.text("screen"))
            assertNull(hit["installid"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `background writes the queue to disk before it sends`() {
        val dir = Files.createTempDirectory("logduck-usage").toFile()
        try {
            val queueFile = File(dir, "logduck-usage-queue.json")
            var onDiskDuringSend = false
            val usage = LogDuckUsage(
                LogDuckUsageOptions(
                    key = "ld_client",
                    appId = "com.example.app",
                    queueDir = dir,
                    flushInterval = Duration.ofHours(1),
                    transport = LogDuckTransport { request ->
                        onDiskDuringSend = queueFile.exists()
                        transport.perform(request)
                    },
                    logger = quiet,
                ),
            )
            usage.screen("Home")
            usage.background()
            usage.awaitIdle()
            assertTrue(onDiskDuringSend)
            assertFalse(queueFile.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `dimensions are attached, and invalid keys dropped`() {
        val usage = usage()
        usage.setDimensions(mapOf("variant" to "b", "Bad-Key" to "x"))
        usage.screen("Home")
        usage.flushNow()
        val dimensions = transport.hits().single()["dimensions"]!!.jsonObject
        assertEquals(setOf("variant"), dimensions.keys)
    }

    @Test
    fun `a reserved dimension key is dropped with one warning and the hit still sent`() {
        val warnings = mutableListOf<String>()
        val usage = usage(logger = { message, _ -> warnings += message })
        usage.setDimensions(mapOf("variant" to "b", "user_id" to "42"))
        usage.screen("Home")
        usage.flushNow()
        val hit = transport.hits().single()
        assertEquals(setOf("variant"), hit["dimensions"]!!.jsonObject.keys)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("user_id"))
    }
}
