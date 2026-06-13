package com.beryndil.pharos.settings

import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists and exposes the user's appearance preferences (theme mode and in-app text scale).
 *
 * Backed by [SettingDao] — same pattern as [com.beryndil.pharos.onboarding.OnboardingRepository].
 * Both preferences expose live [Flow]s so [com.beryndil.pharos.MainActivity] can recompose the
 * theme without a restart (Law 10 — accessibility settings must apply immediately).
 *
 * Thread-safety: all suspend writes run on the caller's dispatcher; Room ensures the DB
 * operations are safe from any thread.
 */
open class AppearanceRepository(private val settingDao: SettingDao) {

    /** Live stream of the persisted [ThemeMode]; emits [ThemeMode.SYSTEM] when not yet set. */
    fun observeThemeMode(): Flow<ThemeMode> =
        settingDao.observeByKey(KEY_THEME).map { entity ->
            ThemeMode.fromStoredValue(entity?.value)
        }

    /** Live stream of the persisted [TextScale]; emits [TextScale.DEFAULT] when not yet set. */
    fun observeTextScale(): Flow<TextScale> =
        settingDao.observeByKey(KEY_TEXT_SCALE).map { entity ->
            TextScale.fromStoredValue(entity?.value)
        }

    open suspend fun setThemeMode(mode: ThemeMode, nowMs: Long = System.currentTimeMillis()) {
        settingDao.upsert(
            SettingEntity(key = KEY_THEME, value = mode.storedValue, updatedAtEpochMs = nowMs),
        )
    }

    open suspend fun setTextScale(scale: TextScale, nowMs: Long = System.currentTimeMillis()) {
        settingDao.upsert(
            SettingEntity(key = KEY_TEXT_SCALE, value = scale.storedValue, updatedAtEpochMs = nowMs),
        )
    }

    companion object {
        const val KEY_THEME = "appearance.theme"
        const val KEY_TEXT_SCALE = "appearance.textScale"
    }
}

/**
 * The three possible theme modes persisted under [AppearanceRepository.KEY_THEME].
 *
 * [SYSTEM] follows the OS dark/light setting (default).
 * [LIGHT] forces light theme regardless of OS setting.
 * [DARK] forces dark theme regardless of OS setting.
 */
enum class ThemeMode(val storedValue: String) {
    SYSTEM("SYSTEM"),
    LIGHT("LIGHT"),
    DARK("DARK"),
    ;

    companion object {
        /** Returns the matching enum value, or [SYSTEM] if the stored value is unrecognised. */
        fun fromStoredValue(value: String?): ThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/**
 * The four in-app text size steps persisted under [AppearanceRepository.KEY_TEXT_SCALE].
 *
 * The [factor] is multiplied against each MaterialTheme typography style's fontSize (in sp)
 * inside [com.beryndil.pharos.ui.theme.PharosTheme]. This stacks multiplicatively with the
 * system font scale setting — it never replaces it (Law 10 requirement).
 *
 * Numeric values chosen to give noticeable steps without making the default sizes unreadably
 * large on a standard-density device:
 *   Default: 1.0× (no change), Large: 1.15×, Extra large: 1.3×, Largest: 1.5×.
 */
enum class TextScale(val storedValue: String, val factor: Float) {
    DEFAULT("1.0", 1.0f),
    LARGE("1.15", 1.15f),
    EXTRA_LARGE("1.3", 1.3f),
    LARGEST("1.5", 1.5f),
    ;

    companion object {
        /** Returns the matching enum value, or [DEFAULT] if the stored value is unrecognised. */
        fun fromStoredValue(value: String?): TextScale =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}
