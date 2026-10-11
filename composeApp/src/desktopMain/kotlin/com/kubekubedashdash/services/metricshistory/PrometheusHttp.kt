package com.kubekubedashdash.services.metricshistory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** A thin form-POST client over java.net.http, which the packaged runtime carries. */
class PrometheusHttp internal constructor(private val client: HttpClient) {
    data class Response(val status: Int, val body: String)

    /** POSTs [form] url-encoded with a bearer token. Throws IOException (HttpTimeoutException included) on transport failure. */
    suspend fun postForm(url: String, form: List<Pair<String, String>>, bearer: String): Response {
        val body = form.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = runInterruptible(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        return Response(response.statusCode(), response.body().orEmpty())
    }

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        // Never follows redirects: one would carry the bearer token to whatever host it names.
        val shared: PrometheusHttp by lazy {
            PrometheusHttp(
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),
            )
        }
    }
}
