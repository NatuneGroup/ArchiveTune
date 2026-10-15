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

internal class RecoveringOpenHelper(
    private val create: () -> SupportSQLiteOpenHelper,
    private val shouldRecover: (Throwable) -> Boolean,
    private val repair: (Throwable) -> Unit,
    private val reset: (Throwable) -> Unit,
    private val open: (SupportSQLiteOpenHelper, Boolean) -> SupportSQLiteDatabase = { helper, writable ->
        if (writable) helper.writableDatabase else helper.readableDatabase
    },
) : SupportSQLiteOpenHelper {
    private val lock = Any()
    private var helper = create()
    private var walEnabled = false
    private var opened = false

    override val databaseName: String?
        get() = synchronized(lock) { helper.databaseName }

    override val writableDatabase: SupportSQLiteDatabase
        get() = database(writable = true)

    override val readableDatabase: SupportSQLiteDatabase
        get() = database(writable = false)

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        synchronized(lock) {
            walEnabled = enabled
            helper.setWriteAheadLoggingEnabled(enabled)
        }
    }

    private fun replacement(): SupportSQLiteOpenHelper = create().also {
        it.setWriteAheadLoggingEnabled(walEnabled)
    }

    private fun database(writable: Boolean): SupportSQLiteDatabase = synchronized(lock) {
        if (opened) {
            return@synchronized if (writable) helper.writableDatabase else helper.readableDatabase
        }
        val database = try {
            open(helper, writable)
        } catch (error: Exception) {
            if (!shouldRecover(error)) throw error
            // Serialize repair with every opener so no query can see a partially
            // repaired file or reopen it between close and replacement.
            runCatching { helper.close() }
            repair(error)
            helper = replacement()
            try {
                open(helper, writable)
            } catch (retryError: Exception) {
                runCatching { helper.close() }
                reset(retryError)
                helper = replacement()
                open(helper, writable)
            }
        }
        opened = true
        database
    }

    override fun close() {
        synchronized(lock) {
            helper.close()
            opened = false
        }
    }
}
