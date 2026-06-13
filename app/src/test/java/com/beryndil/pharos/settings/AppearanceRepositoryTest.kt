package com.beryndil.pharos.settings

import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import com.beryndil.pharos.ui.theme.scaleTypography
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [AppearanceRepository], [ThemeMode], [TextScale], and [scaleTypography].
 *
 * Pure JVM — no Robolectric needed.
 *
 * Coverage:
 *  1. ThemeMode and TextScale round-trip through the persistence layer.
 *  2. Unknown stored values fall back to safe defaults.
 *  3. [scaleTypography] preserves sp units and applies the scale correctly.
 *  4. The live [Flow]s emit the correct value after an upsert.
 */
class AppearanceRepositoryTest {

    // ── ThemeMode persistence ─────────────────────────────────────────────────

    @Test
    fun setThemeMode_system_persistsAndReadsBack() = runTest {
        val (repo, _) = makeRepo()
        repo.setThemeMode(ThemeMode.SYSTEM)
        val read = repo.observeThemeMode().first()
        assertEquals(ThemeMode.SYSTEM, read)
    }

    @Test
    fun setThemeMode_light_persistsAndReadsBack() = runTest {
        val (repo, _) = makeRepo()
        repo.setThemeMode(ThemeMode.LIGHT)
        val read = repo.observeThemeMode().first()
        assertEquals(ThemeMode.LIGHT, read)
    }

    @Test
    fun setThemeMode_dark_persistsAndReadsBack() = runTest {
        val (repo, _) = makeRepo()
        repo.setThemeMode(ThemeMode.DARK)
        val read = repo.observeThemeMode().first()
        assertEquals(ThemeMode.DARK, read)
    }

    @Test
    fun observeThemeMode_emitsSystem_whenKeyAbsent() = runTest {
        val (repo, _) = makeRepo()
        val read = repo.observeThemeMode().first()
        assertEquals(ThemeMode.SYSTEM, read)
    }

    @Test
    fun themeModeFromStoredValue_fallsBackToSystem_onUnknownValue() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue("GARBAGE"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue(null))
    }

    // ── TextScale persistence ─────────────────────────────────────────────────

    @Test
    fun setTextScale_default_persistsAndReadsBack() = runTest {
        val (repo, _) = makeRepo()
        repo.setTextScale(TextScale.DEFAULT)
        val read = repo.observeTextScale().first()
        assertEquals(TextScale.DEFAULT, read)
    }

    @Test
    fun setTextScale_large_persistsAndReadsBack() = runTest {
        val (repo, _) = makeRepo()
        repo.setTextScale(TextScale.LARGE)
        val read = repo.observeTextScale().first()
        assertEquals(TextScale.LARGE, read)
    }

    @Test
    fun setTextScale_extraLarge_persistsAndReadsBack() = runTest {
        val (repo, _) = makeRepo()
        repo.setTextScale(TextScale.EXTRA_LARGE)
        val read = repo.observeTextScale().first()
        assertEquals(TextScale.EXTRA_LARGE, read)
    }

    @Test
    fun setTextScale_largest_persistsAndReadsBack() = runTest {
        val (repo, _) = makeRepo()
        repo.setTextScale(TextScale.LARGEST)
        val read = repo.observeTextScale().first()
        assertEquals(TextScale.LARGEST, read)
    }

    @Test
    fun observeTextScale_emitsDefault_whenKeyAbsent() = runTest {
        val (repo, _) = makeRepo()
        val read = repo.observeTextScale().first()
        assertEquals(TextScale.DEFAULT, read)
    }

    @Test
    fun textScaleFromStoredValue_fallsBackToDefault_onUnknownValue() {
        assertEquals(TextScale.DEFAULT, TextScale.fromStoredValue("GARBAGE"))
        assertEquals(TextScale.DEFAULT, TextScale.fromStoredValue(null))
    }

    // ── ThemeMode → darkTheme mapping ─────────────────────────────────────────

    @Test
    fun themeModeStoredValues_areDistinct() {
        val values = ThemeMode.entries.map { it.storedValue }
        assertEquals(ThemeMode.entries.size, values.toSet().size)
    }

    // ── TextScale factor values ───────────────────────────────────────────────

    @Test
    fun textScaleFactors_matchDocumentedValues() {
        assertEquals(1.0f, TextScale.DEFAULT.factor)
        assertEquals(1.15f, TextScale.LARGE.factor)
        assertEquals(1.3f, TextScale.EXTRA_LARGE.factor)
        assertEquals(1.5f, TextScale.LARGEST.factor)
    }

    // ── scaleTypography ───────────────────────────────────────────────────────

    @Test
    fun scaleTypography_factor1_returnsDefaultFontSizes() {
        // At factor 1.0 the font sizes must equal the Material3 defaults.
        val default = scaleTypography(1.0f)
        val base = androidx.compose.material3.Typography()
        assertEquals(base.bodyLarge.fontSize, default.bodyLarge.fontSize)
        assertEquals(base.titleMedium.fontSize, default.titleMedium.fontSize)
        assertEquals(base.labelSmall.fontSize, default.labelSmall.fontSize)
    }

    @Test
    fun scaleTypography_factorAbove1_increasesFontSizes() {
        val scaled = scaleTypography(1.5f)
        val base = androidx.compose.material3.Typography()
        // Each style's fontSize should be ≈ 1.5× the base.
        assertEquals(
            base.bodyLarge.fontSize * 1.5f,
            scaled.bodyLarge.fontSize,
        )
        assertEquals(
            base.displayLarge.fontSize * 1.5f,
            scaled.displayLarge.fontSize,
        )
        assertEquals(
            base.labelSmall.fontSize * 1.5f,
            scaled.labelSmall.fontSize,
        )
    }

    @Test
    fun scaleTypography_preservesSpUnit() {
        val scaled = scaleTypography(1.3f)
        // sp × Float = sp; the result must stay in sp so it stacks with the system font scale.
        assertEquals(
            androidx.compose.ui.unit.TextUnitType.Sp,
            scaled.bodyMedium.fontSize.type,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeRepo(): Pair<AppearanceRepository, ReactiveSettingDao> {
        val dao = ReactiveSettingDao()
        return AppearanceRepository(dao) to dao
    }
}

/**
 * Reactive in-memory [SettingDao] for appearance tests.
 *
 * [observeByKey] returns a [MutableStateFlow] that is updated on every [upsert], so tests
 * that call [setThemeMode]/[setTextScale] and then collect from [observeThemeMode]/
 * [observeTextScale] see the correct value immediately.
 */
private class ReactiveSettingDao : SettingDao {

    private val store = mutableMapOf<String, SettingEntity>()

    // Per-key StateFlows so observeByKey callers see updates in the same coroutine scope.
    private val keyFlows = mutableMapOf<String, MutableStateFlow<SettingEntity?>>()

    override suspend fun upsert(setting: SettingEntity) {
        store[setting.key] = setting
        keyFlows.getOrPut(setting.key) { MutableStateFlow(null) }.value = setting
        // Also update the all-flow snapshot in case a test uses observeAll().
        _all.value = store.values.toList()
    }

    override suspend fun get(key: String): SettingEntity? = store[key]

    private val _all = MutableStateFlow<List<SettingEntity>>(emptyList())
    override fun observeAll(): Flow<List<SettingEntity>> = _all.asStateFlow()

    override suspend fun getAll(): List<SettingEntity> = store.values.toList()

    override fun observeByKey(key: String): Flow<SettingEntity?> =
        keyFlows.getOrPut(key) { MutableStateFlow(store[key]) }.asStateFlow()
}
