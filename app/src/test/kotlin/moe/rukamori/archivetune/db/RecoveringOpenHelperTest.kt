/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveringOpenHelperTest {
    private val database = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, _ -> error("Unexpected database operation: ${method.name}") } as SupportSQLiteDatabase

    @Test
    fun constructionDoesNotOpenTheDatabase() {
        var opens = 0
        val helper = RecoveringOpenHelper(
            create = { FakeHelper { opens++; database } },
            shouldRecover = { false },
            repair = { error("Unexpected repair") },
            reset = { error("Unexpected reset") },
        )
        helper.setWriteAheadLoggingEnabled(true)
        assertEquals("song.db", helper.databaseName)
        assertEquals(0, opens)
        assertSame(database, helper.writableDatabase)
        assertEquals(1, opens)
    }

    @Test
    fun schemaMismatchClosesThenRepairsBeforeRetryingWithWalPreserved() {
        val events = mutableListOf<String>()
        val helpers = mutableListOf<FakeHelper>()
        val helper = RecoveringOpenHelper(
            create = {
                val attempt = helpers.size
                FakeHelper(
                    onClose = { events += "close-$attempt" },
                    read = {
                        events += "open-$attempt"
                        if (attempt == 0) error("schema mismatch")
                        database
                    },
                ).also(helpers::add)
            },
            shouldRecover = { it.message == "schema mismatch" },
            repair = { events += "repair" },
            reset = { error("A repaired database must not be deleted") },
        )
        helper.setWriteAheadLoggingEnabled(true)
        assertSame(database, helper.readableDatabase)
        assertEquals(listOf("open-0", "close-0", "repair", "open-1"), events)
        assertTrue(helpers.all { it.walEnabled })
    }

    @Test
    fun existingResetFallbackRunsOnlyAfterRepairAndRetryFail() {
        var creations = 0
        var repairs = 0
        var resets = 0
        val helper = RecoveringOpenHelper(
            create = {
                val attempt = creations++
                FakeHelper {
                    if (attempt < 2) error("schema mismatch")
                    database
                }
            },
            shouldRecover = { true },
            repair = { repairs++ },
            reset = { resets++ },
        )
        assertSame(database, helper.writableDatabase)
        assertEquals(3, creations)
        assertEquals(1, repairs)
        assertEquals(1, resets)
    }

    @Test
    fun unrelatedOpenFailureNeverRepairsOrDeletesUserData() {
        val failure = IllegalStateException("disk full")
        val helper = RecoveringOpenHelper(
            create = { FakeHelper { throw failure } },
            shouldRecover = { false },
            repair = { error("Unexpected repair") },
            reset = { error("Unexpected reset") },
        )
        assertSame(failure, assertThrows(IllegalStateException::class.java) { helper.writableDatabase })
    }

    @Test(timeout = 10_000)
    fun concurrentFirstQueriesShareOneRepair() {
        var creations = 0
        var repairs = 0
        val helper = RecoveringOpenHelper(
            create = {
                val attempt = creations++
                FakeHelper {
                    if (attempt == 0) error("schema mismatch")
                    database
                }
            },
            shouldRecover = { true },
            repair = { repairs++ },
            reset = { error("Unexpected reset") },
        )
        val workers = Executors.newFixedThreadPool(4)
        try {
            val readers = List(20) { workers.submit<SupportSQLiteDatabase> { helper.writableDatabase } }
            readers.forEach { assertSame(database, it.get(2, TimeUnit.SECONDS)) }
            assertEquals(2, creations)
            assertEquals(1, repairs)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class FakeHelper(
        private val onClose: () -> Unit = {},
        private val read: () -> SupportSQLiteDatabase,
    ) : SupportSQLiteOpenHelper {
        var walEnabled = false
        override val databaseName = "song.db"
        override val writableDatabase get() = read()
        override val readableDatabase get() = read()
        override fun close() = onClose()
        override fun setWriteAheadLoggingEnabled(enabled: Boolean) { walEnabled = enabled }
    }
}
