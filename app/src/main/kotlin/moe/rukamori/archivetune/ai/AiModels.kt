/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ai

import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AiCustomModelKey
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.AiSelectedModelKey
import moe.rukamori.archivetune.constants.DeeplApiKeyKey
import moe.rukamori.archivetune.constants.DeeplFormalityKey
import moe.rukamori.archivetune.constants.OpenRouterApiKeyKey
import moe.rukamori.archivetune.constants.OpenRouterBaseUrlKey
import moe.rukamori.archivetune.constants.OpenRouterModelKey
import moe.rukamori.archivetune.extensions.toEnum

@Immutable
data class AiModelOption(
    val id: String,
    val displayName: String,
)

@Immutable
data class AiServiceConfig(
    val provider: AiProvider,
    val apiKey: String,
    val customEndpoint: String,
    val model: String,
    val deeplFormality: String = "default",
) {
    val supportsRomanization: Boolean
        get() = canCallApi && provider != AiProvider.DEEPL

    /** Opaque, process-local identity; neither credentials nor signed endpoint URLs are retained in keys. */
    val cacheIdentity: String
        get() = aiCacheIdentity(provider.name, apiKey, customEndpoint, model, deeplFormality)

    val canCallApi: Boolean
        get() =
            provider != AiProvider.NONE &&
                apiKey.isNotBlank() &&
                (provider != AiProvider.CUSTOM || customEndpoint.isNotBlank())
}

fun Preferences.toAiServiceConfig(): AiServiceConfig {
    val provider = this[AiProviderKey].toEnum(AiProvider.NONE)
    val isOpenRouter = provider == AiProvider.OPENROUTER
    val isDeepL = provider == AiProvider.DEEPL
    return AiServiceConfig(
        provider = provider,
        apiKey =
            when {
                isOpenRouter -> this[OpenRouterApiKeyKey].orEmpty()
                isDeepL -> this[DeeplApiKeyKey].orEmpty()
                else -> this[AiApiKeyKey].orEmpty()
            },
        customEndpoint = if (isOpenRouter) this[OpenRouterBaseUrlKey].orEmpty() else this[AiCustomEndpointKey].orEmpty(),
        model =
            when {
                provider == AiProvider.CUSTOM -> this[AiCustomModelKey].orEmpty()
                isOpenRouter -> this[OpenRouterModelKey].orEmpty().ifBlank { "openai/gpt-4o-mini" }
                else -> this[AiSelectedModelKey].orEmpty()
            },
        deeplFormality = this[DeeplFormalityKey].orEmpty().ifBlank { "default" },
    )
}

private val aiIdentitySalt = java.util.UUID.randomUUID().toString()

internal fun aiCacheIdentity(vararg parts: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    (listOf(aiIdentitySalt) + parts).forEach { part ->
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
