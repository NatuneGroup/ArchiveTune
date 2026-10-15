/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal open class DownloadRequestLifetime<E>(val directory: File) {
    val latch = CountDownLatch(1)
    val error = AtomicReference<E?>(null)
    @Volatile var cancelled = false
        private set

    fun completed() {
        latch.countDown()
        if (cancelled) cleanup()
    }

    fun failed(value: E) {
        error.set(value)
        completed()
    }

    fun cancel() {
        cancelled = true
        latch.countDown()
    }

    fun cleanup() {
        // Includes transport partials, but never another request's directory.
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    companion object {
        fun createDirectory(parent: File): File {
            parent.mkdirs()
            return File(parent, "request_${UUID.randomUUID()}").also {
                if (!it.mkdir()) throw IOException("Cannot create download staging directory")
            }
        }
    }
}

internal fun downloadedBytesRemaining(
    requestedLength: Long,
    reportedLength: Long,
    fileLength: Long,
    position: Long,
): Long {
    val available = if (reportedLength < 0) fileLength - position else reportedLength
    return if (requestedLength == -1L) available else minOf(requestedLength, available)
}

internal fun downloadedReadLength(requested: Int, remaining: Long): Int =
    minOf(requested.toLong(), remaining).toInt()
