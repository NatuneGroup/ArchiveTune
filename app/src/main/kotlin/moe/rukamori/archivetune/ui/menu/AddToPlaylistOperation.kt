/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.menu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal data class PlaylistAddState(val loading: Boolean = false, val failed: Boolean = false)

/** Owns the actual dialog fetch/insert work, even while its remembered scope stays composed. */
internal class AddToPlaylistOperation(private val scope: CoroutineScope) {
    @Volatile private var revision = 0L
    private var job: Job? = null
    private val mutableState = MutableStateFlow(PlaylistAddState())
    val state = mutableState.asStateFlow()

    fun cancel() {
        revision++
        job?.cancel()
        job = null
        mutableState.value = PlaylistAddState()
    }

    fun <T, R> start(
        targets: List<T>,
        load: suspend () -> List<String>,
        insert: suspend Attempt.(List<T>, List<String>) -> R,
        onSuccess: (R) -> Unit,
    ): Job {
        cancel()
        val attempt = Attempt(revision)
        val targetSnapshot = targets.toList()
        mutableState.value = PlaylistAddState(loading = true)
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val songs = load().toList()
                attempt.ensureCurrent()
                val result = attempt.insert(targetSnapshot, songs)
                attempt.ensureCurrent()
                onSuccess(result)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Never expose transport URLs, account details or provider error messages.
                if (attempt.isCurrent()) mutableState.value = PlaylistAddState(failed = true)
            } finally {
                if (attempt.isCurrent()) {
                    mutableState.value = mutableState.value.copy(loading = false)
                    job = null
                }
            }
        }
        job = launched
        launched.start()
        return launched
    }

    inner class Attempt internal constructor(private val origin: Long) {
        internal fun isCurrent() = origin == revision

        suspend fun ensureCurrent() {
            coroutineContext.ensureActive()
            if (!isCurrent()) throw CancellationException("Playlist add operation replaced")
        }
    }
}
