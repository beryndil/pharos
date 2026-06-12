package com.beryndil.pharos.backup.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.beryndil.pharos.R
import com.beryndil.pharos.backup.BackupEvent
import com.beryndil.pharos.backup.BackupOperation
import com.beryndil.pharos.backup.BackupUiState

/**
 * Backup & Restore screen (spec §2.12, Law 7 — free recovery path, always available).
 *
 * Sections:
 *  1. Encrypted backup — create/restore (user passphrase, SAF destination)
 *  2. Export list      — PDF or CSV (plaintext, no passphrase)
 *
 * Plain language per DESIGN.md: explains what is / is not encrypted, that these features
 * are free, and that backups stay local unless the user explicitly saves them to Drive or
 * email (Law 4 — no automatic off-device transmission).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    uiState: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.beryndil.pharos.core.ui.SecureWindow()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }

    // Passphrase state held here (not in ViewModel) — zeroed on use, not persisted to SavedState.
    var pendingBackupPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var pendingRestorePassphrase by remember { mutableStateOf<CharArray?>(null) }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val pass = pendingBackupPassphrase
        pendingBackupPassphrase = null
        if (uri != null && pass != null) onEvent(BackupEvent.CreateBackup(pass, uri))
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val pass = pendingRestorePassphrase
        pendingRestorePassphrase = null
        if (uri != null && pass != null) onEvent(BackupEvent.Restore(pass, uri))
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri: Uri? ->
        if (uri != null) onEvent(BackupEvent.ExportPdf(uri))
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        if (uri != null) onEvent(BackupEvent.ExportCsv(uri))
    }

    val backupSuccessMsg = stringResource(R.string.backup_success)
    val backupShareActionLabel = stringResource(R.string.backup_share_action)
    val restoreSuccessMsg = stringResource(R.string.backup_restore_success)
    val exportSuccessMsg = stringResource(R.string.backup_export_success)

    LaunchedEffect(uiState.operation) {
        when (val op = uiState.operation) {
            is BackupOperation.BackupSuccess -> {
                // Offer a "Share" action so the user can immediately send the backup file (A3-8).
                val result = snackbarHostState.showSnackbar(
                    message = backupSuccessMsg,
                    actionLabel = backupShareActionLabel,
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, op.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, null))
                }
                onEvent(BackupEvent.DismissResult)
            }
            is BackupOperation.RestoreSuccess -> {
                snackbarHostState.showSnackbar(restoreSuccessMsg)
                onEvent(BackupEvent.DismissResult)
            }
            is BackupOperation.ExportSuccess -> {
                snackbarHostState.showSnackbar(exportSuccessMsg)
                onEvent(BackupEvent.DismissResult)
            }
            is BackupOperation.Error -> {
                snackbarHostState.showSnackbar(op.message)
                onEvent(BackupEvent.DismissResult)
            }
            else -> Unit
        }
    }

    val backCd = stringResource(R.string.cd_back_button)

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.screen_backup)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (uiState.operation == BackupOperation.InProgress) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.backup_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                EncryptedBackupSection(
                    onCreateBackup = { passphrase ->
                        pendingBackupPassphrase = passphrase
                        createDocLauncher.launch("pharos_backup.bak")
                    },
                    onRestoreBackup = { passphrase ->
                        pendingRestorePassphrase = passphrase
                        openDocLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                )

                ExportListSection(
                    onExportPdf = { exportPdfLauncher.launch("pharos_medications.pdf") },
                    onExportCsv = { exportCsvLauncher.launch("pharos_medications.csv") },
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun EncryptedBackupSection(
    onCreateBackup: (CharArray) -> Unit,
    onRestoreBackup: (CharArray) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.backup_section_encrypted),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.backup_encrypted_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Outlined.Lock, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(stringResource(R.string.backup_action_create))
        }

        OutlinedButton(
            onClick = { showRestoreDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Outlined.UploadFile, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(stringResource(R.string.backup_action_restore))
        }
    }

    if (showCreateDialog) {
        PassphraseDialog(
            title = stringResource(R.string.backup_passphrase_title_create),
            body = stringResource(R.string.backup_passphrase_body_create),
            confirmLabel = stringResource(R.string.backup_action_create),
            onConfirm = { passphrase ->
                showCreateDialog = false
                onCreateBackup(passphrase)
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    if (showRestoreDialog) {
        PassphraseDialog(
            title = stringResource(R.string.backup_passphrase_title_restore),
            body = stringResource(R.string.backup_passphrase_body_restore),
            confirmLabel = stringResource(R.string.backup_action_restore),
            onConfirm = { passphrase ->
                showRestoreDialog = false
                onRestoreBackup(passphrase)
            },
            onDismiss = { showRestoreDialog = false },
        )
    }
}

@Composable
private fun ExportListSection(
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.backup_section_export),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.backup_export_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onExportPdf,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_action_export_pdf))
        }
        OutlinedButton(
            onClick = onExportCsv,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_action_export_csv))
        }
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    val fieldCd = stringResource(R.string.backup_passphrase_field_cd)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = fieldCd },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passphrase.toCharArray()) },
                enabled = passphrase.isNotEmpty(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}
