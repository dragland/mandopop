package com.mandopop.traverse

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thrown for any Traverse sync failure. Message is surfaced verbatim in the UI and notification —
 * failures should be loud, not silently swallowed.
 */
class TraverseException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : IOException(message, cause)

internal object Http {
    private const val TIMEOUT_MS = 20_000

    /** A card batch is ~1 MB; 20 s is comfortable on wifi and not on a throttled cellular link. */
    const val LONG_TIMEOUT_MS = 60_000

    fun get(url: String, bearerToken: String): String {
        return request(url, "GET", bearerToken = bearerToken)
    }

    fun postJson(
        url: String,
        body: String,
        bearerToken: String? = null,
        readTimeoutMs: Int = TIMEOUT_MS,
    ): String {
        return request(
            url,
            "POST",
            body = body,
            contentType = "application/json; charset=utf-8",
            bearerToken = bearerToken,
            readTimeoutMs = readTimeoutMs,
        )
    }

    fun postForm(url: String, body: String): String {
        return request(url, "POST", body = body, contentType = "application/x-www-form-urlencoded")
    }

    private fun request(
        url: String,
        method: String,
        body: String? = null,
        contentType: String? = null,
        bearerToken: String? = null,
        readTimeoutMs: Int = TIMEOUT_MS,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            contentType?.let { connection.setRequestProperty("Content-Type", it) }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw TraverseException(
                    "HTTP $status from ${redact(url)}: ${detail.take(400)}",
                    statusCode = status,
                )
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: TraverseException) {
            throw error
        } catch (error: Exception) {
            throw TraverseException("${method} ${redact(url)} failed: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Strip the query string (carries the API key) and the uid path segment, so neither ends up in
     * an error message that gets rendered into a notification.
     */
    private fun redact(url: String): String =
        url.substringBefore("?").replace(UID_SEGMENT, "/userNames/{uid}")

    private val UID_SEGMENT = Regex("/userNames/[^/]+")
}
