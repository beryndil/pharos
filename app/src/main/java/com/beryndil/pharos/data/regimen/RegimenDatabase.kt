package com.beryndil.pharos.data.regimen

import androidx.room.Database
import androidx.room.RoomDatabase
import com.beryndil.pharos.data.regimen.dao.DoseInstanceDao
import com.beryndil.pharos.data.regimen.dao.DoseTransitionDao
import com.beryndil.pharos.data.regimen.dao.MedicationDao
import com.beryndil.pharos.data.regimen.dao.PharmacyDao
import com.beryndil.pharos.data.regimen.dao.PrescriberDao
import com.beryndil.pharos.data.regimen.dao.RefillRecordDao
import com.beryndil.pharos.data.regimen.dao.RestoreDao
import com.beryndil.pharos.data.regimen.dao.ScheduleDao
import com.beryndil.pharos.data.regimen.dao.SchedulePhaseDao
import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.dao.SupplyDao
import com.beryndil.pharos.data.regimen.dao.SupplyRecordDao
import com.beryndil.pharos.data.regimen.entity.DoseInstanceEntity
import com.beryndil.pharos.data.regimen.entity.DoseTransitionEntity
import com.beryndil.pharos.data.regimen.entity.MedicationEntity
import com.beryndil.pharos.data.regimen.entity.PharmacyEntity
import com.beryndil.pharos.data.regimen.entity.PrescriberEntity
import com.beryndil.pharos.data.regimen.entity.RefillRecordEntity
import com.beryndil.pharos.data.regimen.entity.ScheduleEntity
import com.beryndil.pharos.data.regimen.entity.SchedulePhaseEntity
import com.beryndil.pharos.data.regimen.entity.SettingEntity
import com.beryndil.pharos.data.regimen.entity.SupplyEntity
import com.beryndil.pharos.data.regimen.entity.SupplyRecordEntity

/**
 * Room database for the user's regimen: medications, schedules, dose history, refills, settings.
 *
 * Security: in production this database is opened via SQLCipher
 * [net.zetetic.database.sqlcipher.SupportFactory] with a Tink-wrapped 32-byte key.
 * See [RegimenDatabaseFactory.build] for the full construction logic.
 *
 * Append-only invariant: dose history is never overwritten; see [DoseInstanceDao].
 * fallbackToDestructiveMigration() is intentionally absent — dose loss is a safety event.
 *
 * Factory methods, the newer-schema guard, and [NewerSchemaException] live in
 * [RegimenDatabaseFactory] to keep this class clean for Room's KSP annotation processor.
 */
@Database(
    entities = [
        MedicationEntity::class,
        ScheduleEntity::class,
        SchedulePhaseEntity::class,
        DoseInstanceEntity::class,
        DoseTransitionEntity::class,
        RefillRecordEntity::class,
        SettingEntity::class,
        PrescriberEntity::class,
        PharmacyEntity::class,
        SupplyEntity::class,
        SupplyRecordEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class RegimenDatabase : RoomDatabase() {

    abstract fun medicationDao(): MedicationDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun schedulePhaseDao(): SchedulePhaseDao
    abstract fun doseInstanceDao(): DoseInstanceDao
    abstract fun doseTransitionDao(): DoseTransitionDao
    abstract fun refillRecordDao(): RefillRecordDao
    abstract fun settingDao(): SettingDao
    abstract fun prescriberDao(): PrescriberDao
    abstract fun pharmacyDao(): PharmacyDao
    abstract fun supplyDao(): SupplyDao
    abstract fun supplyRecordDao(): SupplyRecordDao

    /** Backup/restore operations — see [RestoreDao] for usage contract. */
    abstract fun restoreDao(): RestoreDao
}
