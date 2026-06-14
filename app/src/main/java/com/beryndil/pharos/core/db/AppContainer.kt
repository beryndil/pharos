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
import com.beryndil.pharos.backup.AutoBackupManager
import com.beryndil.pharos.backup.BackupRepository
import com.beryndil.pharos.data.dose.DoseRepository
import com.beryndil.pharos.data.drugref.DrugDbUpdater
import com.beryndil.pharos.data.drugref.DrugDbUpdateWorker
import com.beryndil.pharos.data.drugref.DrugLabelRepository
import com.beryndil.pharos.data.drugref.DrugRefDatabase
import com.beryndil.pharos.data.drugref.DrugRefDatabaseFactory
import com.beryndil.pharos.data.drugref.ManifestVerifier
import com.beryndil.pharos.data.drugref.OpenFdaDrugLabelService
import com.beryndil.pharos.contacts.ContactRepository
import com.beryndil.pharos.data.medication.MedicationRepository
import com.beryndil.pharos.medication.export.MedListPdfExporter
import com.beryndil.pharos.data.regimen.RegimenDatabase
import com.beryndil.pharos.data.regimen.RegimenDatabaseFactory
import com.beryndil.pharos.data.schedule.ScheduleRepository
import com.beryndil.pharos.dose.AndroidDoseTransitionScheduler
import com.beryndil.pharos.dose.DoseStateMachine
import com.beryndil.pharos.dose.DoseTransitionScheduler
import com.beryndil.pharos.onboarding.OnboardingRepository
import com.beryndil.pharos.settings.AppearanceRepository
import com.beryndil.pharos.settings.UserProfileRepository
import com.beryndil.pharos.refill.AndroidRefillNotifier
import com.beryndil.pharos.refill.LowSupplyCheckWorker
import com.beryndil.pharos.refill.RefillNotifier
import com.beryndil.pharos.refill.RefillRepository
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
        // SQLCipher's native lib must be loaded before the encrypted DB opens, or nativeOpen
        // throws UnsatisfiedLinkError. The net.zetetic:sqlcipher-android artifact has no loadLibs()
        // helper and does not self-load. Done here (production open path) rather than in
        // Application.onCreate so Robolectric unit tests — which use plain SQLite (DECISIONS.md A9)
        // and never reach this lazy block — don't try (and fail) to load a host-JVM native lib.
        System.loadLibrary("sqlcipher")
        val passphrase = passphraseProvider.getOrCreatePassphrase(applicationContext)
        val factory = SupportOpenHelperFactory(passphrase)
        // Pass the passphrase to build() so enforceSchemaVersion() can open the encrypted file
        // via the SQLCipher API. Zero AFTER build() returns; SupportOpenHelperFactory already
        // holds its own copy so the factory is unaffected by zeroing here.
        val db = RegimenDatabaseFactory.build(applicationContext, factory, passphrase)
        passphrase.fill(0)
        db
    }

    val drugRefDatabase: DrugRefDatabase by lazy {
        DrugRefDatabaseFactory.build(applicationContext)
    }

    /** Repository for medication identity & entry (Slice 2). Bridges both databases. */
    val medicationRepository: MedicationRepository by lazy {
        MedicationRepository(
            medicationDao = regimenDatabase.medicationDao(),
            drugSearchDao = drugRefDatabase.drugSearchDao(),
            ingredientMapDao = drugRefDatabase.ingredientMapDao(),
            doseTransitionDao = regimenDatabase.doseTransitionDao(),
            doseInstanceDao = regimenDatabase.doseInstanceDao(),
            schedulePhaseDao = regimenDatabase.schedulePhaseDao(),
            scheduleDao = regimenDatabase.scheduleDao(),
            refillRecordDao = regimenDatabase.refillRecordDao(),
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

    /**
     * Repository for saved prescriber and pharmacy contacts (V1.3-F1).
     * All data is local (Law 4 — no off-device transmission).
     */
    val contactRepository: ContactRepository by lazy {
        ContactRepository(
            prescriberDao = regimenDatabase.prescriberDao(),
            pharmacyDao = regimenDatabase.pharmacyDao(),
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

    // ── Dose state machine (Slice 5, spec §2.6 / §2.7 / §2.8) ─────────────────

    /** Schedules the per-dose miss-window and escalation re-alert alarms (app-closed safe). */
    val doseTransitionScheduler: DoseTransitionScheduler by lazy {
        AndroidDoseTransitionScheduler(applicationContext)
    }

    /**
     * The single authority for every dose transition (D2/D3, escalation, append-only history).
     * Used as both the [com.beryndil.pharos.alarm.DoseActionHandler] (Take/Snooze/Skip) and the
     * [com.beryndil.pharos.alarm.DoseDueListener] (arms timers when a dose enters DUE).
     */
    val doseStateMachine: DoseStateMachine by lazy {
        DoseStateMachine(
            doseInstanceDao = regimenDatabase.doseInstanceDao(),
            doseTransitionDao = regimenDatabase.doseTransitionDao(),
            medicationDao = regimenDatabase.medicationDao(),
            scheduleDao = regimenDatabase.scheduleDao(),
            transitionScheduler = doseTransitionScheduler,
            notifier = doseNotifier,
        )
    }

    /** Read/act facade for the today and per-med history UIs (Slice 5). */
    val doseRepository: DoseRepository by lazy {
        DoseRepository(
            doseInstanceDao = regimenDatabase.doseInstanceDao(),
            doseTransitionDao = regimenDatabase.doseTransitionDao(),
            medicationDao = regimenDatabase.medicationDao(),
            scheduleDao = regimenDatabase.scheduleDao(),
            stateMachine = doseStateMachine,
        )
    }

    // ── Onboarding & reliability dashboard (Slice 6) ──────────────────────────

    /**
     * Persists the onboarding-complete flag. [com.beryndil.pharos.MainActivity] reads this once
     * on start to decide whether to show onboarding or the Today screen.
     */
    val onboardingRepository: OnboardingRepository by lazy {
        OnboardingRepository(regimenDatabase.settingDao())
    }

    /**
     * Persists and exposes the user's appearance preferences (theme mode and in-app text scale).
     * [com.beryndil.pharos.MainActivity] collects these as state to recompose [PharosTheme] live.
     */
    val appearanceRepository: AppearanceRepository by lazy {
        AppearanceRepository(regimenDatabase.settingDao())
    }

    /** Persists the user's personal profile for PDF export headers (all fields optional). */
    val userProfileRepository: UserProfileRepository by lazy {
        UserProfileRepository(regimenDatabase.settingDao())
    }

    // ── Refill tracking (Slice 7, spec §2.9) ─────────────────────────────────

    /**
     * Refill repository: tracks per-med supply counts, derives days-until-empty, and provides
     * the list of low-supply medications for the WorkManager check job.
     *
     * Architecturally isolated from the dose state machine and alarm engine — a zero supply
     * count can NEVER suppress a dose reminder (zero-supply invariant, Law 1, spec §2.9).
     */
    val refillRepository: RefillRepository by lazy {
        RefillRepository(
            refillRecordDao = regimenDatabase.refillRecordDao(),
            medicationDao = regimenDatabase.medicationDao(),
            scheduleDao = regimenDatabase.scheduleDao(),
            schedulePhaseDao = regimenDatabase.schedulePhaseDao(),
        )
    }

    /**
     * Posts low-supply alerts exclusively on the REFILL channel — never on the sacred dose
     * channel (Law 1, §2.8).
     */
    val refillNotifier: RefillNotifier by lazy {
        AndroidRefillNotifier(applicationContext)
    }

    // ── Drug reference (Slice 8, spec §2.10 / §3.2 / §3.5) ──────────────────

    /**
     * Cache-aside repository for drug label sections (side effects + drug interactions).
     * Fetches from openFDA on demand; caches locally forever after (spec §2.10, Law 9).
     */
    val drugLabelRepository: DrugLabelRepository by lazy {
        DrugLabelRepository(
            labelCacheDao = drugRefDatabase.labelCacheDao(),
            drugLabelService = OpenFdaDrugLabelService(),
        )
    }

    /**
     * CDN pipeline: downloads, Ed25519-verifies, and atomically swaps the drug-reference DB
     * (spec §3.2, §3.5, Standards §6). WorkManager wraps this in [DrugDbUpdateWorker].
     * CDN base URL is a placeholder until Dave provisions Backblaze B2 + Cloudflare (TODO.md).
     */
    val drugDbUpdater: DrugDbUpdater by lazy {
        DrugDbUpdater(
            context = applicationContext,
            cdnBaseUrl = DrugDbUpdateWorker.CDN_BASE_URL,
            manifestVerifier = ManifestVerifier.production(),
        )
    }

    // ── Backup / restore / export (Slice 9, spec §2.12) ──────────────────────

    /**
     * Shared PDF renderer for both the Backup export screen (SAF URI) and the Today-screen
     * "Email to doctor" action (cache file). Single instance; no shared mutable state.
     */
    val medListPdfExporter: MedListPdfExporter by lazy {
        MedListPdfExporter(db = regimenDatabase)
    }

    /**
     * Orchestrates encrypted backup creation, restore, and plaintext export.
     * Law 7: backup/restore are free forever; Law 4: only writes to user-chosen SAF URIs.
     *
     * Post-restore callback (A2-1 + A2-2): after a successful restore, re-arm exact alarms
     * (so the restored regimen's dose schedule fires) and REPLACE both WorkManager jobs
     * (so they pick up the new regimen state rather than stale pre-restore data).
     *
     * [alarmCoordinator] is lazy, so referencing it here does not create a cyclic init.
     */
    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            db = regimenDatabase,
            context = applicationContext,
            pdfExporter = medListPdfExporter,
            userProfileRepository = userProfileRepository,
            onRestoreComplete = {
                alarmCoordinator.onReRegistration("restore")
                LowSupplyCheckWorker.scheduleAfterRestore(applicationContext)
                DrugDbUpdateWorker.scheduleAfterRestore(applicationContext)
            },
        )
    }

    /**
     * Manages automatic encrypted backups to Downloads/Pharos/pharos-auto-backup.pbk.
     * Key derived from ANDROID_ID — survives uninstall+reinstall with the same signing key.
     * Law 4: stays on device. Law 7: zero-user-action recovery after accidental uninstall.
     */
    val autoBackupManager: AutoBackupManager by lazy {
        AutoBackupManager(context = applicationContext, repository = backupRepository)
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
            doseDueListener = doseStateMachine,
        )
    }
}
