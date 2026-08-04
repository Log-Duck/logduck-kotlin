# LogDuck Kotlin SDK

Official Kotlin and Java SDK for [LogDuck](https://logduck.com) — send events
from your app and get notified about the ones that matter.

Java 11 bytecode, so it works from server-side Kotlin and Java as well as from
an Android app.

```kotlin
dependencies {
    implementation("com.logduck:logduck:2.0.0")
}
```

## Quick start

Two settings are required: your API key, and a `source` naming the app sending
the event.

```kotlin
import com.logduck.LogDuckClient
import com.logduck.LogDuckEvent
import com.logduck.LogDuckOptions

val logduck = LogDuckClient(
    LogDuckOptions(apiKey = System.getenv("LOGDUCK_API_KEY"), source = "checkout-api")
)

logduck.send(LogDuckEvent(type = "order.placed"))
```

That is the whole minimal setup. Create an API key in your project settings —
see [Authentication](https://logduck.com/docs#authentication).

`send` is a `suspend` function. From Java, or anywhere without a coroutine
scope, use `sendBlocking`:

```java
import com.logduck.LogDuckClient;
import com.logduck.LogDuckEvent;
import com.logduck.LogDuckOptions;

LogDuckClient logduck = new LogDuckClient(
    new LogDuckOptions(System.getenv("LOGDUCK_API_KEY"), "checkout-api")
);

logduck.sendBlocking(new LogDuckEvent("order.placed"));
```

## Every option

Only `apiKey` and `source` are required. The values below are the defaults you
get by omitting each one.

```kotlin
import com.logduck.HttpUrlConnectionTransport
import com.logduck.LogDuckClient
import com.logduck.LogDuckEvent
import com.logduck.LogDuckOptions
import com.logduck.StderrLogger
import java.time.Duration
import java.time.Instant

val logduck = LogDuckClient(
    options = LogDuckOptions(
        apiKey = System.getenv("LOGDUCK_API_KEY"),  // required — your key, starts with `ld_`
        source = "checkout-api",                    // required — which app sent this, max 200 chars
        throwOnError = false,                       // optional, default false — true throws instead of returning null
        retryEnabled = true,                        // optional, default true  — retry once on 5xx, network errors and 429
        maxRetryDelay = Duration.ofSeconds(10),     // optional, default 10s   — never block your caller longer than this
        timeout = Duration.ofSeconds(30),           // optional, default 30s   — per-request timeout
        baseUrl = "https://api.logduck.com",        // optional — override only for a self-hosted deployment
    ),
    transport = HttpUrlConnectionTransport(),       // optional — swap the HTTP layer, e.g. for OkHttp
    logger = StderrLogger,                          // optional, default StderrLogger — see Logging below
)

logduck.send(
    LogDuckEvent(
        type = "order.placed",                      // required — max 100 chars, naming rule below
        subject = "order_9127",                     // optional — what the event is about, max 500 chars
        sessionId = "sess_8f21c",                   // optional — groups related events, max 256 chars
        time = Instant.now(),                       // optional — defaults to when the server received it
        data = mapOf("total" to 4999, "currency" to "NOK"), // optional — strings, numbers, booleans,
                                                    //            nulls, lists and nested maps
        emoji = "🛒",                                // optional — shown next to the event, max 10 chars
    )
)
```

One client is enough for a process — it holds no per-request state and is safe
to share between threads.

**`message` is what a push notification shows.** Without it the body falls back
to listing the first few `data` keys, which reads like `total: 4999, currency:
NOK` rather than "Order placed on the Pro plan".

**Wire names.** The event is a CloudEvents 1.0 document, and a CloudEvents
extension attribute name must be lowercase alphanumeric — so `sessionId` goes
out as `sessionid`. The SDK maps it for you; you only need this when comparing
against the raw HTTP API.

**Naming `type`:** the server lowercases it and requires `^[a-z][a-z0-9_.]*$` —
letters, digits, `_` and `.`, starting with a letter. So `order.placed` and
`user.signup_completed` are fine; `order-placed` is rejected with a 400. Dots
are the conventional separator.

## Errors

By default `send` **never throws**. It returns `null` and logs a warning — a
logging SDK that throws can take down the code it was only meant to observe.

```kotlin
val result = logduck.send(LogDuckEvent(type = "order.placed"))

if (result == null) {
    // The event did not reach LogDuck. A warning has already been logged.
}
```

Set `throwOnError = true` to get a `LogDuckException` instead. Note that `catch`
only sees anything when that option is on — with the default, the block below
would never run:

```kotlin
import com.logduck.LogDuckClient
import com.logduck.LogDuckEvent
import com.logduck.LogDuckException
import com.logduck.LogDuckOptions
import java.time.Instant

val logduck = LogDuckClient(
    LogDuckOptions(
        apiKey = System.getenv("LOGDUCK_API_KEY"),
        source = "checkout-api",
        throwOnError = true,      // required, or the catch below never fires
    )
)

// Your own queue — the SDK does not provide one.
data class QueuedEvent(val event: LogDuckEvent, val notBefore: Instant)
val retryQueue = mutableListOf<QueuedEvent>()

val event = LogDuckEvent(type = "order.placed", subject = "order_9127")

try {
    logduck.send(event)
} catch (error: LogDuckException) {
    error.retryAfter?.let { wait ->
        // Rate limited for longer than the client will wait — queue it rather than lose it.
        retryQueue += QueuedEvent(event, Instant.now().plus(wait))
    }
}
```

`LogDuckException` carries `status`, `body` and `retryAfter`.

## Retries and rate limits

### The SDK sets `Idempotency-Key` for you

Every call generates an `Idempotency-Key` header, and reuses the **same** key on
the retry. The server deduplicates on it, so an event that timed out and got
retried is recorded once rather than twice.

You never set this header yourself. It is the main thing the SDK does for you
that raw HTTP does not — the endpoint *requires* the header (16–36 characters of
`[A-Za-z0-9_-]`) and rejects requests without one.

### What gets retried

| Response | Behaviour |
|---|---|
| 5xx, network failure, timeout | retried once after 1 second |
| 429 | retried once after the server's `Retry-After`, but only if that fits inside `maxRetryDelay` |
| Any other 4xx | never retried — it will fail identically the second time |

The server rate-limits each API key in fixed one-minute windows, so `Retry-After`
can be as much as 60 seconds. Blocking your caller that long is rarely
acceptable, so beyond `maxRetryDelay` the client gives up immediately and puts
the remaining wait on `error.retryAfter`, letting you queue the event instead of
losing it.

## Using the API without the SDK

The SDK is a convenience, not a requirement — `POST /v1/events` is a plain JSON
endpoint over HTTPS, usable from any language or from `curl`:

```bash
curl -X POST https://api.logduck.com/v1/events \
  -H "X-API-Key: ld_your_key_here" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"type":"order.placed","source":"checkout-api","subject":"order_9127"}'
```

Full request and response reference:
**[logduck.com/docs#api-reference](https://logduck.com/docs#api-reference)**.

Going direct means you take on what the SDK handles: generating a valid
`Idempotency-Key` per event and reusing it across retries, honouring
`Retry-After` on a 429, and deciding which failures are worth retrying.

## Dependencies

kotlinx-coroutines and kotlinx-serialization, both `api` dependencies since they
appear in the public surface.

HTTP goes through `HttpURLConnection` rather than `java.net.http.HttpClient`,
because the latter does not exist on Android at any API level and the same
artifact is meant to work there. To use OkHttp or anything else, implement
`LogDuckTransport` — which is also the injection point for tests, so nothing has
to touch the network:

```kotlin
import com.logduck.LogDuckClient
import com.logduck.LogDuckOptions
import com.logduck.LogDuckTransport
import com.logduck.TransportResponse

val stub = LogDuckTransport { _ ->
    TransportResponse(
        status = 200,
        body = """{"success":true,"eventId":"evt_1","time":"2026-01-01T00:00:00Z"}""",
        headers = emptyMap(),
    )
}

val logduck = LogDuckClient(
    LogDuckOptions(apiKey = "ld_test", source = "tests"),
    transport = stub,
)
```

## Logging

Warnings go to stderr by default. Pass any `LogDuckLogger` to redirect them:

```kotlin
import com.logduck.LogDuckClient
import com.logduck.LogDuckOptions
import org.slf4j.LoggerFactory

val log = LoggerFactory.getLogger("logduck")

val logduck = LogDuckClient(
    LogDuckOptions(apiKey = System.getenv("LOGDUCK_API_KEY"), source = "checkout-api"),
    logger = { message, error -> log.warn(message, error) },
)
```

## Development

```bash
./gradlew build
./gradlew test
```

The public API is under `explicitApi()` — every public declaration needs an
explicit visibility and return type, so adding to the compatibility surface is
deliberate rather than accidental.

Releases are published by CI to Maven Central. Bump `version` in
`build.gradle.kts` **and** `SDK_VERSION` in `LogDuckClient.kt`, commit, then
`git tag v1.1.0 && git push origin v1.1.0`. The workflow refuses the release if
any of the three disagree, or if the version already exists on Central.

## License

MIT
