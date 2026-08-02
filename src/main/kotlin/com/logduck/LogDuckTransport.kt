package com.logduck

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

/** One HTTP request, as the client describes it to a transport. */
public data class TransportRequest(
    public val url: String,
    public val headers: Map<String, String>,
    public val body: String,
)

/** One HTTP response, as a transport describes it back. */
public data class TransportResponse(
    public val status: Int,
    public val body: String,
    public val headers: Map<String, String>,
) {
    /** Case-insensitive, because header casing is not guaranteed. */
    public fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** How the client performs a request. Injectable so tests need no network. */
public fun interface LogDuckTransport {
    /** Performs the request, or throws [IOException] if it never completed. */
    public fun perform(request: TransportRequest): TransportResponse
}

/**
 * The default transport.
 *
 * Built on [HttpURLConnection] rather than `java.net.http.HttpClient` so the
 * same artifact works on Android, where the newer client does not exist at any
 * API level. It also keeps the dependency list to coroutines and serialization.
 */
public class HttpUrlConnectionTransport(
    private val timeout: Duration = Duration.ofSeconds(30),
) : LogDuckTransport {

    override fun perform(request: TransportRequest): TransportResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = timeout.toMillis().toInt()
            connection.readTimeout = timeout.toMillis().toInt()
            request.headers.forEach(connection::setRequestProperty)

            connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode

            // HttpURLConnection routes 4xx and 5xx bodies to the error stream
            // and throws from inputStream — reading the wrong one loses the
            // server's explanation of what was wrong with the request.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.firstOrNull() ?: "" }

            TransportResponse(status, body, headers)
        } finally {
            connection.disconnect()
        }
    }
}
