package com.beryndil.pharos.data.drugref

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CDN manifest describing a drug-reference DB release (spec §3.2, §3.5, DECISIONS.md S8-A4).
 *
 * The manifest is a JSON file hosted at `{cdnBaseUrl}/pharos_drug_ref_manifest.json`.
 * A raw 64-byte Ed25519 signature over the manifest's exact UTF-8 bytes lives at
 * `{cdnBaseUrl}/pharos_drug_ref_manifest.json.sig` (see DECISIONS.md S8-A4 for the full
 * CDN contract and keypair generation procedure).
 *
 * Fields:
 *  - [schemaVersion]: manifest format version — bump if this class changes incompatibly.
 *  - [dbFilename]:    filename of the DB file relative to [cdnBaseUrl].
 *  - [dbSchemaVersion]: Room schema version of the contained DB. The app refuses to swap
 *    a DB whose [dbSchemaVersion] exceeds its own [DrugRefDatabaseFactory.CURRENT_VERSION].
 *  - [sha256Hex]:     lowercase hex SHA-256 of the DB file bytes.
 *  - [sizeBytes]:     size of the DB file in bytes; used as a sanity pre-check.
 */
@Serializable
data class CdnManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("db_filename") val dbFilename: String,
    @SerialName("db_schema_version") val dbSchemaVersion: Int,
    @SerialName("sha256_hex") val sha256Hex: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)
