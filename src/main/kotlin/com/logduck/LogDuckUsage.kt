package com.logduck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where [LogDuckUsage] keeps the two values that must outlive the process: the
 * install id (only after [LogDuckUsage.consent]) and the flag that `app.install`
 * was sent.
 *
 * On Android, back it with `SharedPreferences`; on a plain JVM use
 * [FileUsageStorage]. Called from the SDK's background thread only.
 */
public interface UsageStorage {
    public fun get(key: String): String?
    public fun put(key: String, value: String)
    public fun remove(key: String)
}

/** A [UsageStorage] in a properties file inside [dir], for a plain JVM. */
public class FileUsageStorage(dir: File) : UsageStorage {
    private val file = File(dir, "logduck-usage.properties")
    private val properties = Properties().apply {
        if (file.exists()) file.inputStream().use(::load)
    }

    @Synchronized
    override fun get(key: String): String? = properties.getProperty(key)

    @Synchronized
    override fun put(key: String, value: String) {
        properties.setProperty(key, value)
        save()
    }

    @Synchronized
    override fun remove(key: String) {
        if (properties.remove(key) != null) save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, null) }
    }
}

/**
 * Configuration for [LogDuckUsage].
 *
 * `@JvmOverloads` so a Java caller can pass only the required arguments.
 */
public class LogDuckUsageOptions @JvmOverloads constructor(
    /**
     * A **client** key (`ld_...`) with "allow requests without an origin"
     * switched on in the dashboard — an app sends no `Origin` header.
     */
    public val key: String,

    /** The app identifier, e.g. the Android application id. Sent as `host`. */
    public val appId: String,

    /**
     * Keeps the install id and the installed flag across launches. `null`
     * keeps them in memory, so `app.install` is then sent on every launch.
     */
    public val storage: UsageStorage? = null,

    /** Your app's version name, e.g. `BuildConfig.VERSION_NAME`. */
    public val appVersion: String? = null,

    /** The OS version, e.g. `Build.VERSION.RELEASE`. */
    public val osVersion: String? = null,

    /**
     * Attached to every hit: at most ten lowercase keys with values of at most
     * 256 characters. Never an identifier.
     */
    public val dimensions: Map<String, String>? = null,

    /**
     * Where hits that could not be sent wait for the next launch (at most 500,
     * oldest dropped). `null` keeps them in memory only.
     */
    public val queueDir: File? = null,

    /** API base URL. Override only for a self-hosted deployment or in tests. */
    public val baseUrl: String = "https://api.logduck.com",

    /** How long hits wait to be batched before a flush. */
    public val flushInterval: Duration = Duration.ofSeconds(1),

    /** Retry a foreground flush once on 5xx and network errors. */
    public val retryEnabled: Boolean = true,

    /** How hits are sent. */
    public val transport: LogDuckTransport = HttpUrlConnectionTransport(),

    /** Where warnings go. */
    public val logger: LogDuckLogger = StderrLogger,

    /** Injected for tests. */
    public val clock: Clock = Clock.systemUTC(),
)

/**
 * The usage surface for a native app: screen views and app lifecycle, for the
 * dashboard's Site tab. Hits are never events — no push, no webhooks, no alerts.
 *
 * ```kotlin
 * val usage = LogDuckUsage(LogDuckUsageOptions(key = "ld_...", appId = "com.example.app"))
 * usage.open()
 * usage.screen("Checkout")
 * ```
 *
 * Anonymous until [consent]: nothing that identifies the device is stored, and
 * the session id lives in memory. Every method is safe from any thread and
 * returns at once — the work happens on one background daemon thread. Create
 * one instance per process.
 */
public class LogDuckUsage(private val options: LogDuckUsageOptions) {
    private val url = "${options.baseUrl.trimEnd('/')}/v1/usage"
    private val userAgent = "LogDuck-Kotlin/${LogDuckClient.SDK_VERSION}"
    private val json = Json
    private val storage: UsageStorage = options.storage ?: MemoryStorage()
    private val queueFile = options.queueDir?.let { File(it, "logduck-usage-queue.json") }

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "logduck-usage").apply { isDaemon = true }
        }
    private val closed = AtomicBoolean(false)

    // Everything below is touched only on the executor thread.
    private val queue = ArrayDeque<JsonObject>()
    private var timer: ScheduledFuture<*>? = null
    private var dimensions: Map<String, String>? = null
    private var sessionId = randomId()
    private var backgroundedAt: Instant? = null
    private var installId: String? = null
    private var userId: String? = null
    private var lastScreen: String? = null
    private var opened = false
    private var persisted = false
    private var warnedRefused = false

    init {
        if (options.key.isEmpty()) warn("no key configured - every request will be refused")
        if (options.appId.isEmpty()) warn("no appId configured - hits cannot be attributed")
        submit {
            dimensions = cleanDimensions(options.dimensions)
            installId = storage.get(INSTALL_ID_KEY)
            loadQueue()
            if (queue.isNotEmpty()) scheduleTimer()
        }
    }

    /**
     * Records a `screen.view`. [previous] defaults to the name passed to the
     * previous call.
     */
    public fun screen(name: String, previous: String? = null) {
        val at = options.clock.instant()
        submit {
            val screen = name.take(SCREEN_LIMIT)
            val previousScreen = (previous ?: lastScreen)?.take(SCREEN_LIMIT)
            lastScreen = screen
            enqueue("screen.view", at) {
                put("screen", screen)
                previousScreen?.let { put("previousscreen", it) }
            }
        }
    }

    /**
     * Records `app.open`, once per process; the first time ever it is preceded
     * by `app.install`. Call it from `Application.onCreate`.
     */
    public fun open() {
        val at = options.clock.instant()
        submit {
            if (opened) return@submit
            opened = true
            if (storage.get(INSTALLED_KEY) == null) {
                storage.put(INSTALLED_KEY, "1")
                enqueue("app.install", at)
            }
            enqueue("app.open", at)
        }
    }

    /**
     * Records `app.foreground`. After 30 minutes or more in the background a
     * new session starts.
     */
    public fun foreground() {
        val at = options.clock.instant()
        submit {
            enqueue("app.foreground", at)
            backgroundedAt = null
            if (queue.isNotEmpty()) scheduleTimer()
        }
    }

    /**
     * Records `app.background` and sends what is queued in a single request.
     * No timer runs until [foreground].
     */
    public fun background() {
        val at = options.clock.instant()
        submit {
            backgroundedAt = at
            enqueue("app.background", at)
            cancelTimer()
            sendBatches(background = true)
        }
    }

    /**
     * Records that the user consented to being recognised across launches.
     * `true` mints a random install id, stores it and attaches it to every
     * later hit; `false` deletes it. Nothing identifying is stored before this.
     */
    public fun consent(granted: Boolean) {
        submit {
            if (!granted) {
                installId = null
                storage.remove(INSTALL_ID_KEY)
            } else if (installId == null) {
                installId = randomId().also { storage.put(INSTALL_ID_KEY, it) }
            }
        }
    }

    /**
     * Links later hits to your own user id from a signed-in session, and marks
     * them logged in. Stores nothing on the device. An empty string clears it.
     */
    public fun identify(userId: String) {
        submit { this.userId = userId.trim().take(USER_ID_LIMIT).ifEmpty { null } }
    }

    /** Replaces the dimensions attached to every later hit. */
    public fun setDimensions(dimensions: Map<String, String>?) {
        submit { this.dimensions = cleanDimensions(dimensions) }
    }

    /** Sends whatever is queued now. */
    public fun flush() {
        submit {
            cancelTimer()
            sendBatches(background = false)
        }
    }

    /** Sends what is queued and stops the background thread. Later calls do nothing. */
    public fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            executor.execute {
                cancelTimer()
                sendBatches(background = false)
            }
        } catch (_: RejectedExecutionException) {
        }
        executor.shutdown()
    }

    /** Blocks until every task submitted so far has run. For tests. */
    internal fun awaitIdle() {
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }

    // -- the queue -----------------------------------------------------------

    private fun submit(task: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute {
                try {
                    task()
                } catch (error: Exception) {
                    options.logger.warn("usage: ${error.message}", error)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Closed concurrently.
        }
    }

    private fun enqueue(type: String, at: Instant, fields: JsonObjectBuilder.() -> Unit = {}) {
        // A hit after a long spell in the background, without foreground() in
        // between, still belongs to a new session.
        backgroundedAt?.let { since ->
            if (Duration.between(since, at) >= SESSION_TIMEOUT) {
                sessionId = randomId()
                backgroundedAt = at
            }
        }

        queue.addLast(
            buildJsonObject {
                put("id", randomId())
                put("type", type)
                put("time", DateTimeFormatter.ISO_INSTANT.format(at))
                put("platform", "android")
                put("host", options.appId)
                put("sessionid", sessionId)
                options.appVersion?.let { put("appversion", it.take(VERSION_LIMIT)) }
                options.osVersion?.let { put("osversion", it.take(VERSION_LIMIT)) }
                put("sdkversion", LogDuckClient.SDK_VERSION)
                put("samplerate", 1)
                dimensions?.let { map ->
                    put("dimensions", JsonObject(map.mapValues { JsonPrimitive(it.value) }))
                }
                installId?.let { put("installid", it) }
                userId?.let {
                    put("userid", it)
                    put("loggedin", true)
                }
                fields()
            },
        )
        while (queue.size > QUEUE_LIMIT) queue.removeFirst()

        // In the background only background() itself sends, once.
        if (backgroundedAt != null) return
        if (queue.size >= HITS_PER_REQUEST) {
            cancelTimer()
            sendBatches(background = false)
        } else {
            scheduleTimer()
        }
    }

    private fun scheduleTimer() {
        if (timer != null || closed.get()) return
        timer = try {
            executor.schedule(
                {
                    timer = null
                    sendBatches(background = false)
                },
                options.flushInterval.toMillis(),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun cancelTimer() {
        timer?.cancel(false)
        timer = null
    }

    /**
     * In the foreground, sends batch after batch until the queue is empty or a
     * send fails. In the background, sends one batch, without a retry: one
     * network call per [background].
     */
    private fun sendBatches(background: Boolean) {
        var failed = false
        while (queue.isNotEmpty()) {
            val batch = queue.take(HITS_PER_REQUEST)
            when (send(batch, retry = options.retryEnabled && !background)) {
                Outcome.Sent -> repeat(batch.size) { queue.removeFirst() }
                Outcome.Refused -> repeat(batch.size) { queue.removeFirst() }
                Outcome.Failed -> failed = true
            }
            if (failed || background) break
        }
        // Hits that are still here wait for the next flush; nothing reschedules
        // it, so an offline device does not wake up every second to fail again.
        if (failed || background || persisted) saveQueue()
    }

    private enum class Outcome { Sent, Refused, Failed }

    private fun send(batch: List<JsonObject>, retry: Boolean): Outcome {
        val request = TransportRequest(
            url = url,
            headers = mapOf("Content-Type" to "text/plain", "User-Agent" to userAgent),
            body = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("key", options.key)
                    put("hits", JsonArray(batch))
                },
            ),
        )
        val maxAttempts = if (retry) 2 else 1

        for (attempt in 1..maxAttempts) {
            val decision = try {
                val response = options.transport.perform(request)
                RetryPolicy.decide(
                    status = response.status,
                    retryAfterHeader = response.header("Retry-After"),
                    body = response.body,
                    attempt = attempt,
                    maxAttempts = maxAttempts,
                    // Never wait out a rate-limit window on this thread; the
                    // hits stay queued instead.
                    maxRetryDelay = Duration.ZERO,
                )
            } catch (error: Exception) {
                RetryPolicy.decideTransportFailure(error, attempt, maxAttempts)
            }

            when (decision) {
                RetryDecision.Succeed -> return Outcome.Sent
                is RetryDecision.Retry -> Thread.sleep(decision.delay.toMillis())
                is RetryDecision.Fail -> {
                    val status = decision.error.status
                    if (status == null || status == 429 || status >= 500) return Outcome.Failed
                    if (!warnedRefused) {
                        warnedRefused = true
                        warn("the server answered $status; these hits are not being recorded", decision.error)
                    }
                    return Outcome.Refused
                }
            }
        }
        return Outcome.Failed
    }

    // -- offline persistence -------------------------------------------------

    private fun loadQueue() {
        val file = queueFile ?: return
        if (!file.exists()) return
        try {
            json.parseToJsonElement(file.readText()).let { it as JsonArray }
                .takeLast(QUEUE_LIMIT)
                .forEach { queue.addLast(it.jsonObject) }
            persisted = true
        } catch (error: Exception) {
            warn("discarded an unreadable offline queue", error)
            file.delete()
        }
    }

    private fun saveQueue() {
        val file = queueFile ?: return
        try {
            if (queue.isEmpty()) {
                file.delete()
                persisted = false
            } else {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(JsonArray.serializer(), JsonArray(queue.toList())))
                persisted = true
            }
        } catch (error: IOException) {
            warn("could not save the offline queue", error)
        }
    }

    // -- helpers -------------------------------------------------------------

    private fun cleanDimensions(input: Map<String, String>?): Map<String, String>? {
        if (input == null) return null
        val out = LinkedHashMap<String, String>()
        for ((key, value) in input) {
            when {
                !DIMENSION_KEY.matches(key) ->
                    warn("dimension \"$key\" dropped: keys are lowercase identifiers of at most 64 characters")
                out.size >= DIMENSION_KEYS_MAX ->
                    warn("dimension \"$key\" dropped: at most $DIMENSION_KEYS_MAX dimensions")
                else -> out[key] = value.take(DIMENSION_VALUE_MAX)
            }
        }
        return out.ifEmpty { null }
    }

    private fun warn(message: String, error: Throwable? = null) {
        options.logger.warn("usage: $message", error)
    }

    private class MemoryStorage : UsageStorage {
        private val values = HashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    internal companion object {
        const val HITS_PER_REQUEST = 50
        const val QUEUE_LIMIT = 500
        const val SCREEN_LIMIT = 128
        const val USER_ID_LIMIT = 128
        const val VERSION_LIMIT = 64
        const val DIMENSION_KEYS_MAX = 10
        const val DIMENSION_VALUE_MAX = 256
        const val INSTALL_ID_KEY = "logduck.installid"
        const val INSTALLED_KEY = "logduck.installed"
        val SESSION_TIMEOUT: Duration = Duration.ofMinutes(30)
        private val DIMENSION_KEY = Regex("^[a-z][a-z0-9_]{0,63}$")

        fun randomId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
