package com.justb81.compassduel.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justb81.compassduel.data.preferences.ThemePreference
import com.justb81.compassduel.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes the persisted app-wide [ThemePreference] and lets the user change it.
 *
 * Backed by the singleton [UserPreferencesRepository], so every consumer (the app root that drives
 * [com.justb81.compassduel.ui.theme.CompassDuelTheme] and the home-screen picker) observes the same
 * value without needing to share a single ViewModel instance.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    /** The current theme choice, defaulting to [ThemePreference.DEFAULT] until DataStore loads. */
    val themePreference: StateFlow<ThemePreference> = prefs.themePreference.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ThemePreference.DEFAULT,
    )

    /** Persists the user's theme selection. */
    fun onThemeSelected(theme: ThemePreference) {
        viewModelScope.launch { prefs.setThemePreference(theme) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
