/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit

/** Loads TDLib's native library, fetching it on demand when the build did not bundle it. */
object TdLibNativeLibrary {
    private const val TAG = "TdLibNative"

    /** The TDLight build the vendored org.drinkless.tdlib binding was generated from. */
    const val VERSION = "tdlight-2b51b33"

    private const val LIB_NAME = "tdjni"
    private const val FILE_NAME = "libtdjni.so"

    /**
     * SHA-256 of each ABI's **decompressed** library, published as the release's
     * libtdjni-digests.txt beside the .so.gz downloads. A download that does not match one of
     * these is discarded — the digests are the only thing standing between the app and whatever
     * the release host serves, which is what makes hosting them upstream safe.
     */
    private val DIGESTS =
        mapOf(
            "arm64-v8a" to "29e0ffb1e99ef30f1ae6db1a9ffc76e4bb82f6a888f91999596de237d17ea110",
            "armeabi-v7a" to "d30b446aa6906274655e68317460b485c41cac3c258a86fa99627add089bce12",
            "x86" to "3d3a67b2b0a924d3a2105fde12d852517cfd871371d94eddad9425e32622166e",
            "x86_64" to "5b114899f4e0aeefb2580131c6d3d48a9135c008328fca94368ccccc2f3550e7",
        )

    @Volatile
    private var loaded = false

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // No callTimeout: this is a ~10 MB body and the deadline that matters is per-read.
            .build()

    /**
     * The device's ABI, as one of the four the release ships. `SUPPORTED_ABIS` is ordered
     * best-first, so a 64-bit device that also lists armeabi-v7a still picks arm64-v8a.
     */
    private val abi: String?
        get() = Build.SUPPORTED_ABIS.firstOrNull { it in DIGESTS }

    private fun target(context: Context): File =
        File(File(context.filesDir, "tdlib-native"), "$VERSION-${abi.orEmpty()}-$FILE_NAME")

    /** True once the library is usable in this process. */
    val isLoaded: Boolean get() = loaded

    /** True when the library still has to be downloaded before Telegram can start. */
    fun needsDownload(context: Context): Boolean =
        !loaded && !BuildConfig.TDLIB_BUNDLED && !target(context).isFile

    /**
     * Loads the library if it can be, without touching the network. Returns false on a slim build
     * that has not fetched it yet — the caller should offer [download].
     */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true

        // Bundled build: it is inside the APK, and the linker already knows where to find it.
        // Tried first (and unconditionally) so a build that reverts the slim flag keeps working
        // even with a stale download still sitting in filesDir.
        if (runCatching { System.loadLibrary(LIB_NAME) }.isSuccess) {
            loaded = true
            return true
        }

        val file = target(context)
        if (!file.isFile) return false
        // Re-verify on every cold start rather than trusting the file's presence: it is loaded as
        // executable code, and a truncated write or a tampered file is exactly what must not run.
        if (!matchesDigest(file)) {
            Timber.tag(TAG).w("Cached %s failed its digest check; deleting", file.name)
            file.delete()
            return false
        }
        return runCatching {
            System.load(file.absolutePath)
            loaded = true
            true
        }.getOrElse {
            Timber.tag(TAG).e(it, "Loading %s failed", file.absolutePath)
            false
        }
    }

    /**
     * Downloads the library for this device's ABI, verifies it, and loads it.
     *
     * [onProgress] receives 0f..1f, or -1f while the total size is unknown. Returns false on any
     * failure; the partial file is always cleaned up, so a retry starts clean rather than
     * resuming into a file whose first half came from a different response.
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (ensureLoaded(context)) return@withContext true

            val abi = abi
            if (abi == null) {
                Timber.tag(TAG).e("No supported ABI among %s", Build.SUPPORTED_ABIS.joinToString())
                return@withContext false
            }
            val base = BuildConfig.TDLIB_NATIVE_BASE_URL.trim().trimEnd('/')
            if (base.isEmpty()) {
                Timber.tag(TAG).e("This build has no TDLIB_NATIVE_BASE_URL to download from")
                return@withContext false
            }

            val url = "$base/libtdjni-$abi.so.gz"
            val destination = target(context)
            destination.parentFile?.mkdirs()
            val partial = File(destination.absolutePath + ".part")
            partial.delete()

            val ok =
                runCatching {
                    client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.tag(TAG).e("Download of %s failed: HTTP %d", url, response.code)
                            return@use false
                        }
                        val body = response.body ?: return@use false
                        // contentLength is the compressed size, so progress is reported against
                        // bytes pulled off the wire rather than bytes written to disk.
                        val total = body.contentLength()
                        val counting = CountingInputStream(body.byteStream())
                        GZIPInputStream(counting).use { gunzip ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                while (true) {
                                    val n = gunzip.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    onProgress(
                                        if (total > 0) (counting.count.toFloat() / total).coerceAtMost(1f) else -1f,
                                    )
                                }
                            }
                        }
                        true
                    }
                }.getOrElse {
                    Timber.tag(TAG).e(it, "Download of %s failed", url)
                    false
                }

            if (!ok || !matchesDigest(partial)) {
                if (ok) Timber.tag(TAG).e("Downloaded %s did not match its expected digest", url)
                partial.delete()
                return@withContext false
            }

            // Rename only after the digest passes, so `destination` never exists in a bad state
            // and ensureLoaded can treat its presence as "worth verifying" rather than "unknown".
            if (!partial.renameTo(destination)) {
                Timber.tag(TAG).e("Could not move the verified library into place")
                partial.delete()
                return@withContext false
            }
            ensureLoaded(context)
        }

    private fun matchesDigest(file: File): Boolean {
        val expected = DIGESTS[abi] ?: return false
        val digest =
            runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        md.update(buffer, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull() ?: return false
        return digest.equals(expected, ignoreCase = true)
    }

    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

    /**
     * Counts bytes read from the wire so download progress tracks the compressed body, which is
     * what `Content-Length` describes. Reading `GZIPInputStream`'s output instead would overshoot,
     * since the decompressed library is roughly twice the size of the transfer.
     */
    private class CountingInputStream(
        private val source: InputStream,
    ) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int = source.read().also { if (it >= 0) count++ }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = source.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = source.close()
    }
}
