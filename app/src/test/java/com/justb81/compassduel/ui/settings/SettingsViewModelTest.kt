package com.justb81.compassduel.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.justb81.compassduel.data.preferences.ThemePreference
import com.justb81.compassduel.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Tests for [SettingsViewModel] — reads the persisted theme and writes selections through. */
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = testScope.backgroundScope) {
            File(tempDir, "user.preferences_pb")
        }

    @Test
    fun `themePreference reflects the stored value`() = testScope.runTest {
        val prefs = UserPreferencesRepository(buildDataStore())
        prefs.setThemePreference(ThemePreference.LIGHT)

        val viewModel = SettingsViewModel(prefs)
        // themePreference is shared WhileSubscribed, so an active collector is needed for the
        // upstream DataStore read to run (mirrors collectAsStateWithLifecycle in the UI).
        val collector = backgroundScope.launch { viewModel.themePreference.collect {} }
        advanceUntilIdle()

        assertEquals(ThemePreference.LIGHT, viewModel.themePreference.value)
        collector.cancel()
    }

    @Test
    fun `onThemeSelected persists the choice`() = testScope.runTest {
        val prefs = UserPreferencesRepository(buildDataStore())
        val viewModel = SettingsViewModel(prefs)

        viewModel.onThemeSelected(ThemePreference.DARK)
        advanceUntilIdle()

        assertEquals(ThemePreference.DARK, prefs.themePreference.first())
    }
}
