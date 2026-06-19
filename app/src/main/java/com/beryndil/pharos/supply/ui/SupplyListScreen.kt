package com.beryndil.pharos.supply.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.supply.SupplyListUiState
import com.beryndil.pharos.supply.SupplySummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyListScreen(
    uiState: SupplyListUiState,
    onAddSupply: () -> Unit,
    onSupplyClicked: (String) -> Unit,
    onBack: () -> Unit,
    /** Navigate to the Medications screen (shared top-bar nav). */
    onOpenMedications: () -> Unit = {},
    /** Navigate to the Settings screen (shared top-bar nav). */
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.SecureWindow()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val backCd = stringResource(R.string.cd_back_button)
    val addCd = stringResource(R.string.cd_add_supply)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_supplies)) },
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
                actions = {
                    com.beryndil.pharos.ui.navigation.PharosTopBarActions(
                        current = com.beryndil.pharos.ui.navigation.PharosTopBarScreen.SUPPLIES,
                        onOpenMedications = onOpenMedications,
                        onOpenSupplies = {},
                        onOpenSettings = onOpenSettings,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddSupply,
                modifier = Modifier.semantics { contentDescription = addCd },
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
            }
        },
    ) { innerPadding ->
        when {
            uiState.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.supplies.isEmpty() -> {
                EmptySuppliesState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    items(uiState.supplies, key = { it.supplyId }) { supply ->
                        SupplyListItem(
                            supply = supply,
                            onClick = { onSupplyClicked(supply.supplyId) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplyListItem(
    supply: SupplySummary,
    onClick: () -> Unit,
) {
    val cd = buildString {
        append(supply.supplyName)
        supply.quantityOnHand?.let { append(", $it ${supply.unit}") }
        if (supply.isLowSupply) append(", low supply")
    }
    ListItem(
        headlineContent = { Text(supply.supplyName) },
        supportingContent = {
            val qty = supply.quantityOnHand
            Text(
                when {
                    supply.noRecordYet -> stringResource(R.string.supply_no_count_yet)
                    qty != null -> "$qty ${supply.unit}"
                    else -> ""
                },
            )
        },
        trailingContent = if (supply.isLowSupply) {
            {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = stringResource(R.string.supply_low_warning),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = cd }
            .clickable(onClick = onClick),
    )
}

@Composable
private fun EmptySuppliesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inventory2,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_supplies_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_supplies_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
