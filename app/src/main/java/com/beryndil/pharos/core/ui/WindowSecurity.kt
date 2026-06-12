package com.beryndil.pharos.core.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Applies [WindowManager.LayoutParams.FLAG_SECURE] for the lifetime of the calling composable's
 * composition (A3-5 — per-screen FLAG_SECURE; Standards §6).
 *
 * Call this at the top level of every screen that renders PHI (medication names, dose history,
 * schedules, refill records, backup passphrase). It is idempotent: adding the flag when it is
 * already set is a no-op.
 *
 * **Transition semantics**: the flag is added during composition (before the screen is drawn)
 * and intentionally NOT cleared on dispose. The companion [ClearWindowSecurity] handles clearing
 * when navigating TO a non-PHI screen. This avoids a brief FLAG_SECURE gap when navigating
 * between two PHI screens (PHI-A disposes AFTER PHI-B is composed and has already added the flag).
 */
@Composable
fun SecureWindow() {
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { /* intentionally no-op — ClearWindowSecurity handles removal */ }
    }
}

/**
 * Clears [WindowManager.LayoutParams.FLAG_SECURE] for the lifetime of the calling composable's
 * composition (A3-5).
 *
 * Call this at the top level of non-PHI screens (Onboarding, Legal). When the user navigates
 * FROM a PHI screen TO a non-PHI screen, [ClearWindowSecurity] removes the flag so the OS does
 * not treat the non-PHI screen as sensitive.
 *
 * On dispose (returning to a PHI screen), the flag is not re-added here — the PHI screen's
 * [SecureWindow] composition adds it first.
 */
@Composable
fun ClearWindowSecurity() {
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(Unit) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { /* intentionally no-op — SecureWindow handles re-adding */ }
    }
}
