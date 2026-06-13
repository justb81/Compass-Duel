package com.justb81.compassduel.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists user preferences (theme choice and last-used player name) via Jetpack DataStore.
 *
 * The single backing [DataStore] is supplied by Hilt so there is exactly one instance per process.
 * Read flows swallow [IOException]s by emitting empty preferences, so a transient disk error
 * surfaces as the default value rather than crashing collectors.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** The persisted theme choice, defaulting to [ThemePreference.DEFAULT]. */
    val themePreference: Flow<ThemePreference> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> ThemePreference.fromStorageKey(prefs[Keys.THEME]) }

    /** The last-used player name, defaulting to an empty string when none is stored. */
    val playerName: Flow<String> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> prefs[Keys.PLAYER_NAME].orEmpty() }

    /** Persists the selected [theme]. */
    suspend fun setThemePreference(theme: ThemePreference) {
        dataStore.edit { prefs -> prefs[Keys.THEME] = theme.storageKey }
    }

    /** Persists the last-used player [name]. */
    suspend fun setPlayerName(name: String) {
        dataStore.edit { prefs -> prefs[Keys.PLAYER_NAME] = name }
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_preference")
        val PLAYER_NAME = stringPreferencesKey("player_name")
    }
}
