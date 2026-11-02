/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.models.NewsItem

@Singleton
class NewsRepository @Inject constructor() {
    private val client =
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(15, TimeUnit.SECONDS)
                    writeTimeout(15, TimeUnit.SECONDS)
                    retryOnConnectionFailure(false)
                }
            }
        }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Volatile private var metadataCache: List<NewsItem>? = null

    suspend fun fetchNews(): List<NewsItem> {
        val baseUrl = BuildConfig.NATUNE_NEWS_URL
        if (baseUrl.isBlank()) return emptyList()
        val response =
            client.get("$baseUrl/index.json") {
                headers {
                    append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                    append(HttpHeaders.Pragma, "no-cache")
                    append(HttpHeaders.Expires, "0")
                }
            }
        val text = response.bodyAsText()
        val items = json.decodeFromString<List<NewsItem>>(text)
        metadataCache = items
        return items
    }

    suspend fun fetchNewsContent(id: String): String {
        val baseUrl = BuildConfig.NATUNE_NEWS_URL
        if (baseUrl.isBlank() || !id.matches(SAFE_ID)) return ""
        val response =
            client.get("$baseUrl/content/$id") {
                headers {
                    append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                    append(HttpHeaders.Pragma, "no-cache")
                    append(HttpHeaders.Expires, "0")
                }
            }
        return response.bodyAsText()
    }

    fun getCachedItem(id: String): NewsItem? = metadataCache?.find { it.id == id }

    private companion object {
        /** The id doubles as the content filename; keep it inside content/ with no traversal. */
        val SAFE_ID = Regex("[A-Za-z0-9._-]+")
    }
}
