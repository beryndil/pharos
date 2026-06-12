package com.beryndil.pharos.dose

import com.beryndil.pharos.data.regimen.entity.DoseState

/**
 * The legal-transition table for the dose state machine (spec §2.6). The
 * [com.beryndil.pharos.dose.DoseStateMachine] is the single authority; it consults this table
 * before every transition and rejects anything not listed here.
 *
 * Spec diagram:
 *   SCHEDULED → DUE
 *   DUE → TAKEN | SNOOZED | SKIPPED | MISSED
 *   SNOOZED → DUE | MISSED
 *
 * Pragmatic additions (DECISIONS.md S5-A1): SNOOZED → TAKEN and SNOOZED → SKIPPED. A snoozed dose
 * stays user-actionable from the today view; recording the real outcome the user chose is more
 * faithful than forcing them to wait for the re-alert. No advice is implied (Law 3) — the user
 * initiates the action; the app only records it.
 *
 * Everything else (e.g. TAKEN → anything, SCHEDULED → TAKEN, MISSED → anything, DUE → SCHEDULED)
 * is illegal and rejected.
 */
object DoseTransition {

    private val LEGAL: Map<DoseState, Set<DoseState>> = mapOf(
        DoseState.SCHEDULED to setOf(DoseState.DUE),
        DoseState.DUE to setOf(
            DoseState.TAKEN,
            DoseState.SNOOZED,
            DoseState.SKIPPED,
            DoseState.MISSED,
        ),
        DoseState.SNOOZED to setOf(
            DoseState.DUE,
            DoseState.TAKEN,
            DoseState.SKIPPED,
            DoseState.MISSED,
        ),
        DoseState.TAKEN to emptySet(),
        DoseState.SKIPPED to emptySet(),
        DoseState.MISSED to emptySet(),
    )

    /** True when [from] → [to] is a legal dose transition (spec §2.6). */
    fun isLegal(from: DoseState, to: DoseState): Boolean =
        LEGAL[from]?.contains(to) == true
}

/**
 * Thrown when a caller attempts an illegal dose-state transition. Surfacing this (rather than
 * silently ignoring) keeps the state machine honest: an illegal transition is a programming
 * error, not a user-recoverable condition (Standards §1 — never swallow).
 */
class IllegalDoseTransitionException(
    val from: DoseState,
    val to: DoseState,
) : IllegalStateException("Illegal dose transition: $from → $to")
