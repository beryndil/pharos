package com.beryndil.pharos.core.db

import android.content.Context
import com.beryndil.pharos.alarm.AlarmCoordinator
import com.beryndil.pharos.alarm.AlarmScheduler
import com.beryndil.pharos.alarm.AndroidAlarmScheduler
import com.beryndil.pharos.alarm.DoseNotifier
import com.beryndil.pharos.alarm.FullScreenDoseNotifier
import com.beryndil.pharos.alarm.ReliabilityLog
import com.beryndil.pharos.alarm.SettingsReliabilityLog
import com.beryndil.pharos.core.crypto.PassphraseProvider
import com.beryndil.pharos.core.crypto.TinkPassphraseProvider
import com.beryndil.pharos.data.drugref.DrugRefDatabase
import com.beryndil.pharos.data.drugref.DrugRefDatabaseFactory
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.RegimenDatabaseFactory
import com.beryndil.pharos.data.schedule.ScheduleRepository
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Manual dependency injection container held by [com.beryndil.pharos.PharosApplication].
 *
 * Holds singleton instances of both Room databases and any shared dependencies.
 * All creation is lazy — databases open on first access, not at app startup.
 *
 * Pattern: manual constructor DI with no Hilt/Dagger (DECISIONS.md A1).
 *
 * Security:
 *  [regimenDatabase] is opened via SQLCipher [SupportFactory] with a Tink-wrapped key
 *  (Standards §6 LAUNCH-BLOCKER). The passphrase byte array is zeroed immediately after
 *  passing to [SupportFactory].
 *
 * [drugRefDatabase] is plaintext — public reference data, integrity-checked on CDN download.
 */
class AppContainer(private val applicationContext: Context) {

    private val passphraseProvider: PassphraseProvider = TinkPassphraseProvider(applicationContext)

    val regimenDatabase: RegimenDatabase by lazy {
        val passphrase = passphraseProvider.getOrCreatePassphrase(applicationContext)
        val factory = SupportOpenHelperFactory(passphrase)
        passphrase.fill(0) // zero immediately; SupportOpenHelperFactory has its own copy
        RegimenDatabaseFactory.build(applicationContext, factory)
    }

    val drugRefDatabase: DrugRefDatabase by lazy {
        DrugRefDatabaseFactory.build(applicationContext)
    }

    /** Repository for medication identity & entry (Slice 2). Bridges both databases. */
    val medicationRepository: MedicationRepository by lazy {
        MedicationRepository(
            medicationDao = regimenDatabase.medicationDao(),
            productDao = drugRefDatabase.productDao(),
            ingredientDao = drugRefDatabase.ingredientDao(),
        )
    }

    /** Repository for schedule management (Slice 3). */
    val scheduleRepository: ScheduleRepository by lazy {
        ScheduleRepository(
            scheduleDao = regimenDatabase.scheduleDao(),
            schedulePhaseDao = regimenDatabase.schedulePhaseDao(),
            doseInstanceDao = regimenDatabase.doseInstanceDao(),
        )
    }

    // ── Alarm engine (Slice 4, spec §3.4) ────────────────────────────────────

    /** Schedules exact alarms over [android.app.AlarmManager] with windowed fallback. */
    val alarmScheduler: AlarmScheduler by lazy { AndroidAlarmScheduler(applicationContext) }

    /** Posts the full-screen dose-due alert on the (sacred) dose channel. */
    val doseNotifier: DoseNotifier by lazy { FullScreenDoseNotifier(applicationContext) }

    /** Records reliability facts the Slice 6 dashboard reads. */
    val reliabilityLog: ReliabilityLog by lazy {
        SettingsReliabilityLog(regimenDatabase.settingDao())
    }

    /** Single-fire-and-reschedule coordinator: the brain of the alarm engine. */
    val alarmCoordinator: AlarmCoordinator by lazy {
        AlarmCoordinator(
            scheduler = alarmScheduler,
            notifier = doseNotifier,
            doseInstanceDao = regimenDatabase.doseInstanceDao(),
            medicationDao = regimenDatabase.medicationDao(),
            scheduleRepository = scheduleRepository,
            reliabilityLog = reliabilityLog,
        )
    }
}
