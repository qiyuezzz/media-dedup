package com.example.mediadedup.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    private val LANGUAGE_KEY = stringPreferencesKey("language_preference")
    private val NEAR_DUPLICATE_KEY = booleanPreferencesKey("near_duplicate_enabled")

    val languagePreference: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: ""
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language
        }
    }

    /** Whether the perceptual-hash near-duplicate pass runs during scans. Default off. */
    val nearDuplicateEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NEAR_DUPLICATE_KEY] ?: false
    }

    suspend fun setNearDuplicateEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NEAR_DUPLICATE_KEY] = enabled
        }
    }
}

