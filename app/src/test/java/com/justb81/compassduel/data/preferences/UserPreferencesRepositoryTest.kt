package com.justb81.compassduel.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [UserPreferencesRepository] over a real DataStore backed by a temp file — faithful to
 * production serialization and the default/fallback behaviour, without mocking.
 */
class UserPreferencesRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private fun TestScope.buildDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(tempDir, "user.preferences_pb")
        }

    @Test
    fun `theme defaults to SYSTEM when nothing is stored`() = runTest {
        val repo = UserPreferencesRepository(buildDataStore())
        assertEquals(ThemePreference.SYSTEM, repo.themePreference.first())
    }

    @Test
    fun `setThemePreference round-trips`() = runTest {
        val repo = UserPreferencesRepository(buildDataStore())
        repo.setThemePreference(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, repo.themePreference.first())
    }

    @Test
    fun `an unrecognised stored theme falls back to SYSTEM`() = runTest {
        val dataStore = buildDataStore()
        dataStore.edit { it[stringPreferencesKey("theme_preference")] = "not-a-real-theme" }

        val repo = UserPreferencesRepository(dataStore)
        assertEquals(ThemePreference.SYSTEM, repo.themePreference.first())
    }

    @Test
    fun `player name defaults to empty and round-trips`() = runTest {
        val repo = UserPreferencesRepository(buildDataStore())
        assertEquals("", repo.playerName.first())

        repo.setPlayerName("Alice")
        assertEquals("Alice", repo.playerName.first())
    }
}
