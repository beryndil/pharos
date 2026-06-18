package com.beryndil.pharos.supply

import com.beryndil.pharos.data.regimen.dao.SupplyDao
import com.beryndil.pharos.data.regimen.dao.SupplyRecordDao
import com.beryndil.pharos.data.regimen.entity.SupplyEntity
import com.beryndil.pharos.data.regimen.entity.SupplyEventType
import com.beryndil.pharos.data.regimen.entity.SupplyRecordEntity
import com.beryndil.pharos.data.regimen.entity.SupplyStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Repository for non-drug supply tracking.
 *
 * All writes produce append-only [SupplyRecordEntity] rows — no event is ever modified or
 * deleted. Current quantity is always [SupplyRecordEntity.quantityAfter] of the latest row.
 *
 * Zero-supply invariant (Law 1): this repository has no reference to the alarm engine or
 * dose state machine. Supply counts NEVER affect dose reminder delivery.
 */
class SupplyRepository(
    private val supplyDao: SupplyDao,
    private val supplyRecordDao: SupplyRecordDao,
) {

    // ── Observation ───────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAllSummaries(): Flow<List<SupplySummary>> =
        supplyDao.observeActive().flatMapLatest { supplies ->
            if (supplies.isEmpty()) {
                flow { emit(emptyList()) }
            } else {
                val recordFlows = supplies.map { supply ->
                    supplyRecordDao.observeBySupply(supply.id).map { records ->
                        toSummary(supply, records.firstOrNull()?.quantityAfter)
                    }
                }
                combine(recordFlows) { it.toList() }
            }
        }

    fun observeSupplySummary(supplyId: String): Flow<SupplySummary> =
        combine(
            flow { emit(supplyDao.getById(supplyId)) },
            supplyRecordDao.observeBySupply(supplyId),
        ) { supply, records ->
            if (supply == null) {
                SupplySummary(
                    supplyId = supplyId, supplyName = "", unit = "", quantityOnHand = null,
                    lowThreshold = 0, noRecordYet = true, isLowSupply = false,
                    prescriberName = null, prescriberPhone = null,
                    pharmacyName = null, pharmacyPhone = null, notes = null,
                    status = SupplyStatus.ACTIVE.name,
                )
            } else {
                toSummary(supply, records.firstOrNull()?.quantityAfter)
            }
        }

    fun observeRecords(supplyId: String) = supplyRecordDao.observeBySupply(supplyId)

    suspend fun getById(supplyId: String): SupplyEntity? = supplyDao.getById(supplyId)

    // ── Write: supply lifecycle ───────────────────────────────────────────────

    suspend fun addSupply(
        name: String,
        unit: String,
        prescriberName: String?,
        prescriberPhone: String?,
        pharmacyName: String?,
        pharmacyPhone: String?,
        lowThreshold: Int,
        notes: String?,
        initialCount: Int?,
        nowMs: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        supplyDao.insert(
            SupplyEntity(
                id = id,
                name = name.trim(),
                unit = unit.trim(),
                prescriberName = prescriberName?.trim()?.ifBlank { null },
                prescriberPhone = prescriberPhone?.trim()?.ifBlank { null },
                pharmacyName = pharmacyName?.trim()?.ifBlank { null },
                pharmacyPhone = pharmacyPhone?.trim()?.ifBlank { null },
                lowThreshold = lowThreshold,
                notes = notes?.trim()?.ifBlank { null },
                status = SupplyStatus.ACTIVE.name,
                createdAtEpochMs = nowMs,
            ),
        )
        if (initialCount != null && initialCount > 0) {
            supplyRecordDao.insert(
                SupplyRecordEntity(
                    id = UUID.randomUUID().toString(),
                    supplyId = id,
                    quantityDelta = initialCount,
                    quantityAfter = initialCount,
                    eventType = SupplyEventType.INITIAL.name,
                    notes = null,
                    createdAtEpochMs = nowMs,
                ),
            )
        }
        return id
    }

    suspend fun updateSupply(supply: SupplyEntity) = supplyDao.update(supply)

    suspend fun endSupply(supplyId: String) =
        supplyDao.updateStatus(supplyId, SupplyStatus.ENDED.name)

    // ── Write: append-only record operations ─────────────────────────────────

    suspend fun logUsage(supplyId: String, quantity: Int, notes: String?, nowMs: Long) {
        val current = supplyRecordDao.getLatest(supplyId)?.quantityAfter ?: 0
        val after = maxOf(0, current - quantity)
        supplyRecordDao.insert(
            SupplyRecordEntity(
                id = UUID.randomUUID().toString(),
                supplyId = supplyId,
                quantityDelta = -quantity,
                quantityAfter = after,
                eventType = SupplyEventType.USAGE.name,
                notes = notes?.trim()?.ifBlank { null },
                createdAtEpochMs = nowMs,
            ),
        )
    }

    suspend fun logRestock(supplyId: String, quantity: Int, notes: String?, nowMs: Long) {
        val current = supplyRecordDao.getLatest(supplyId)?.quantityAfter ?: 0
        val after = current + quantity
        supplyRecordDao.insert(
            SupplyRecordEntity(
                id = UUID.randomUUID().toString(),
                supplyId = supplyId,
                quantityDelta = quantity,
                quantityAfter = after,
                eventType = SupplyEventType.RESTOCK.name,
                notes = notes?.trim()?.ifBlank { null },
                createdAtEpochMs = nowMs,
            ),
        )
    }

    suspend fun logAdjustment(supplyId: String, newQuantity: Int, notes: String?, nowMs: Long) {
        val current = supplyRecordDao.getLatest(supplyId)?.quantityAfter ?: 0
        supplyRecordDao.insert(
            SupplyRecordEntity(
                id = UUID.randomUUID().toString(),
                supplyId = supplyId,
                quantityDelta = newQuantity - current,
                quantityAfter = newQuantity,
                eventType = SupplyEventType.ADJUSTMENT.name,
                notes = notes?.trim()?.ifBlank { null },
                createdAtEpochMs = nowMs,
            ),
        )
    }

    // ── Low-supply check (used by LowSupplyCheckWorker) ───────────────────────

    suspend fun getLowSupplies(): List<SupplySummary> {
        val actives = supplyDao.getActiveOnce()
        return actives.mapNotNull { supply ->
            if (supply.lowThreshold <= 0) return@mapNotNull null
            val latest = supplyRecordDao.getLatest(supply.id) ?: return@mapNotNull null
            if (latest.quantityAfter <= supply.lowThreshold) {
                toSummary(supply, latest.quantityAfter)
            } else null
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun toSummary(supply: SupplyEntity, quantityOnHand: Int?): SupplySummary {
        val isLow = supply.lowThreshold > 0 &&
            quantityOnHand != null &&
            quantityOnHand <= supply.lowThreshold
        return SupplySummary(
            supplyId = supply.id,
            supplyName = supply.name,
            unit = supply.unit,
            quantityOnHand = quantityOnHand,
            lowThreshold = supply.lowThreshold,
            noRecordYet = quantityOnHand == null,
            isLowSupply = isLow,
            prescriberName = supply.prescriberName,
            prescriberPhone = supply.prescriberPhone,
            pharmacyName = supply.pharmacyName,
            pharmacyPhone = supply.pharmacyPhone,
            notes = supply.notes,
            status = supply.status,
        )
    }
}
