/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ai

import androidx.datastore.preferences.core.mutablePreferencesOf
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.DeeplApiKeyKey
import moe.rukamori.archivetune.constants.DeeplFormalityKey
import moe.rukamori.archivetune.constants.OpenRouterApiKeyKey
import moe.rukamori.archivetune.constants.OpenRouterBaseUrlKey
import moe.rukamori.archivetune.constants.OpenRouterModelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {
    @Test
    fun openRouterUsesDedicatedCredentialsAndEndpoint() {
        val config =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.OPENROUTER.name,
                OpenRouterApiKeyKey to "router-key",
                OpenRouterBaseUrlKey to "https://router.example/v1",
                OpenRouterModelKey to "router-model",
            ).toAiServiceConfig()

        assertEquals(AiProvider.OPENROUTER, config.provider)
        assertEquals("router-key", config.apiKey)
        assertEquals("https://router.example/v1", config.customEndpoint)
        assertEquals("router-model", config.model)
        assertTrue(config.canCallApi)
    }

    @Test
    fun deepLUsesDeepLCredentialsAndFormality() {
        val config =
            mutablePreferencesOf(
                AiProviderKey to AiProvider.DEEPL.name,
                DeeplApiKeyKey to "deepl-key",
                DeeplFormalityKey to "more",
            ).toAiServiceConfig()

        assertEquals(AiProvider.DEEPL, config.provider)
        assertEquals("deepl-key", config.apiKey)
        assertEquals("more", config.deeplFormality)
        assertTrue(config.canCallApi)
    }
}
