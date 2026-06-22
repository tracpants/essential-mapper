package com.aiden.essentialmapper

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** The three tap slots the Essential Key can be mapped to. */
enum class TapSlot(val key: String, val label: String) {
    SINGLE("single", "Single tap"),
    DOUBLE("double", "Double tap"),
    TRIPLE("triple", "Triple tap"),
}

/** Persisted mapping. A null slot means "do nothing". */
data class KeyMap(
    val single: String? = null,
    val double: String? = null,
    val triple: String? = null,
) {
    fun forSlot(slot: TapSlot): String? = when (slot) {
        TapSlot.SINGLE -> single
        TapSlot.DOUBLE -> double
        TapSlot.TRIPLE -> triple
    }

    /** Used by the service: resolve a package for a given tap count. */
    fun forCount(count: Int): String? = when (count) {
        1 -> single
        2 -> double
        3 -> triple
        else -> null
    }
}

private val Context.dataStore by preferencesDataStore(name = "config")

object Config {
    /**
     * Sentinel stored in a slot to mean "toggle the flashlight" rather than launch an
     * app. Slots hold either a real package name or this token.
     */
    const val ACTION_FLASHLIGHT = "action:flashlight"

    /** Seed the chosen mapping once, on first run, leaving later edits untouched. */
    suspend fun seedDefaultsIfEmpty(ctx: Context) {
        ctx.dataStore.edit { p ->
            val seeded = booleanPreferencesKey("seeded")
            if (p[seeded] != true) {
                p[stringPreferencesKey(TapSlot.SINGLE.key)] = "com.anthropic.claude"
                p[stringPreferencesKey(TapSlot.DOUBLE.key)] = "com.google.android.apps.walletnfcrel"
                p[stringPreferencesKey(TapSlot.TRIPLE.key)] = ACTION_FLASHLIGHT
                p[seeded] = true
            }
        }
    }

    fun flow(ctx: Context): Flow<KeyMap> = ctx.dataStore.data.map { p ->
        KeyMap(
            single = p[stringPreferencesKey(TapSlot.SINGLE.key)],
            double = p[stringPreferencesKey(TapSlot.DOUBLE.key)],
            triple = p[stringPreferencesKey(TapSlot.TRIPLE.key)],
        )
    }

    suspend fun set(ctx: Context, slot: TapSlot, pkg: String?) {
        ctx.dataStore.edit { p ->
            val key = stringPreferencesKey(slot.key)
            if (pkg == null) p.remove(key) else p[key] = pkg
        }
    }

    /** Synchronous read for the accessibility service (re-read per event is fine). */
    fun readBlocking(ctx: Context): KeyMap = runBlocking { flow(ctx).first() }
}
