package com.beryndil.pharos.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.beryndil.pharos.R

/**
 * Identifies the screen currently displaying the top-bar actions, so its own
 * link is omitted (no self-link).
 */
internal enum class PharosTopBarScreen {
    MEDICATIONS,
    SUPPLIES,
    SETTINGS,

    /** A screen that is none of the three nav destinations (e.g. Today, detail). */
    OTHER,
}

/**
 * Shared top-bar nav actions: up to three icon-button links — Medications,
 * Supplies, Settings — for use in a `TopAppBar`'s `actions = { }` block.
 *
 * The link for [current] is omitted so the active screen never links to itself.
 *
 * §8 (TalkBack): each icon-only button carries a content description; the
 * default [IconButton] supplies the ≥48dp tap target.
 */
@Composable
internal fun PharosTopBarActions(
    current: PharosTopBarScreen,
    onOpenMedications: () -> Unit,
    onOpenSupplies: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (current != PharosTopBarScreen.MEDICATIONS) {
        IconButton(onClick = onOpenMedications) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ListAlt,
                contentDescription = stringResource(R.string.cd_open_medications),
            )
        }
    }
    if (current != PharosTopBarScreen.SUPPLIES) {
        IconButton(onClick = onOpenSupplies) {
            Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = stringResource(R.string.action_supplies),
            )
        }
    }
    if (current != PharosTopBarScreen.SETTINGS) {
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.cd_open_settings),
            )
        }
    }
}
