/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A cancelled attempt still owns shared staging until its coroutine, including cleanup, ends. */
internal class UpdateAttemptOwner<S>(private val scope: CoroutineScope, private val idle: S) {
    private var job: Job? = null
    private val mutableState = MutableStateFlow(idle)
    val state = mutableState.asStateFlow()

    @Synchronized
    fun start(downloading: S, work: suspend () -> S): Boolean {
        if (job?.isCompleted == false) return false
        mutableState.value = downloading
        job = scope.launch(start = CoroutineStart.LAZY) {
            val terminal = work()
            synchronized(this@UpdateAttemptOwner) {
                if (job?.isActive == true) mutableState.value = terminal
            }
        }
        job?.start()
        return true
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        mutableState.value = idle
    }

    @Synchronized
    fun update(change: (S) -> S) {
        if (job?.isActive == true) mutableState.value = change(mutableState.value)
    }

    @Synchronized
    fun acknowledge(canClear: (S) -> Boolean) {
        if (canClear(mutableState.value)) mutableState.value = idle
    }
}
