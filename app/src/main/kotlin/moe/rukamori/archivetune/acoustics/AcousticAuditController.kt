/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.acoustics

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class AcousticWork : Closeable {
    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "AcousticAuditDeadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    private val cancelled = AtomicBoolean(false)
    private val source = AtomicReference<Closeable?>(null)
    private val deadline = System.nanoTime() + AcousticAuditLimits.DEADLINE_MS * 1_000_000
    private val watchdog = deadlines.schedule({
        runCatching { source.getAndSet(null)?.close() }
    }, AcousticAuditLimits.DEADLINE_MS, TimeUnit.MILLISECONDS)

    fun check() {
        if (cancelled.get()) throw CancellationException()
        if (System.nanoTime() >= deadline) throw AcousticUnavailable("deadline_exceeded")
    }

    fun own(value: Closeable) {
        source.set(value)
        if (cancelled.get()) {
            source.getAndSet(null)?.close()
            throw CancellationException()
        }
        check()
    }

    override fun close() {
        cancelled.set(true)
        watchdog.cancel(false)
        runCatching { source.getAndSet(null)?.close() }
    }
}

private object AcousticWorkers {
    // A slow OS provider/DNS call may outlive cancellation. Keep its lease until it really
    // unwinds, rather than spawning another blocking worker on every retry or target change.
    val permits = Semaphore(2)
    val dispatcher = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "AcousticAuditWorker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).asCoroutineDispatcher()
}

class AcousticAuditController internal constructor(
    private val target: AcousticAuditTarget,
    private val resolver: AcousticAuditSourceResolver,
) : Closeable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + AcousticWorkers.dispatcher)
    private val mutableState = MutableStateFlow(AcousticAuditState())
    val state: StateFlow<AcousticAuditState> = mutableState.asStateFlow()
    private var generation = 0L
    private var closed = false
    private var active = true
    private var job: Job? = null
    private var work: AcousticWork? = null

    fun start() {
        synchronized(lock) {
            // A cancelled decoder must finish unwinding before another run can own resources.
            if (closed || !active || job?.isCompleted == false) return
            if (!AcousticWorkers.permits.tryAcquire()) {
                mutableState.value = AcousticAuditState(
                    AcousticAuditStatus.UNAVAILABLE, message = "analysis_busy",
                )
                return
            }
            val token = ++generation
            val ownedWork = AcousticWork()
            val entered = AtomicBoolean(false)
            work = ownedWork
            mutableState.value = AcousticAuditState(AcousticAuditStatus.RESOLVING, 0f)
            job = scope.launch {
                entered.set(true)
                try {
                    val source = withTimeout(AcousticAuditLimits.DEADLINE_MS) {
                        resolver.resolve(target, ownedWork).also(ownedWork::own)
                    }
                    source.use {
                        publish(token, AcousticAuditState(AcousticAuditStatus.DECODING, 0f))
                        val pcm = AcousticAuditDecoder.decode(source, ownedWork) { progress ->
                            publish(token, AcousticAuditState(AcousticAuditStatus.DECODING, progress))
                        }
                        publish(token, AcousticAuditState(AcousticAuditStatus.ANALYZING, 0f))
                        val result = AcousticSpectrogramAnalyzer.analyze(
                            target.mediaId, source.label, source.advertisedRate, source.claimedLossless,
                            source.limitations, pcm, ownedWork::check,
                        ) { progress ->
                            publish(token, AcousticAuditState(AcousticAuditStatus.ANALYZING, progress))
                        }
                        currentCoroutineContext().ensureActive()
                        ownedWork.check()
                        if (!withContext(Dispatchers.Main.immediate) { resolver.isCurrent(source) }) {
                            throw AcousticUnavailable("source_changed")
                        }
                        publish(token, AcousticAuditState(AcousticAuditStatus.COMPLETE, 1f, result))
                    }
                } catch (_: TimeoutCancellationException) {
                    publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = "deadline_exceeded"))
                } catch (_: CancellationException) {
                    publish(token, AcousticAuditState(AcousticAuditStatus.CANCELLED))
                } catch (error: AcousticUnavailable) {
                    publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = error.code))
                } catch (_: IOException) {
                    publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = "input_unreadable"))
                } catch (_: android.system.ErrnoException) {
                    publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = "local_unreadable"))
                } catch (_: SecurityException) {
                    publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = "input_permission_denied"))
                } catch (_: Exception) {
                    // Platform/network exception messages can contain URIs or provider content.
                    publish(token, AcousticAuditState(AcousticAuditStatus.FAILED, message = "analysis_failed"))
                } finally {
                    ownedWork.close()
                }
            }.also { launched ->
                // Also covers cancellation before the coroutine's try/finally was entered.
                launched.invokeOnCompletion { cause ->
                    ownedWork.close()
                    AcousticWorkers.permits.release()
                    if (cause != null && !entered.get()) {
                        publish(token, AcousticAuditState(AcousticAuditStatus.UNAVAILABLE, message = "analysis_busy"))
                    }
                }
            }
        }
    }

    private fun publish(token: Long, value: AcousticAuditState) {
        synchronized(lock) {
            if (!closed && active && token == generation) mutableState.value = value
        }
    }

    fun cancel() {
        synchronized(lock) {
            generation++
            work?.close()
            work = null
            job?.cancel()
            mutableState.value = AcousticAuditState(AcousticAuditStatus.CANCELLED)
        }
    }

    internal fun setActive(value: Boolean) {
        synchronized(lock) {
            active = value
            if (!value) cancel()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cancel()
            scope.cancel()
        }
    }
}
