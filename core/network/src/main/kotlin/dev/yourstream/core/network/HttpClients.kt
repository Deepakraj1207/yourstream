package dev.yourstream.core.network

import dev.yourstream.core.common.AppError
import dev.yourstream.core.common.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import java.io.IOException

/** Lenient JSON: providers add fields at any time. */
val ProviderJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

object HttpClients {
    /**
     * One OkHttp client (connection pool, dispatcher threads, DNS cache) shared by every provider.
     * Before, each provider built its own engine: ~8 pools and thread sets for one app.
     */
    private val sharedOkHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(5, 2, java.util.concurrent.TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            // Hosts the network's DNS blocks (TMDB on some ISPs) are resolved over HTTPS.
            .dns(SecureDns.dns)
            .addInterceptor(SecureDns.Retry())
            .build()
    }

    /** A client per provider (its own plugins/headers) over the shared engine. [engine] is injectable for tests (Ktor MockEngine). */
    fun create(engine: HttpClientEngine? = null, userAgent: String = "YourStream/0.1"): HttpClient {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            expectSuccess = false
            install(ContentNegotiation) { json(ProviderJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            defaultRequest { headers.append("User-Agent", userAgent) }
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(OkHttp) {
            engine { preconfigured = sharedOkHttp }
            configure()
        }
    }
}

/**
 * GET + decode with every failure mapped to an [AppError]:
 * 429 → RateLimited (Retry-After honoured), 401/403 → NotConfigured (bad key), 404 → NotFound,
 * other 4xx/5xx → Remote, IO problems → Network, timeouts → Timeout, bad JSON → Unknown.
 */
suspend fun <T> HttpClient.getJson(
    url: String,
    deserializer: DeserializationStrategy<T>,
    block: HttpRequestBuilder.() -> Unit = {},
): AppResult<T> = try {
    val response: HttpResponse = get(url, block)
    when {
        response.status == HttpStatusCode.TooManyRequests -> AppResult.Err(AppError.RateLimited(retryAfterSeconds(response)))
        response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
            AppResult.Err(AppError.NotConfigured("Rejected by ${response.call.request.url.host} (${response.status.value}): check the API key"))
        response.status == HttpStatusCode.NotFound -> AppResult.Err(AppError.NotFound("Not found: ${response.call.request.url.encodedPath}"))
        response.status.value !in 200..299 -> AppResult.Err(AppError.Remote(response.status.value, response.status.description))
        else -> AppResult.Ok(ProviderJson.decodeFromString(deserializer, response.bodyAsText()))
    }
} catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
    AppResult.Err(AppError.Timeout(url.substringBefore('?')))
} catch (e: kotlinx.serialization.SerializationException) {
    AppResult.Err(AppError.Unknown("Unexpected response from ${url.substringBefore('?')}: ${e.message}", e))
} catch (e: IOException) {
    AppResult.Err(AppError.Network(e.message ?: "Network error"))
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // Anything else (malformed URL, unresolvable address, engine bugs): an error, not a crash.
    AppResult.Err(AppError.Unknown("Request to ${url.substringBefore('?')} failed: ${e::class.simpleName}: ${e.message}", e))
}

private fun retryAfterSeconds(response: HttpResponse): Int =
    response.headers["Retry-After"]?.trim()?.toIntOrNull()?.coerceIn(1, 3_600) ?: 10
