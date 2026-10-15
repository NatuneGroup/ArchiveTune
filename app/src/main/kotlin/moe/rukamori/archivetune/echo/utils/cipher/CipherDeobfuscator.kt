/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.echo.utils.cipher

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

object CipherDeobfuscator {
    private const val TAG = "Metrolist_CipherDeobfusc"

    lateinit var appContext: Context
        private set

    @Volatile
    private var initialized = false

    /**
     * The echo resolver is a last resort, so its config stores are loaded on first use rather than
     * at app start. Until this ran, its PoToken path died on the unset [appContext] and echo fell
     * back to clients without a token.
     */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching { initialize(context) }
                .onFailure { Timber.tag(TAG).w(it, "CipherDeobfuscator init failed") }
            initialized = ::appContext.isInitialized
        }
    }

    fun initialize(context: Context) {
        Timber.tag(TAG).d("CipherDeobfuscator initializing...")
        appContext = context.applicationContext

        Timber.tag(TAG).d("Initializing PlayerConfigStore (bundled + cached overlay)...")
        PlayerConfigStore.initialize(appContext)
        Timber.tag(TAG).d("Known config hashes after init: ${PlayerConfigStore.knownHashes().sorted().joinToString()}")
        PlayerConfigStore.scheduleStartupRefresh()

        PlayerDatesStore.initialize(appContext)
        Timber.tag(TAG).d("CipherDeobfuscator initialized")
    }

    private var cipherWebView: CipherWebView? = null

    private var builtConfigEpoch = -1

    @Volatile
    private var currentPlayerHash: String? = null

    val lastUsedPlayerHash: String? get() = currentPlayerHash

    private val deobfuscateMutex = Mutex()

    private val rendererRecoveryPolicy = RendererRecoveryPolicy()

    suspend fun signatureTimestamp(): Int? {
        Timber.tag(TAG).d("Resolving cipher player signatureTimestamp...")
        val (playerJs, hash) = PlayerJsFetcher.getPlayerJs(forceRefresh = false) ?: run {
            Timber.tag(TAG).w("signatureTimestamp: could not fetch player JS")
            return null
        }
        val sts = FunctionNameExtractor.extractSignatureTimestamp(playerJs, hash)
        Timber.tag(TAG).d("Cipher player STS (hash=$hash): $sts")
        return sts
    }

    suspend fun prewarm() {
        Timber.tag(TAG).d("Prewarming cipher WebView...")
        deobfuscateMutex.withLock {
            try {
                getOrCreateWebView(forceRefresh = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CipherRendererGoneException) {
                onRendererGone(e, "prewarm")
            }
        }
    }

    suspend fun deobfuscateStreamUrl(signatureCipher: String, videoId: String): String? = deobfuscateMutex.withLock {
        try {
            deobfuscateInternal(signatureCipher, videoId, isRetry = false)
                ?.also { rendererRecoveryPolicy.onSuccess() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CipherRendererGoneException) {

            onRendererGone(e, "deobfuscate")
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Cipher deobfuscation failed, retrying with fresh JS: ${e.message}")
            try {
                PlayerJsFetcher.invalidateCache()
                closeWebView()
                deobfuscateInternal(signatureCipher, videoId, isRetry = true)
                    ?.also { rendererRecoveryPolicy.onSuccess() }
            } catch (retryE: CancellationException) {
                throw retryE
            } catch (retryE: CipherRendererGoneException) {
                onRendererGone(retryE, "deobfuscate-retry")
                null
            } catch (retryE: Exception) {
                Timber.tag(TAG).e(retryE, "Cipher deobfuscation retry also failed: ${retryE.message}")
                null
            }
        }
    }

    suspend fun onStreamRejected(): Boolean = PlayerConfigStore.refreshAfterStreamRejection()

    private suspend fun deobfuscateInternal(signatureCipher: String, videoId: String, isRetry: Boolean): String? {
        Timber.tag(TAG).d("deobfuscateInternal: videoId=$videoId, isRetry=$isRetry")

        val params = parseQueryParams(signatureCipher)
        val obfuscatedSig = params["s"]
        val sigParam = params["sp"] ?: "signature"
        val baseUrl = params["url"]

        Timber.tag(TAG).d("Parsed signatureCipher params:")
        Timber.tag(TAG).d("  s (obfuscated sig): ${obfuscatedSig?.take(30)}... (length=${obfuscatedSig?.length})")
        Timber.tag(TAG).d("  sp (sig param name): $sigParam")
        Timber.tag(TAG).d("  url: ${baseUrl?.take(80)}...")

        if (obfuscatedSig == null || baseUrl == null) {
            Timber.tag(TAG).e("Could not parse signatureCipher params: s=${obfuscatedSig != null}, url=${baseUrl != null}")
            return null
        }

        val webView = getOrCreateWebView(forceRefresh = isRetry)
        if (webView == null) {
            Timber.tag(TAG).e("Failed to get/create CipherWebView")
            return null
        }

        Timber.tag(TAG).d("Calling webView.deobfuscateSignature()...")
        val deobfuscatedSig = webView.deobfuscateSignature(obfuscatedSig)
        Timber.tag(TAG).d("Deobfuscated signature: ${deobfuscatedSig.take(30)}... (length=${deobfuscatedSig.length})")

        val separator = if ("?" in baseUrl) "&" else "?"
        val finalUrl = "$baseUrl${separator}${sigParam}=${Uri.encode(deobfuscatedSig)}"

        Timber.tag(TAG).d("=== CIPHER DEOBFUSCATION SUCCESS ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("Final URL length: ${finalUrl.length}")
        Timber.tag(TAG).d("Final URL preview: ${finalUrl.take(100)}...")

        return finalUrl
    }

    suspend fun transformNParamInUrl(url: String): String = deobfuscateMutex.withLock {

        Timber.tag(TAG).d("=== N-TRANSFORM URL ===")
        Timber.tag(TAG).d("Input URL length: ${url.length}")
        Timber.tag(TAG).d("Input URL preview: ${url.take(100)}...")

        try {
            transformNInternal(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CipherRendererGoneException) {
            onRendererGone(e, "n-transform")
            url
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "N-transform failed, returning original URL: ${e.message}")
            url
        }
    }

    private suspend fun transformNInternal(url: String): String {

        val nMatch = Regex("[?&]n=([^&]+)").find(url)
        if (nMatch == null) {
            Timber.tag(TAG).d("No 'n' parameter found in URL, skipping transform")
            return url
        }

        val nValueEncoded = nMatch.groupValues[1]
        val nValue = Uri.decode(nValueEncoded)
        Timber.tag(TAG).d("N-param found:")
        Timber.tag(TAG).d("  encoded: $nValueEncoded")
        Timber.tag(TAG).d("  decoded: $nValue")

        val webView = getOrCreateWebView(forceRefresh = false)
        if (webView == null) {
            Timber.tag(TAG).e("Failed to get CipherWebView for n-transform")
            return url
        }

        Timber.tag(TAG).d("CipherWebView state:")
        Timber.tag(TAG).d("  nFunctionAvailable: ${webView.nFunctionAvailable}")
        Timber.tag(TAG).d("  discoveredNFuncName: ${webView.discoveredNFuncName}")
        Timber.tag(TAG).d("  usingHardcodedMode: ${webView.usingHardcodedMode}")

        if (!webView.nFunctionAvailable) {
            Timber.tag(TAG).e("N-transform function was not discovered at init time")
            return url
        }

        Timber.tag(TAG).d("Calling webView.transformN()...")
        val transformedN = webView.transformN(nValue)
        rendererRecoveryPolicy.onSuccess()

        Timber.tag(TAG).d("=== N-TRANSFORM SUCCESS ===")
        Timber.tag(TAG).d("N-param: $nValue -> $transformedN")

        val transformedUrl = url.replaceFirst(
            Regex("([?&])n=[^&]+"),
            "$1n=${Uri.encode(transformedN)}"
        )

        Timber.tag(TAG).d("Transformed URL length: ${transformedUrl.length}")
        return transformedUrl
    }

    private suspend fun onRendererGone(e: CipherRendererGoneException, where: String) {
        rendererRecoveryPolicy.onFailure(SystemClock.elapsedRealtime())
        Timber.tag(TAG).e(
            e,
            "WebView renderer gone during $where (consecutive failures: " +
                "${rendererRecoveryPolicy.consecutiveFailures}) — dropping cipher WebView"
        )
        closeWebView()
    }

    private suspend fun getOrCreateWebView(forceRefresh: Boolean): CipherWebView? {
        Timber.tag(TAG).d("getOrCreateWebView: forceRefresh=$forceRefresh, existing=${cipherWebView != null}")

        if (cipherWebView?.isDead == true) {
            Timber.tag(TAG).w("Cached cipher WebView renderer is dead — discarding")
            closeWebView()
        }

        if (!rendererRecoveryPolicy.shouldAttempt(SystemClock.elapsedRealtime())) {
            Timber.tag(TAG).w(
                "Skipping cipher WebView creation: ${rendererRecoveryPolicy.consecutiveFailures} " +
                    "consecutive renderer deaths, in backoff window"
            )
            return null
        }

        val epochAtStart = PlayerConfigStore.configEpoch
        if (!forceRefresh && cipherWebView != null && builtConfigEpoch == epochAtStart) {
            Timber.tag(TAG).d("Reusing existing CipherWebView (hash=$currentPlayerHash)")
            return cipherWebView
        }

        var builtEpoch = epochAtStart

        if (cipherWebView != null) {
            Timber.tag(TAG).d("Closing existing CipherWebView...")
            closeWebView()
        }

        Timber.tag(TAG).d("Fetching player JS...")
        val result = PlayerJsFetcher.getPlayerJs(forceRefresh = forceRefresh)
        if (result == null) {
            Timber.tag(TAG).e("Failed to get player JS")
            return null
        }
        val (playerJs, hash) = result
        Timber.tag(TAG).d("Got player JS: hash=$hash, length=${playerJs.length}")

        Timber.tag(TAG).d("Analyzing player JS for cipher functions (knownHash=$hash)...")
        var analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)

        val sigFromConfig = analysis.sigInfo?.isHardcoded == true
        val nFromConfig = analysis.nFuncInfo?.isHardcoded == true
        if (!sigFromConfig || !nFromConfig) {
            Timber.tag(TAG).w("Extraction not fully config-backed for player $hash (sigConfig=$sigFromConfig, nConfig=$nFromConfig; sig=${analysis.sigInfo != null}, n=${analysis.nFuncInfo != null}) — forcing remote config refresh")
            val healed = PlayerConfigStore.forceRefresh(missingHash = hash)
            Timber.tag(TAG).d("forceRefresh($hash) -> hashNowKnown=$healed")
            if (healed) {
                analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)
                builtEpoch = PlayerConfigStore.configEpoch
                Timber.tag(TAG).d("Re-extracted after refresh: sigConfig=${analysis.sigInfo?.isHardcoded == true}, nConfig=${analysis.nFuncInfo?.isHardcoded == true}")
            }
        }

        if (analysis.sigInfo == null) {
            Timber.tag(TAG).e("Could not extract signature function info from player JS")
            return null
        }

        if (analysis.nFuncInfo == null) {
            Timber.tag(TAG).w("Could not extract n-function info from player JS (will try brute-force)")
        }

        Timber.tag(TAG).d("Creating CipherWebView...")
        Timber.tag(TAG).d("  sig: ${analysis.sigInfo.name} (constantArg=${analysis.sigInfo.constantArg}, hardcoded=${analysis.sigInfo.isHardcoded})")
        Timber.tag(TAG).d("  nFunc: ${analysis.nFuncInfo?.name}[${analysis.nFuncInfo?.arrayIndex}] (hardcoded=${analysis.nFuncInfo?.isHardcoded})")

        val webView = CipherWebView.create(
            context = appContext,
            playerJs = playerJs,
            sigInfo = analysis.sigInfo,
            nFuncInfo = analysis.nFuncInfo,
        )

        Timber.tag(TAG).d("CipherWebView created successfully")
        Timber.tag(TAG).d("  nFunctionAvailable: ${webView.nFunctionAvailable}")
        Timber.tag(TAG).d("  sigFunctionAvailable: ${webView.sigFunctionAvailable}")
        Timber.tag(TAG).d("  discoveredNFuncName: ${webView.discoveredNFuncName}")

        cipherWebView = webView
        currentPlayerHash = hash
        builtConfigEpoch = builtEpoch
        return webView
    }

    private suspend fun closeWebView() {
        Timber.tag(TAG).d("closeWebView: existing=${cipherWebView != null}")
        withContext(Dispatchers.Main) {
            runCatching { cipherWebView?.close() }
                .onFailure { Timber.tag(TAG).w("closeWebView threw: $it") }
        }
        cipherWebView = null
        currentPlayerHash = null
        Timber.tag(TAG).d("CipherWebView closed and cleared")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = Uri.decode(pair.substring(0, idx))
                val value = Uri.decode(pair.substring(idx + 1))
                result[key] = value
            }
        }
        Timber.tag(TAG).v("parseQueryParams: ${result.keys.joinToString()}")
        return result
    }

    fun getDebugInfo(): Map<String, Any?> {
        return mapOf(
            "hasWebView" to (cipherWebView != null),
            "playerHash" to currentPlayerHash,
            "nFunctionAvailable" to cipherWebView?.nFunctionAvailable,
            "sigFunctionAvailable" to cipherWebView?.sigFunctionAvailable,
            "discoveredNFuncName" to cipherWebView?.discoveredNFuncName,
            "usingHardcodedMode" to cipherWebView?.usingHardcodedMode,
        )
    }
}
