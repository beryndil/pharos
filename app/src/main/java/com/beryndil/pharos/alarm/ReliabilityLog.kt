package com.beryndil.pharos.alarm

import com.beryndil.pharos.data.regimen.dao.SettingDao
import com.beryndil.pharos.data.regimen.entity.SettingEntity

/**
 * Records alarm-engine reliability facts that the reliability dashboard (Slice 6, spec §2.13)
 * will surface to the user (Law 6 — reliability is visible). This slice only EMITS the data;
 * the dashboard UI that reads it is Slice 6.
 *
 * Implementations must be cheap and must never throw into the alarm path — a logging failure
 * must not stop an alarm from being scheduled.
 */
interface ReliabilityLog {
    suspend fun recordAlarmScheduled(kind: AlarmKind, mode: AlarmMode, triggerAtEpochMs: Long)
    suspend fun recordAlarmFired(kind: AlarmKind, atEpochMs: Long)
    suspend fun recordNoUpcomingAlarm(atEpochMs: Long)
    suspend fun recordReRegistration(trigger: String, atEpochMs: Long)
}

/**
 * [ReliabilityLog] backed by the regimen DB key-value [SettingDao]. Keys are namespaced under
 * `reliability.*`; values are last-write-wins current state (not history), which matches the
 * dashboard's "what is the engine doing right now" question.
 */
class SettingsReliabilityLog(private val settingDao: SettingDao) : ReliabilityLog {

    override suspend fun recordAlarmScheduled(
        kind: AlarmKind,
        mode: AlarmMode,
        triggerAtEpochMs: Long,
    ) {
        put(KEY_ALARM_MODE, mode.name, triggerAtEpochMs)
        put(KEY_NEXT_ALARM_KIND, kind.name, triggerAtEpochMs)
        put(KEY_NEXT_ALARM_AT, triggerAtEpochMs.toString(), triggerAtEpochMs)
    }

    override suspend fun recordAlarmFired(kind: AlarmKind, atEpochMs: Long) {
        put(KEY_LAST_FIRED_KIND, kind.name, atEpochMs)
        put(KEY_LAST_FIRED_AT, atEpochMs.toString(), atEpochMs)
    }

    override suspend fun recordNoUpcomingAlarm(atEpochMs: Long) {
        put(KEY_NEXT_ALARM_AT, VALUE_NONE, atEpochMs)
        put(KEY_NEXT_ALARM_KIND, VALUE_NONE, atEpochMs)
    }

    override suspend fun recordReRegistration(trigger: String, atEpochMs: Long) {
        put(KEY_LAST_REREGISTRATION, trigger, atEpochMs)
        put(KEY_LAST_REREGISTRATION_AT, atEpochMs.toString(), atEpochMs)
    }

    private suspend fun put(key: String, value: String, atEpochMs: Long) {
        settingDao.upsert(SettingEntity(key = key, value = value, updatedAtEpochMs = atEpochMs))
    }

    companion object {
        const val KEY_ALARM_MODE = "reliability.alarm_mode"
        const val KEY_NEXT_ALARM_KIND = "reliability.next_alarm_kind"
        const val KEY_NEXT_ALARM_AT = "reliability.next_alarm_at"
        const val KEY_LAST_FIRED_KIND = "reliability.last_fired_kind"
        const val KEY_LAST_FIRED_AT = "reliability.last_fired_at"
        const val KEY_LAST_REREGISTRATION = "reliability.last_reregistration"
        const val KEY_LAST_REREGISTRATION_AT = "reliability.last_reregistration_at"
        const val VALUE_NONE = "none"
    }
}
