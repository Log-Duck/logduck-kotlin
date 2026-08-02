# LogDuck Kotlin SDK

Official Kotlin and Java SDK for [LogDuck](https://logduck.com) — send events
from your app and get notified about the ones that matter.

Java 11 bytecode, so it works from server-side Kotlin and Java as well as from
an Android app.

## Installation

```kotlin
dependencies {
    implementation("com.logduck:logduck:1.0.0")
}
```

## Usage

```kotlin
import com.logduck.*

val logduck = LogDuckClient(
    LogDuckOptions(apiKey = System.getenv("LOGDUCK_API_KEY"), source = "checkout-api")
)

logduck.send(
    LogDuckEvent(
        type = "order.placed",
        subject = "order_9127",
        data = mapOf("total" to 4999, "currency" to "NOK"),
        emoji = "🛒",
    )
)
```

`send` is a `suspend` function. From Java, or anywhere without a coroutine
scope, use `sendBlocking`:

```java
LogDuckClient logduck = new LogDuckClient(
    new LogDuckOptions("ld_...", "checkout-api")
);
logduck.sendBlocking(new LogDuckEvent("order.placed"));
```

One client is enough for a process — it holds no per-request state and is safe
to share between threads.

## Options

| Option | Default | What it does |
|---|---|---|
| `apiKey` | — | Required. Must start with `ld_`. |
| `source` | — | Required. Identifies the app sending events (max 200 chars). Attached to every event. |
| `throwOnError` | `false` | When false, failures are logged and `send` returns `null`. |
| `retryEnabled` | `true` | Retry once on 5xx, network errors and rate limits. |
| `maxRetryDelay` | 10s | Longest wait before retrying a rate-limited request. |
| `timeout` | 30s | Request timeout. |
| `baseUrl` | `https://api.logduck.com` | Override for a self-hosted deployment. |

## Event fields

| Field | Required | Limit |
|---|---|---|
| `type` | yes | 100 chars. Lowercased by the server, which requires `^[a-z][a-z0-9_.]*$` — letters, digits, `_` and `.`, starting with a letter. A hyphen is rejected. |
| `subject` | no | 500 chars. The resource the event is about. |
| `sessionId` | no | 256 chars. Groups related events. |
| `time` | no | `Instant`. Defaults to server time. |
| `data` | no | `Map<String, Any?>` — strings, numbers, booleans, nulls, lists and nested maps. |
| `emoji` | no | 10 chars. |

`source` is not an event field — it comes from the options.

## Errors

`send` returns `null` on failure and logs a warning. A logging SDK that throws
can take down the code it was only meant to observe, so that is the default;
set `throwOnError = true` to get a `LogDuckException` instead.

```kotlin
try {
    logduck.send(event)
} catch (error: LogDuckException) {
    error.retryAfter?.let { wait ->
        // Rate limited. Queue it rather than lose it.
        queue += event to Instant.now().plus(wait)
    }
}
```

### Retries and rate limits

Every request carries an `Idempotency-Key`, unchanged across a retry, so the
server deduplicates rather than double-posting.

- **5xx and network failures** — retried once after 1 second.
- **429** — retried once, waiting for the period the server gives in
  `Retry-After`, but never longer than `maxRetryDelay`. The server limits each
  API key in fixed one-minute windows, so `Retry-After` can be as much as 60
  seconds; blocking a caller that long is rarely acceptable. When the wait would
  exceed the cap the client gives up immediately and the exception carries
  `retryAfter`, so the event can be queued instead of lost.
- **Other 4xx** — never retried. They will fail identically the second time.

## Dependencies

kotlinx-coroutines and kotlinx-serialization, both `api` dependencies since
they appear in the public surface.

HTTP goes through `HttpURLConnection` rather than `java.net.http.HttpClient`,
because the latter does not exist on Android at any API level and the same
artifact is meant to work there. To use OkHttp or anything else instead,
implement `LogDuckTransport`:

```kotlin
LogDuckClient(options, transport = { request -> /* ... */ })
```

That is also the injection point for tests, so nothing has to touch the
network.

## Logging

Warnings go to stderr by default. Supply any `LogDuckLogger` to redirect them:

```kotlin
LogDuckClient(options, logger = { message, error -> log.warn(message, error) })
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
