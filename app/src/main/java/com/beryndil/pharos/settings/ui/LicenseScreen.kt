package com.beryndil.pharos.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R

/**
 * License and credits screen — app license, data source attributions, and OSS library credits.
 *
 * Hand-written with string resources (no OSS licenses library — per A5-S1 spec).
 * Modelled on the Spyglass about/license pattern: plain scrollable column of sections with
 * section headings, body text, and dividers.
 *
 * Reachable only from the About screen. Non-PHI — no FLAG_SECURE required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.ClearWindowSecurity()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backCd = stringResource(R.string.cd_back_button)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_license)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = backCd },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {

            // ── App license ─────────────────────────────────────────────────────────
            LicenseSection(
                heading = stringResource(R.string.license_section_app),
                body = stringResource(R.string.license_app_body),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // ── Data source attributions ────────────────────────────────────────────
            Text(
                text = stringResource(R.string.license_section_data_sources),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            LicenseCredit(body = stringResource(R.string.license_rxnorm_credit))
            Spacer(modifier = Modifier.height(12.dp))
            LicenseCredit(body = stringResource(R.string.license_openfda_credit))

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // ── Open-source software credits ────────────────────────────────────────
            Text(
                text = stringResource(R.string.license_section_oss),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            LicenseCredit(body = stringResource(R.string.license_compose_credit))
            Spacer(modifier = Modifier.height(12.dp))
            LicenseCredit(body = stringResource(R.string.license_room_credit))
            Spacer(modifier = Modifier.height(12.dp))
            LicenseCredit(body = stringResource(R.string.license_sqlcipher_credit))
            Spacer(modifier = Modifier.height(12.dp))
            LicenseCredit(body = stringResource(R.string.license_tink_credit))
            Spacer(modifier = Modifier.height(12.dp))
            LicenseCredit(body = stringResource(R.string.license_bouncy_castle_credit))

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = stringResource(R.string.license_apache_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LicenseSection(
    heading: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LicenseCredit(
    body: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
