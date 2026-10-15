/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import androidx.compose.runtime.State
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.ui.component.applyNavigationTreatment
import moe.rukamori.archivetune.ui.component.navigationTreatmentKeys
import org.junit.Assert.*
import org.junit.Test

class RestartPreferenceCommitTest {
    private class Store(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        override val data: Flow<Preferences> = values
        var transactions = 0
        var barrier: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            transactions++
            val proposed = transform(values.value)
            barrier?.await()
            if (fail) throw IOException("save failed")
            values.value = proposed
            return proposed
        }
        fun snapshot() = PreferenceSnapshot(object : State<Preferences> {
            override val value: Preferences get() = values.value
        }, this)
    }

    @Test(timeout = 5_000)
    fun editAndAwait_doesNotReturnBeforeSaveAndCapturesImmutableBeforeAfter() = runBlocking {
        val store = Store()
        val barrier = CompletableDeferred<Unit>()
        store.barrier = barrier
        val edit = async { store.snapshot().editAndAwait { this[TabletModeEnabledKey] = true } }
        yield()
        assertFalse(edit.isCompleted)
        assertNull(store.values.value[TabletModeEnabledKey])
        barrier.complete(Unit)
        val result = edit.await()
        assertNull(result.before[TabletModeEnabledKey])
        assertEquals(true, result.after[TabletModeEnabledKey])
        assertEquals(1, store.transactions)
    }

    @Test
    fun failedWrite_doesNotReturnSuccessfulResultOrMutateSavedValues() = runBlocking {
        val store = Store().apply { fail = true }
        var result: PreferenceEditResult? = null
        try {
            result = store.snapshot().editAndAwait { this[LiquidGlassEnabledKey] = true }
            fail("expected save failure")
        } catch (_: IOException) {
            assertNull(result)
            assertNull(store.values.value[LiquidGlassEnabledKey])
        }
    }

    @Test(timeout = 5_000)
    fun cancelledPendingWrite_doesNotCommitOrReturnAnEditResult() = runBlocking {
        val store = Store().apply { barrier = CompletableDeferred() }
        var returned = false
        val edit = async {
            store.snapshot().editAndAwait { this[TabletModeEnabledKey] = true }
            returned = true
        }
        yield()
        edit.cancelAndJoin()
        assertFalse(returned)
        assertNull(store.values.value[TabletModeEnabledKey])
    }

    @Test
    fun navigationTreatment_eachChoiceIsOneExclusiveTransactionAndDisablePreservesOthers() = runBlocking {
        for (key in navigationTreatmentKeys) {
            val store = Store(mutablePreferencesOf(*navigationTreatmentKeys.map { it to true }.toTypedArray()))
            val result = store.snapshot().editAndAwait { applyNavigationTreatment(key, true) }
            assertEquals(1, store.transactions)
            assertTrue(navigationTreatmentKeys.all { result.before[it] == true })
            navigationTreatmentKeys.forEach { assertEquals(it == key, result.after[it]) }
            val disabled = store.snapshot().editAndAwait { applyNavigationTreatment(key, false) }
            assertTrue(navigationTreatmentKeys.all { disabled.after[it] == false })
        }
    }

    @Test
    fun customFont_uriNameAndCanonicalSelectionCommitTogetherWithOneReason() = runBlocking {
        val store = Store()
        val result = store.snapshot().editAndAwait { setCustomFont("content://font/new", "new.ttf") }
        assertEquals(1, store.transactions)
        assertEquals("content://font/new", result.after[CustomFontUriKey])
        assertEquals("new.ttf", result.after[CustomFontNameKey])
        assertEquals(AppFontPreference.CUSTOM.name, result.after[FontPreferenceKey])
        val before = restartSettingValues(result.before, true)
        val after = restartSettingValues(result.after, true)
        val policy = RestartPromptPolicy()
        policy.committed(before, after, before)
        assertEquals(setOf(RestartReason.FONT), policy.reasons)
        assertTrue(policy.visible)
    }
}
