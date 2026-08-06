package com.beyondlevi.nexus.news

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Feed fetching. A phone-side plugin with INTERNET uses its own stack — the bus
 * `http_proxy` capability exists for glasses-side fetches to a narrow allowlist
 * and is deliberately not requested here.
 *
 * Written against HttpURLConnection to keep the plugin APK small, and bounded on
 * every axis that a remote server controls: timeouts, redirect count, body size.
 */
class NewsHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxBodyBytes: Int = 4 * 1024 * 1024,
    private val maxRedirects: Int = 4,
) {

    class HttpFailure(message: String) : Exception(message)

    /**
     * @return the raw document bytes; the XML declaration decides the charset, so
     * decoding is left to the parser.
     */
    fun fetch(url: String): ByteArray {
        var current = url
        var redirects = 0
        while (true) {
            val connection = open(current)
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw HttpFailure("Redirect without a location")
                    if (redirects++ >= maxRedirects) throw HttpFailure("Too many redirects")
                    // Cross-protocol redirects are not followed by HttpURLConnection
                    // itself, and http -> https is the common case.
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (status !in 200..299) throw HttpFailure("HTTP $status")
                val stream = if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                    GZIPInputStream(connection.inputStream)
                } else {
                    connection.inputStream
                }
                return stream.use { readBounded(it) }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val parsed = URL(url)
        if (parsed.protocol != "http" && parsed.protocol != "https") {
            throw HttpFailure("Only http and https feeds are supported")
        }
        val connection = parsed.openConnection() as HttpURLConnection
        // Redirects are followed by hand so http -> https hops work and the hop
        // count stays bounded.
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", ACCEPT)
        connection.setRequestProperty("Accept-Encoding", "gzip")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val output = ByteArrayOutputStream(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size() + read > maxBodyBytes) throw HttpFailure("Feed is too large")
            output.write(buffer, 0, read)
        }
        if (output.size() == 0) throw HttpFailure("Empty response")
        return output.toByteArray()
    }

    private companion object {
        const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml, text/xml, */*"
        const val USER_AGENT = "NexusNews/1.0 (+https://github.com/beyondlevi/news-nexus)"
    }
}
