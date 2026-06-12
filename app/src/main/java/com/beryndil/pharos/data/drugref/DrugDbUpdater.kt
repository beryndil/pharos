package com.beryndil.pharos.data.drugref

import android.content.Context
import android.util.Log
import com.beryndil.pharos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Orchestrates the CDN drug-reference DB update pipeline (spec §3.2, §3.5, Standards §6).
 *
 * Pipeline steps — every step must succeed or the update is aborted (prior DB preserved):
 *  1. Download the manifest JSON from `{cdnBaseUrl}/pharos_drug_ref_manifest.json`.
 *  2. Download the Ed25519 signature from `{cdnBaseUrl}/pharos_drug_ref_manifest.json.sig`.
 *  3. Verify the signature over the exact manifest bytes — abort on failure (tamper).
 *  4. Parse the manifest; refuse if [CdnManifest.schemaVersion] > [ManifestVerifier.SUPPORTED_MANIFEST_SCHEMA_VERSION].
 *  5. Refuse if [CdnManifest.dbSchemaVersion] > [DrugRefDatabaseFactory.CURRENT_VERSION] (forward-compat).
 *  6. Download the DB file to a temp location; verify size and SHA-256 against the manifest.
 *  7. Atomic swap via [DrugRefDatabaseFactory.swapFromFile]; failure leaves the prior DB intact.
 *
 * Law 9: a bad push (signature mismatch, hash mismatch, incompatible schema) MUST roll back.
 * The prior DB remains in place until the new one validates end-to-end.
 *
 * Cleartext is disabled globally (network_security_config.xml); only HTTPS is accepted.
 *
 * @param context          Application context for database path resolution.
 * @param cdnBaseUrl       Base URL of the CDN (no trailing slash). Injected so tests can
 *                         point to a local `file://` directory without network access.
 * @param manifestVerifier Ed25519 verifier; production code passes [ManifestVerifier.production()].
 */
class DrugDbUpdater(
    private val context: Context,
    val cdnBaseUrl: String,
    private val manifestVerifier: ManifestVerifier = ManifestVerifier.production(),
) {

    /**
     * Checks for a new CDN release and applies it atomically if valid.
     *
     * Safe to call repeatedly (idempotent on the file system). A failed update leaves the
     * prior DB untouched and returns [UpdateResult.Failure].
     *
     * Must be called on [Dispatchers.IO].
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        val manifestUrl = "$cdnBaseUrl/pharos_drug_ref_manifest.json"
        val sigUrl = "$cdnBaseUrl/pharos_drug_ref_manifest.json.sig"

        // Step 1 & 2: download manifest + signature bytes.
        val manifestBytes = downloadBytes(manifestUrl)
            ?: return@withContext UpdateResult.Failure("Cannot download manifest")
        val sigBytes = downloadBytes(sigUrl)
            ?: return@withContext UpdateResult.Failure("Cannot download manifest signature")

        // Step 3: verify signature BEFORE parsing (auth before parse).
        if (!manifestVerifier.verify(manifestBytes, sigBytes)) {
            Log.e(TAG, "Manifest signature verification FAILED — aborting CDN update (Law 9).")
            return@withContext UpdateResult.Failure("Signature verification failed")
        }

        // Step 4: parse manifest; refuse unknown schema_version.
        val manifest = try {
            json.decodeFromString<CdnManifest>(String(manifestBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return@withContext UpdateResult.Failure("Manifest parse error: ${e.javaClass.simpleName}")
        }
        if (manifest.schemaVersion > ManifestVerifier.SUPPORTED_MANIFEST_SCHEMA_VERSION) {
            Log.w(TAG, "Manifest schema_version ${manifest.schemaVersion} > supported ${ManifestVerifier.SUPPORTED_MANIFEST_SCHEMA_VERSION}")
            return@withContext UpdateResult.Failure("Manifest schema version too new")
        }

        // Step 5: refuse DB whose Room schema is newer than this app understands.
        if (manifest.dbSchemaVersion > DrugRefDatabaseFactory.CURRENT_VERSION) {
            Log.w(
                TAG,
                "CDN DB schema v${manifest.dbSchemaVersion} > app max v${DrugRefDatabaseFactory.CURRENT_VERSION}; " +
                    "keeping current DB (Law 9).",
            )
            return@withContext UpdateResult.Failure("DB schema version too new for this app version")
        }

        // Step 6: download DB to temp file; verify size + SHA-256.
        val dbUrl = "$cdnBaseUrl/${manifest.dbFilename}"
        val tempFile = File(context.cacheDir, "drug_ref_candidate.db")
        try {
            downloadToFile(dbUrl, tempFile, manifest.sizeBytes)
                ?: return@withContext UpdateResult.Failure("Cannot download DB file")
        } catch (e: IOException) {
            tempFile.delete()
            return@withContext UpdateResult.Failure("DB download I/O error: ${e.message}")
        }

        if (!verifySha256(tempFile, manifest.sha256Hex)) {
            Log.e(TAG, "DB SHA-256 mismatch — aborting CDN update, keeping prior DB (Law 9).")
            tempFile.delete()
            return@withContext UpdateResult.Failure("DB SHA-256 mismatch")
        }

        // Step 7: atomic swap — DrugRefDatabaseFactory.swapFromFile checks schema + replaces.
        val swapped = DrugRefDatabaseFactory.swapFromFile(context, tempFile)
        if (swapped) {
            Log.i(TAG, "CDN drug-ref DB updated to schema v${manifest.dbSchemaVersion}.")
            UpdateResult.Success(dbSchemaVersion = manifest.dbSchemaVersion)
        } else {
            // swapFromFile already deleted tempFile on failure.
            UpdateResult.Failure("Atomic swap failed (incompatible schema or I/O error)")
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun downloadBytes(urlString: String): ByteArray? {
        return try {
            val conn = URL(urlString).openConnection()
            if (conn is HttpURLConnection) {
                conn.requestMethod = "GET"
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "HTTP ${conn.responseCode} for $urlString")
                    }
                    return null
                }
            }
            conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "downloadBytes failed for $urlString: ${e.javaClass.simpleName}")
            }
            null
        }
    }

    /** Downloads [urlString] to [dest] and returns [dest] on success, null on error. */
    private fun downloadToFile(urlString: String, dest: File, expectedSize: Long): File? {
        return try {
            val conn = URL(urlString).openConnection()
            if (conn is HttpURLConnection) {
                conn.requestMethod = "GET"
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.length() != expectedSize) {
                Log.w(TAG, "Downloaded size ${dest.length()} != expected $expectedSize")
                dest.delete()
                return null
            }
            dest
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "downloadToFile failed for $urlString: ${e.javaClass.simpleName}")
            }
            null
        }
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8_192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == expectedHex.lowercase()
    }

    companion object {
        private const val TAG = "DrugDbUpdater"
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Result of [checkForUpdate]. */
    sealed interface UpdateResult {
        /** DB was successfully replaced with the CDN version. */
        data class Success(val dbSchemaVersion: Int) : UpdateResult

        /** Update failed (prior DB intact). [reason] is for logging/debugging only — not user-facing. */
        data class Failure(val reason: String) : UpdateResult
    }
}
