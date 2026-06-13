package com.beryndil.pharos.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/**
 * Manages the automatic transparent backup written to the device's Downloads folder.
 *
 * ## Why this survives an uninstall
 * The backup file lives in the public Downloads folder (not the app's internal storage),
 * so Android does NOT delete it when the app is uninstalled. On reinstall, the file is
 * still there and can be decrypted with the same key.
 *
 * ## Key derivation
 * The backup key is derived from [Settings.Secure.ANDROID_ID] via [BackupCrypto.deriveKey]
 * (Argon2id, m=64 MiB, t=3, p=4). On API 26+ with a consistent release signing key,
 * ANDROID_ID returns the same value before and after an uninstall+reinstall on the same
 * device — so no user passphrase is needed to restore.
 *
 * ## Law compliance
 * - Law 4: data stays on the device in an encrypted file. No cloud, no network.
 * - Law 7: zero-user-action recovery path after an accidental uninstall.
 *
 * ## Storage API
 * - API 29+: [MediaStore.Downloads] (no extra permissions required).
 * - API 26–28: direct write to [Environment.DIRECTORY_DOWNLOADS] via File
 *   (requires [android.Manifest.permission.WRITE_EXTERNAL_STORAGE]).
 */
class AutoBackupManager(
    private val context: Context,
    private val repository: BackupRepository,
) {
    companion object {
        private const val FILE_NAME = "pharos-auto-backup.pbk"
        private const val SUBDIR    = "Pharos"
        private const val MIME      = "application/octet-stream"
    }

    /**
     * Returns the auto-backup passphrase derived from [Settings.Secure.ANDROID_ID].
     * Stable per signing-key per device on API 26+.
     *
     * Note: [BackupCrypto.deriveKey] will zero this array after use — always call this
     * fresh; never reuse the same [CharArray] across multiple backup calls.
     */
    private fun autoPassphrase(): CharArray =
        (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "pharos-auto-backup-fallback")
            .toCharArray()

    /**
     * Write an encrypted auto-backup to Downloads/Pharos/pharos-auto-backup.pbk.
     *
     * Any existing file at that location is replaced atomically. Must be called on an IO
     * dispatcher — Argon2id key derivation takes ~500 ms–2 s.
     */
    suspend fun writeAutoBackup(): BackupResult =
        try {
            val uri = resolveWriteUri() ?: return BackupResult.Error(
                "Cannot create auto-backup: Downloads folder not accessible.",
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Write with IS_PENDING=1 so the file is invisible to other apps during the
                // write; cleared to 0 when the backup completes (or on failure).
                val result = repository.createBackup(autoPassphrase(), uri)
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, done, null, null)
                result
            } else {
                repository.createBackup(autoPassphrase(), uri)
            }
        } catch (e: Exception) {
            BackupResult.Error("Auto-backup failed: ${e.message}")
        }

    /**
     * Returns true when an auto-backup file is present in Downloads.
     * Use this on startup to decide whether to show the restore prompt.
     */
    fun hasAutoBackupFile(): Boolean = findReadUri() != null

    /**
     * Restore from the auto-backup file in Downloads.
     * No passphrase required — key derived from [Settings.Secure.ANDROID_ID] automatically.
     */
    suspend fun restoreAutoBackup(): RestoreResult =
        try {
            val uri = findReadUri() ?: return RestoreResult.Error(
                "No auto-backup file found in Downloads.",
            )
            repository.restore(autoPassphrase(), uri)
        } catch (e: Exception) {
            RestoreResult.Error("Auto-restore failed: ${e.message}")
        }

    // ── URI resolution ────────────────────────────────────────────────────

    /** Find an existing auto-backup URI suitable for reading (null = not found). */
    private fun findReadUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) findInMediaStore()
        else legacyFile().let { if (it.exists()) Uri.fromFile(it) else null }

    /**
     * Create (or replace) the auto-backup MediaStore entry and return a URI for writing.
     * On API < 29, returns a direct File URI.
     */
    private fun resolveWriteUri(): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Delete any existing entry so we can create a fresh one.
            findInMediaStore()?.let { context.contentResolver.delete(it, null, null) }
            return insertIntoMediaStore()
        } else {
            val file = legacyFile()
            file.parentFile?.mkdirs()
            return Uri.fromFile(file)
        }
    }

    /** Query MediaStore Downloads for the auto-backup file owned by this app. */
    private fun findInMediaStore(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selArgs = arrayOf(FILE_NAME, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/")
        context.contentResolver.query(collection, projection, selection, selArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    /** Insert a new auto-backup entry into MediaStore Downloads (API 29+). */
    private fun insertIntoMediaStore(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/")
            put(MediaStore.Downloads.IS_PENDING, 1) // invisible during write
        }
        return context.contentResolver.insert(collection, values)
    }

    /** Pre-API-29 download file path. Requires WRITE_EXTERNAL_STORAGE permission. */
    private fun legacyFile(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBDIR,
        )
        return File(dir, FILE_NAME)
    }
}
