/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.audiosource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins how the Amazon instance list is assembled.
 *
 * Getting this wrong is quiet: a dropped instance means the resolver tries fewer hosts and falls
 * through to YouTube, and material sent to the wrong host means the instance answers 401/428 — in
 * both cases the user sees "Amazon did not resolve this track", never an error naming the cause. The
 * rules are pure, so they are worth pinning here rather than discovering on a device.
 */
class AmazonInstancesTest {
    private val now = 1_700_000_000_000L

    @Test
    fun userInstancesComeFirstAndKeepTheirOwnMaterial() {
        val merged =
            AmazonInstances.merge(
                local = listOf("https://mine.example.com/"),
                localBypassToken = "operator-token",
                localTurnstileJwt = null,
                pooled = listOf(AmazonInstance("https://pooled.example.com", bypassToken = "pool-token", fromPool = true)),
                nowMs = now,
            )

        assertEquals(listOf("https://mine.example.com", "https://pooled.example.com"), merged.map { it.baseUrl })
        assertEquals("operator-token", merged.first().bypassToken)
        assertFalse(merged.first().fromPool)
        // The pool's token belongs to the pool's host and must not be offered to the user's, nor the
        // user's to the pool's: a bypass token is issued per operator, a JWT per challenge origin.
        assertEquals(null, merged.first().turnstileJwt)
        assertEquals("pool-token", merged.last().bypassToken)
    }

    @Test
    fun aHostAlreadyConfiguredByTheUserIsNotTriedTwice() {
        val merged =
            AmazonInstances.merge(
                local = listOf("https://shared.example.com"),
                localBypassToken = "mine",
                localTurnstileJwt = null,
                pooled =
                    listOf(
                        AmazonInstance("https://shared.example.com/", bypassToken = "pool", fromPool = true),
                        AmazonInstance("https://other.example.com", bypassToken = "pool", fromPool = true),
                    ),
                nowMs = now,
            )

        // The user's entry survives (with the user's material), the duplicate is dropped, and the
        // unrelated host still gets its turn.
        assertEquals(listOf("https://shared.example.com", "https://other.example.com"), merged.map { it.baseUrl })
        assertEquals("mine", merged.first().bypassToken)
    }

    @Test
    fun aPooledHostWithNoMaterialIsNotWorthACall() {
        val merged =
            AmazonInstances.merge(
                local = emptyList(),
                localBypassToken = "ignored",
                localTurnstileJwt = null,
                pooled =
                    listOf(
                        AmazonInstance("https://bare.example.com", fromPool = true),
                        AmazonInstance("https://with-jwt.example.com", turnstileJwt = "jwt", fromPool = true),
                    ),
                nowMs = now,
            )

        assertEquals(listOf("https://with-jwt.example.com"), merged.map { it.baseUrl })
    }

    @Test
    fun aStalePooledJwtIsSkippedButAnUndatedOneIsStillSent() {
        val merged =
            AmazonInstances.merge(
                local = emptyList(),
                localBypassToken = null,
                localTurnstileJwt = null,
                pooled =
                    listOf(
                        AmazonInstance(
                            baseUrl = "https://expired.example.com",
                            turnstileJwt = "old",
                            turnstileJwtExpiresAtMs = now - 1,
                            fromPool = true,
                        ),
                        AmazonInstance(
                            baseUrl = "https://undated.example.com",
                            turnstileJwt = "no expiry recorded",
                            fromPool = true,
                        ),
                        AmazonInstance(
                            baseUrl = "https://expired-but-tokened.example.com",
                            bypassToken = "operator-token",
                            turnstileJwt = "old",
                            turnstileJwtExpiresAtMs = now - 1,
                            fromPool = true,
                        ),
                    ),
                nowMs = now,
            )

        // A host whose only material went stale is dropped; an entry with no recorded expiry is still
        // worth a call (the instance judges it); and a host whose operator token survives keeps its
        // place with the stale JWT stripped.
        assertEquals(
            listOf("https://undated.example.com", "https://expired-but-tokened.example.com"),
            merged.map { it.baseUrl },
        )
        assertEquals("no expiry recorded", merged.first().turnstileJwt)
        assertEquals("operator-token", merged.last().bypassToken)
        assertEquals(null, merged.last().turnstileJwt)
    }

    @Test
    fun theStoredListSplitsOnBothSeparatorsAndDropsNoise() {
        val urls =
            AmazonInstances.parseBaseUrls(
                " https://one.example.com/ \n\nhttps://two.example.com,  ,https://one.example.com  \n",
            )

        assertEquals(listOf("https://one.example.com", "https://two.example.com"), urls)
    }
}
