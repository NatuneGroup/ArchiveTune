package com.lastwave.app.data.local

import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

internal inline fun <reified T> Preferences.readSafely(key: Preferences.Key<T>): T? =
    asMap().entries.firstOrNull { it.key.name == key.name }?.value as? T

internal fun Flow<Preferences>.recoverPreferences(owner: String): Flow<Preferences> =
    catch { error ->
        Log.e(owner, "Preferences unavailable; using safe defaults", error)
        emit(emptyPreferences())
    }
