/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.acoustics

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaDataSource
import android.net.Uri
import android.os.CancellationSignal
import android.system.Os
import android.system.OsConstants
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.audiosource.AudioSourceConfig
import moe.rukamori.archivetune.audiosource.SongSourceOverride
import moe.rukamori.archivetune.constants.AudioSourceOrderKey
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.QobuzBackupEnabledKey
import moe.rukamori.archivetune.constants.QobuzEnabledKey
import moe.rukamori.archivetune.constants.SongSourceOverrideKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.playback.CachedAudioMetadata
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.withAppStreamProxy
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal abstract class AcousticInput(
    val label: String,
    val snapshot: AcousticSourceSnapshot?,
    val advertisedRate: Int?,
    val claimedLossless: Boolean,
    val limitations: List<String>,
    protected val work: AcousticWork,
) : MediaDataSource() {
    protected val closed = AtomicBoolean(false)
    private var bytesRead = 0L

    @Synchronized
    final override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        work.check()
        if (closed.get()) throw AcousticUnavailable("input_closed")
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) {
            throw AcousticUnavailable("invalid_read")
        }
        if (size == 0) return 0
        if (position >= getSize()) return -1
        val count = minOf(size.toLong(), AcousticAuditLimits.MAX_READ_CHUNK.toLong(), getSize() - position).toInt()
        // Charge requests, not just returned bytes, so repeated short/failed reads are bounded too.
        bytesRead += count
        if (bytesRead > AcousticAuditLimits.MAX_READ_BYTES) throw AcousticUnavailable("read_budget_exceeded")
        val read = readBounded(position, buffer, offset, count)
        work.check()
        return read
    }

    protected abstract fun readBounded(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
}

internal class AcousticAuditSourceResolver(
    private val context: Context,
    private val caches: List<Cache>,
    private val snapshot: (String) -> AcousticSourceSnapshot?,
) {
    suspend fun resolve(target: AcousticAuditTarget, work: AcousticWork): AcousticInput {
        work.check()
        if (target.mediaId.isBlank()) throw AcousticUnavailable("no_target")
        val uri = Uri.parse(target.mediaId)
        if (uri.scheme == "file") {
            if (!uri.authority.isNullOrEmpty() && uri.authority != "localhost") {
                throw AcousticUnavailable("local_authority_unsupported")
            }
            val file = File(uri.path ?: throw AcousticUnavailable("local_unreadable"))
            if (!OsConstants.S_ISREG(Os.stat(file.absolutePath).st_mode)) {
                throw AcousticUnavailable("local_not_regular")
            }
            return FileInput(file, work)
        }
        if (uri.scheme == "content") {
            val opening = ContentOpen()
            work.own(opening)
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", opening.signal)
                ?: throw AcousticUnavailable("content_unreadable")
            opening.accept(descriptor)
            work.check()
            return ContentInput(descriptor, opening, work)
        }
        if (uri.scheme == "android.resource") {
            throw AcousticUnavailable("provider_input_unsupported")
        }
        if (uri.scheme == "telegram") throw AcousticUnavailable("telegram_unsupported")
        val current = withContext(Dispatchers.Main.immediate) {
            AcousticSourcePolicy.selectedSnapshot(target.mediaId, snapshot(target.mediaId))
        }
        val prefs = context.dataStore.data.first()
        work.check()
        val enabled = setOfNotNull(
            AudioSourceType.TIDAL.takeIf { prefs[TidalEnabledKey] ?: true },
            AudioSourceType.QOBUZ.takeIf { prefs[QobuzEnabledKey] ?: false },
            AudioSourceType.QOBUZ_BACKUP.takeIf { prefs[QobuzBackupEnabledKey] ?: false },
        )
        val override = SongSourceOverride.get(prefs[SongSourceOverrideKey], target.mediaId)
        val sources = if (current != null) listOf(current.source) else {
            val ordered = AudioSourceConfig.parseOrder(prefs[AudioSourceOrderKey])
            if (override != null) listOf(override) else ordered
        }
        for (source in sources) {
            work.check()
            if (source !in enabled) continue
            val key = current?.cacheKey ?: "${source.name.lowercase(Locale.ROOT)}:${target.mediaId}"
            for (cache in caches) {
                work.check()
                completeCache(cache, key, source, current, work)?.let { return it }
            }
            if (current != null && current.uri != null && current.expiresAtMs > System.currentTimeMillis()) {
                val input = RangeInput(current, work)
                work.own(input)
                input.resolveLength()
                return input
            }
        }
        throw AcousticUnavailable(if (current != null) "current_source_unavailable" else "no_safe_source")
    }

    fun isCurrent(input: AcousticInput): Boolean =
        input.snapshot?.let { it.matches(snapshot(it.mediaId)) } ?: true

    private fun completeCache(
        cache: Cache,
        key: String,
        source: AudioSourceType,
        current: AcousticSourceSnapshot?,
        work: AcousticWork,
    ): AcousticInput? {
        val metadata = cache.getContentMetadata(key)
        val audio = CachedAudioMetadata.read(metadata, source) ?: return null
        val length = metadata.get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
        if (!AcousticSourcePolicy.cacheMatches(source, length, audio.sampleRateHz, audio.claimedLossless, current)) return null
        val spans = cache.getCachedSpans(key)
        if (spans.size > 2048) return null
        val extents = spans.map { span ->
            work.check()
            val file = span.file ?: return null
            AcousticCacheExtent(
                span.position, span.length, file.length(),
                { OsConstants.S_ISREG(Os.stat(file.absolutePath).st_mode) }, span.isCached,
            )
        }
        if (!AcousticSourcePolicy.completeExtents(length, extents, work::check)) return null
        val segments = spans.map { Segment(it.position, it.length, it.file ?: return null) }
        return CacheInput(
            source.name, length, segments, current, audio.sampleRateHz, audio.claimedLossless, work,
        )
    }
}

private class ContentOpen : Closeable {
    val signal = CancellationSignal()
    private val closed = AtomicBoolean(false)
    private val descriptor = AtomicReference<AssetFileDescriptor?>(null)

    fun accept(value: AssetFileDescriptor) {
        descriptor.set(value)
        if (closed.get()) {
            runCatching { descriptor.getAndSet(null)?.close() }
            throw CancellationException()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                signal.cancel()
            } finally {
                descriptor.getAndSet(null)?.close()
            }
        }
    }
}

private class ContentInput(
    private val descriptor: AssetFileDescriptor,
    private val opening: ContentOpen,
    work: AcousticWork,
) : AcousticInput(
    "LOCAL_CONTENT", null, null, false, listOf("local_content", "excerpt_only"), work,
) {
    private val offset = descriptor.startOffset
    private val stat = Os.fstat(descriptor.fileDescriptor)
    private val length = descriptor.declaredLength.takeIf { it >= 0 } ?: (stat.st_size - offset)

    init {
        if (!OsConstants.S_ISREG(stat.st_mode) || offset < 0 || offset > stat.st_size ||
            length <= 0 || length > stat.st_size - offset
        ) throw AcousticUnavailable("content_not_seekable")
        // Verify seekability without changing a descriptor position shared with its provider.
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
    }

    override fun getSize(): Long = length
    override fun readBounded(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (Os.fstat(descriptor.fileDescriptor).st_size != stat.st_size) {
            throw AcousticUnavailable("local_changed")
        }
        return Os.pread(descriptor.fileDescriptor, buffer, offset, size, this.offset + position)
            .takeIf { it > 0 } ?: -1
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) opening.close()
    }
}

private class FileInput(private val file: File, work: AcousticWork) : AcousticInput(
    "LOCAL_FILE", null, null, false, listOf("local_file", "excerpt_only"), work,
) {
    private val reader = RandomAccessFile(file, "r")
    private val length = runCatching { reader.length() }.getOrElse {
        reader.close()
        throw it
    }
    private val modified = file.lastModified()
    override fun getSize(): Long = length
    override fun readBounded(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (file.lastModified() != modified || file.length() != length) {
            throw AcousticUnavailable("local_changed")
        }
        return reader.channel.read(ByteBuffer.wrap(buffer, offset, size), position)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) reader.close()
    }
}

private class Segment(val start: Long, val length: Long, val file: File)

private class CacheInput(
    provider: String,
    private val length: Long,
    private val segments: List<Segment>,
    snapshot: AcousticSourceSnapshot?,
    rate: Int?,
    lossless: Boolean,
    work: AcousticWork,
) : AcousticInput(
    "${provider}_CACHED", snapshot, rate, lossless,
    listOf("cached_rendition", "excerpt_only"), work,
) {
    override fun getSize(): Long = length
    override fun readBounded(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val segment = segments.firstOrNull { position >= it.start && position - it.start < it.length }
            ?: throw AcousticUnavailable("cache_gap")
        // Hold no cache lock, touch no LRU state and never fall through to a network data source.
        return RandomAccessFile(segment.file, "r").use { reader ->
            if (reader.length() != segment.length) throw AcousticUnavailable("cache_changed")
            reader.channel.read(
                ByteBuffer.wrap(buffer, offset, minOf(size.toLong(), segment.start + segment.length - position).toInt()),
                position - segment.start,
            )
        }
    }
    override fun close() {
        closed.set(true)
    }
}

private class RangeInput(
    private val identity: AcousticSourceSnapshot,
    work: AcousticWork,
) : AcousticInput(
    "${identity.source.name}_CURRENT", identity, identity.advertisedSampleRate,
    identity.claimedLossless, listOf("current_clear_rendition", "excerpt_only"), work,
) {
    companion object {
        // Keep the app's stream proxy policy, but no provider credentials, playback
        // interceptors, redirects or disk cache are attached to independent audit reads.
        private val client = OkHttpClient.Builder()
            .withAppStreamProxy()
            .callTimeout(AcousticAuditLimits.NETWORK_CALL_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(AcousticAuditLimits.NETWORK_CALL_MS, TimeUnit.MILLISECONDS)
            .readTimeout(AcousticAuditLimits.NETWORK_CALL_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
    private val call = AtomicReference<Call?>(null)
    private val url = identity.uri?.toHttpUrlOrNull()?.takeIf {
        it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty()
    } ?: throw AcousticUnavailable("remote_unsupported")
    private var length = identity.contentLength?.takeIf { it > 0 } ?: -1L
    private val block = ByteArray(AcousticAuditLimits.MAX_READ_CHUNK)
    private var blockStart = -1L
    private var blockSize = 0
    private var networkBytes = 0L
    override fun getSize(): Long = length

    fun resolveLength() {
        work.check()
        if (System.currentTimeMillis() >= identity.expiresAtMs) throw AcousticUnavailable("source_expired")
        if (length > 0) return
        // A one-byte range probe obtains container size without downloading/materializing it.
        val request = Request.Builder().url(url)
            .header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
        val owned = client.newCall(request)
        call.set(owned)
        try {
            work.check()
            if (closed.get()) throw AcousticUnavailable("input_closed")
            owned.execute().use { response ->
                val range = response.header("Content-Range").orEmpty()
                val reportedLength = Regex("^bytes 0-0/([1-9][0-9]*)$").matchEntire(range)
                    ?.groupValues?.get(1)?.toLongOrNull()
                if (response.code != 206 || reportedLength == null ||
                    response.header("Content-Encoding")?.let { it != "identity" } == true
                ) throw AcousticUnavailable("remote_length_unknown")
                val body = response.body ?: throw AcousticUnavailable("remote_unreadable")
                networkBytes++
                body.byteStream().use { stream ->
                    work.check()
                    if (stream.read() < 0) throw AcousticUnavailable("remote_short_read")
                }
                work.check()
                length = reportedLength
            }
        } finally {
            call.compareAndSet(owned, null)
            owned.cancel()
        }
    }
    override fun readBounded(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (blockStart < 0 || position < blockStart || position - blockStart >= blockSize) {
            loadBlock(position / block.size * block.size)
        }
        val from = (position - blockStart).toInt()
        val count = minOf(size, blockSize - from)
        block.copyInto(buffer, offset, from, from + count)
        return count
    }

    private fun loadBlock(position: Long) {
        if (System.currentTimeMillis() >= identity.expiresAtMs) throw AcousticUnavailable("source_expired")
        val size = minOf(block.size.toLong(), length - position).toInt()
        networkBytes += size
        if (networkBytes > AcousticAuditLimits.MAX_READ_BYTES) throw AcousticUnavailable("network_budget_exceeded")
        val end = position + size - 1
        val request = Request.Builder().url(url)
            .header("Range", "bytes=$position-$end").header("Accept-Encoding", "identity").build()
        val owned = client.newCall(request)
        call.set(owned)
        try {
            work.check()
            if (closed.get()) throw AcousticUnavailable("input_closed")
            owned.execute().use { response ->
                if (response.code != 206 ||
                    response.header("Content-Range") != "bytes $position-$end/$length" ||
                    response.header("Content-Encoding")?.let { it != "identity" } == true
                ) throw AcousticUnavailable("remote_range_unsupported")
                val body = response.body ?: throw AcousticUnavailable("remote_unreadable")
                body.byteStream().use { input ->
                    var read = 0
                    while (read < size) {
                        work.check()
                        val count = input.read(block, read, size - read)
                        if (count <= 0) throw AcousticUnavailable("remote_short_read")
                        read += count
                    }
                    blockStart = position
                    blockSize = read
                }
            }
        } finally {
            call.compareAndSet(owned, null)
            owned.cancel()
        }
    }
    override fun close() {
        closed.set(true)
        call.getAndSet(null)?.cancel()
    }
}
