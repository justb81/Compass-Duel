package com.justb81.compassduel.data.preferences

/**
 * User-selectable app theme.
 *
 * [SYSTEM] follows the OS dark-mode setting (the historical default); [LIGHT]/[DARK] force a
 * fixed scheme regardless of the system. Persisted by its stable [storageKey] — never persist the
 * enum [ordinal], as reordering the constants would silently corrupt saved values.
 */
enum class ThemePreference(val storageKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** Falls back here for an absent, null, or unrecognised stored value. */
        val DEFAULT = SYSTEM

        /** Maps a persisted [storageKey] back to a constant, defaulting to [DEFAULT]. */
        fun fromStorageKey(key: String?): ThemePreference =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
