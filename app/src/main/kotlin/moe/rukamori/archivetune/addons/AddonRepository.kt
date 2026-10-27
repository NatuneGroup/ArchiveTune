/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.addons

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.constants.AddonInstallationsKey
import moe.rukamori.archivetune.utils.dataStore
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AddonRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val json = Json { ignoreUnknownKeys = true }
        private val httpClient =
            OkHttpClient.Builder()
                .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        fun observeInstallations(): Flow<List<AddonInstallation>> =
            context.dataStore.data.map { preferences ->
                runCatching {
                    json.decodeFromString<List<AddonInstallation>>(preferences[AddonInstallationsKey].orEmpty())
                }.getOrDefault(emptyList())
            }

        suspend fun addAddon(manifestUrl: String): AddonInstallation = withContext(Dispatchers.IO) {
            val normalizedUrl = normalizeBaseUrl(manifestUrl)
            val manifest = fetchManifestOrProbe(normalizedUrl)
            val installation =
                AddonInstallation(
                    id = addonId(normalizedUrl),
                    manifestUrl = normalizedUrl,
                    name = manifest.name.ifBlank { requireHttpsUrl(normalizedUrl).host }.take(MAX_NAME_LENGTH),
                )
            context.dataStore.edit { preferences ->
                val current = readInstallations(preferences[AddonInstallationsKey])
                require(current.none { it.id == installation.id }) { "This add-on is already installed." }
                require(current.size < MAX_INSTALLATIONS) { "The add-on limit has been reached." }
                preferences[AddonInstallationsKey] = json.encodeToString(current + installation)
            }
            installation
        }

        suspend fun setEnabled(
            addonId: String,
            enabled: Boolean,
        ) {
            context.dataStore.edit { preferences ->
                val current = readInstallations(preferences[AddonInstallationsKey])
                preferences[AddonInstallationsKey] =
                    json.encodeToString(current.map { installation ->
                        if (installation.id == addonId) installation.copy(enabled = enabled) else installation
                    })
            }
        }

        suspend fun removeAddon(addonId: String) {
            context.dataStore.edit { preferences ->
                preferences[AddonInstallationsKey] =
                    json.encodeToString(readInstallations(preferences[AddonInstallationsKey]).filterNot { it.id == addonId })
            }
        }

        suspend fun search(query: String): List<AddonTrack> = withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val installations = observeInstallationsOnce().filter { it.enabled }
            coroutineScope {
                installations.map { installation ->
                    async {
                        try {
                            val manifest = fetchManifestOrProbe(installation.manifestUrl)
                            val endpoint = resolveEndpoint(installation.manifestUrl, manifest.searchEndpoint, "search")
                            val url = endpoint.newBuilder()
                                .apply {
                                    settingsFor(manifest).forEach { (key, value) -> addQueryParameter(key, value) }
                                }
                                .addQueryParameter("q", query)
                                .build()
                            getJson<AddonSearchResponse>(url).allTracks()
                                .filter { it.id.isNotBlank() && it.title.isNotBlank() }
                                .take(MAX_RESULTS_PER_ADDON)
                                .map { it.copy(addonId = installation.id, addonName = installation.name) }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        }

        suspend fun resolveStream(
            addonId: String,
            track: AddonTrack,
        ): AddonStreamResponse = withContext(Dispatchers.IO) {
            val installation = observeInstallationsOnce().firstOrNull { it.id == addonId && it.enabled }
                ?: throw IOException("Add-on is unavailable.")
            val manifest = fetchManifestOrProbe(installation.manifestUrl)
            val endpointTemplate = manifest.streamEndpoint?.takeIf(String::isNotBlank) ?: "stream/{id}"
            val response =
                if (endpointTemplate.contains("{id}")) {
                    val encodedTrackId = java.net.URLEncoder.encode(track.id, Charsets.UTF_8.name()).replace("+", "%20")
                    val endpoint = resolveEndpoint(
                        installation.manifestUrl,
                        endpointTemplate.replace("{id}", encodedTrackId),
                        "stream/$encodedTrackId",
                    ).newBuilder()
                        .apply {
                            settingsFor(manifest).forEach { (key, value) -> addQueryParameter(key, value) }
                        }
                        .build()
                    try {
                        getJson<AddonStreamResponse>(endpoint)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        null
                    }
                } else {
                    null
                }
            if (response?.isEncrypted() == true) throw IOException("The add-on returned an encrypted stream.")
            val usableResponse = response?.takeIf { it.url.isNotBlank() }
            if (usableResponse != null) {
                requireHttpsUrl(usableResponse.url)
                usableResponse.withTrackMetadata(track)
            } else {
                val fallbackUrl = track.streamURL?.takeIf(String::isNotBlank)
                    ?: throw IOException(response?.error ?: "Add-on did not return a playable stream URL.")
                requireHttpsUrl(fallbackUrl)
                AddonStreamResponse(
                    url = fallbackUrl,
                    format = track.format,
                    audioQuality = track.audioQuality,
                )
            }
        }

        private suspend fun observeInstallationsOnce(): List<AddonInstallation> =
            observeInstallations().first()

        private fun fetchManifest(baseUrl: String): AddonManifest {
            val request = Request.Builder().url(manifestUrl(normalizeBaseUrl(baseUrl))).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Add-on request failed (${response.code}).")
                val body = response.body ?: throw IOException("Add-on returned an empty response.")
                val bytes = body.byteStream().use { input -> input.readBounded(MAX_RESPONSE_BYTES) }
                if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("Add-on response is too large.")
                if (!response.request.url.isHttps) throw IOException("Add-on requests must not redirect to HTTP.")
                return parseAddonManifest(bytes.toString(Charsets.UTF_8))
            }
        }

        private fun fetchManifestOrProbe(baseUrl: String): AddonManifest =
            try {
                fetchManifest(baseUrl)
            } catch (manifestError: Throwable) {
                if (manifestError is CancellationException) throw manifestError
                try {
                    val searchUrl = resolveEndpoint(baseUrl, null, "search")
                        .newBuilder().addQueryParameter("q", PROBE_QUERY).build()
                    getJson<AddonSearchResponse>(searchUrl)
                    AddonManifest(id = requireHttpsUrl(baseUrl).host, name = requireHttpsUrl(baseUrl).host)
                } catch (probeError: Throwable) {
                    if (probeError is CancellationException) throw probeError
                    throw manifestError
                }
            }

        private inline fun <reified T> getJson(url: HttpUrl): T {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Add-on request failed (${response.code}).")
                val body = response.body ?: throw IOException("Add-on returned an empty response.")
                val bytes = body.byteStream().use { it.readBounded(MAX_RESPONSE_BYTES) }
                if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("Add-on response is too large.")
                if (!response.request.url.isHttps) throw IOException("Add-on requests must not redirect to HTTP.")
                return json.decodeFromString(bytes.toString(Charsets.UTF_8))
            }
        }

        private fun resolveEndpoint(
            baseUrl: String,
            endpoint: String?,
            defaultPath: String,
        ): HttpUrl {
            val base = requireHttpsUrl(normalizeBaseUrl(baseUrl))
            val url =
                when {
                    endpoint.isNullOrBlank() -> base.newBuilder().addPathSegments(defaultPath).build()
                    endpoint.startsWith("https://", ignoreCase = true) -> requireHttpsUrl(endpoint)
                    endpoint.startsWith('/') -> base.resolve(endpoint)
                    else -> base.newBuilder().addPathSegments(endpoint).build()
                } ?: throw IllegalArgumentException("Invalid add-on endpoint.")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
                "Add-on endpoints must use HTTPS without credentials."
            }
            return url
        }

        private fun manifestUrl(baseUrl: String): HttpUrl =
            requireHttpsUrl(normalizeBaseUrl(baseUrl)).newBuilder().addPathSegment("manifest.json").build()

        private fun settingsFor(manifest: AddonManifest): Map<String, String> = buildMap {
            manifest.settings.forEach { setting ->
                val key = setting.key.takeIf(String::isNotBlank) ?: return@forEach
                setting.defaultValue.asQueryValue()?.let { put(key, it) }
            }
            val qualityOptions =
                manifest.settings.firstOrNull { it.key == QUALITY_SETTING_KEY }
                    ?.options
                    ?.mapNotNull { it.value.asQueryValue() }
                    .orEmpty()
            put("quality", qualityOptions.matchTier(LOSSLESS_TIER))
        }

        private fun List<String>.matchTier(tier: String): String {
            if (isEmpty()) return tier
            firstOrNull { it.equals(tier, ignoreCase = true) }?.let { return it }
            val words =
                when (tier.uppercase()) {
                    LOSSLESS_TIER -> LOSSLESS_QUALITY_WORDS
                    LOW_TIER -> LOW_QUALITY_WORDS
                    else -> HIGH_QUALITY_WORDS
                }
            return firstOrNull { option -> words.any { word -> word in option.lowercase() } } ?: first()
        }

        private fun normalizeBaseUrl(value: String): String {
            val url = requireHttpsUrl(value)
            val pathSegments = url.pathSegments
            return if (pathSegments.lastOrNull()?.endsWith(".json", ignoreCase = true) == true) {
                url.newBuilder().removePathSegment(pathSegments.lastIndex).build().toString().trimEnd('/')
            } else {
                url.toString().trimEnd('/')
            }
        }

        private fun AddonSearchResponse.allTracks(): List<AddonTrack> = tracks.ifEmpty { results }

        private fun AddonStreamResponse.withTrackMetadata(track: AddonTrack): AddonStreamResponse =
            copy(
                format = format ?: track.format,
                audioQuality = audioQuality ?: track.audioQuality,
            )

        private fun kotlinx.serialization.json.JsonElement?.asQueryValue(): String? =
            (this as? JsonPrimitive)?.contentOrNull

        private fun readInstallations(value: String?): List<AddonInstallation> =
            runCatching { json.decodeFromString<List<AddonInstallation>>(value.orEmpty()) }.getOrDefault(emptyList())

        private fun addonId(manifestUrl: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(manifestUrl.toByteArray())
                .take(12)
                .joinToString("") { byte -> "%02x".format(byte) }

        private fun requireHttpsUrl(value: String): HttpUrl {
            val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS URL.")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
                "Only HTTPS URLs without credentials are supported."
            }
            return url
        }

        private fun InputStream.readBounded(maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var totalBytes = 0
            while (totalBytes <= maxBytes) {
                val bytesRead = read(buffer, 0, minOf(buffer.size, maxBytes + 1 - totalBytes))
                if (bytesRead < 0) break
                output.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }
            return output.toByteArray()
        }

        private companion object {
            const val QUALITY_SETTING_KEY = "quality"
            const val LOSSLESS_TIER = "LOSSLESS"
            const val LOW_TIER = "LOW"
            val LOSSLESS_QUALITY_WORDS = listOf("lossless", "flac", "hifi", "hi-res", "hires", "max", "best")
            val HIGH_QUALITY_WORDS = listOf("high", "320", "normal", "standard")
            val LOW_QUALITY_WORDS = listOf("low", "96", "128", "min")
            const val MAX_INSTALLATIONS = 20
            const val MAX_NAME_LENGTH = 80
            const val MAX_RESULTS_PER_ADDON = 50
            const val MAX_RESPONSE_BYTES = 1_000_000
            const val NETWORK_TIMEOUT_SECONDS = 15L
            const val PROBE_QUERY = "ArchiveTune"
        }
    }

internal fun parseAddonManifest(body: String): AddonManifest =
    Json { ignoreUnknownKeys = true }
        .decodeFromString<AddonManifest>(body)
        .also(::validateAddonManifest)

internal fun validateAddonManifest(manifest: AddonManifest) {
    require(manifest.id.isNotBlank()) { "The add-on manifest is missing an id." }
    require(manifest.resources.isEmpty() || manifest.resources.any { it.equals("search", ignoreCase = true) }) {
        "This add-on does not declare search support."
    }
    require(manifest.streamEndpoint.isNullOrBlank() || manifest.streamEndpoint.contains("{id}")) {
        "streamEndpoint must contain {id}."
    }
}