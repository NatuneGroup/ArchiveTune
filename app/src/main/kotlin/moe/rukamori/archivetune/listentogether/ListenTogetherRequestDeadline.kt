/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.listentogether

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ListenTogetherRequestDeadline(private val scope: CoroutineScope) {
    private val generation = AtomicLong(0L)
    private var timeoutJob: Job? = null

    @Synchronized
    fun start(timeoutMs: Long, onTimeout: () -> Unit): Long {
        require(timeoutMs > 0L)
        val attempt = generation.incrementAndGet()
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (claimTimeout(attempt)) onTimeout()
        }
        return attempt
    }

    fun isCurrent(attempt: Long): Boolean = generation.get() == attempt

    @Synchronized
    fun complete() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    @Synchronized
    fun cancel() {
        generation.incrementAndGet()
        complete()
    }

    @Synchronized
    private fun claimTimeout(attempt: Long): Boolean {
        if (!isCurrent(attempt) || timeoutJob?.isActive != true) return false
        timeoutJob = null
        return true
    }
}

internal fun listenTogetherRequestFailure(event: ListenTogetherEvent): String? =
    when (event) {
        is ListenTogetherEvent.ConnectionError -> event.error
        is ListenTogetherEvent.ServerError -> event.message.takeUnless { event.code == "invalid_message" }
        else -> null
    }
