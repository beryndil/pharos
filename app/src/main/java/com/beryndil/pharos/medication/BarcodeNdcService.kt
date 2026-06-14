package com.beryndil.pharos.medication

import android.util.Log
import com.beryndil.pharos.BuildConfig
import com.beryndil.pharos.data.regimen.entity.MedicationForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up a barcode value from a pill bottle and returns pre-fill data for the Add Medication form.
 *
 * Supports multiple barcode types found on US pill bottles:
 *  - **UPC-A (12 digits):** OTC meds. Leading 0 + 10-digit NDC + check digit.
 *  - **EAN-13 (13 digits):** Some OTC meds. Similar encoding.
 *  - **Code 128 (11 digits):** Rx bottles. Direct 11-digit padded NDC (5-4-2 format).
 *  - **Code 128 (10 digits):** Rx bottles. Raw 10-digit NDC, config unknown — tries all three.
 *  - **GS1-128:** Extracts digit run and tries NDC formats.
 *
 * Two lookup paths are tried in order:
 *  1. openFDA Drug NDC API — structured, has name + strength + dosage form.
 *  2. openFDA Drug Label API — broader, covers OTC UPC barcodes; returns name only (no strength).
 *
 * Per Law 4: only the raw barcode value (a public drug identifier) is transmitted. No user
 * health data leaves the device.
 */
object BarcodeNdcService {

    data class DrugLookupResult(
        val name: String,
        val strength: String?,
        val form: MedicationForm?,
    )

    suspend fun lookup(rawBarcode: String): DrugLookupResult? = withContext(Dispatchers.IO) {
        val digits = rawBarcode.filter { it.isDigit() }

        // Path 1: Try formatted NDC candidates against the NDC endpoint (structured response).
        for (ndc in buildNdcCandidates(rawBarcode, digits)) {
            queryNdcEndpoint(ndc)?.let { return@withContext it }
        }

        // Path 2: Try the label endpoint with the raw UPC digits (better for OTC drugs).
        if (digits.length >= 8) {
            queryLabelByUpc(digits)?.let { return@withContext it }
            // Strip leading zero in case scanner returned EAN prefix.
            if (digits.startsWith("0")) {
                queryLabelByUpc(digits.drop(1))?.let { return@withContext it }
            }
        }

        null
    }

    // ── NDC candidate generation ──────────────────────────────────────────

    /**
     * Generates a prioritised list of formatted NDC strings to try.
     *
     * FDA NDC is 10 digits in one of three segment configurations:
     *  - 5-4-1: five-labeler, four-product, one-package
     *  - 5-3-2: five-labeler, three-product, two-package
     *  - 4-4-2: four-labeler, four-product, two-package
     *
     * openFDA stores NDC in padded 5-4-2 (11-digit) format: `XXXXX-XXXX-XX`.
     */
    private fun buildNdcCandidates(raw: String, digits: String): List<String> {
        val candidates = mutableListOf<String>()

        // GS1-128 Application Identifiers — strip "(NN)" tags, take first long digit run.
        if (raw.contains("(")) {
            val stripped = raw.replace(Regex("""\(\d{2}\)"""), "").filter { it.isDigit() }
            // GTIN-14 inside AI (01): 14 digits — drop leading indicator + trailing check → 12,
            // then treat as UPC-A.
            if (stripped.length >= 14) {
                val twelveFromGtin = stripped.take(14).let { it.substring(1, 13) }
                if (twelveFromGtin.startsWith("0")) {
                    candidates += allThreeFormats(twelveFromGtin.substring(1, 11))
                }
            }
            // Take first 11-digit run as padded NDC.
            val firstEleven = stripped.take(11)
            if (firstEleven.length == 11) {
                candidates += paddedNdcFormat(firstEleven)
            }
        }

        when (digits.length) {
            // UPC-A: strip leading 0 and trailing check digit → 10-digit NDC.
            12 -> if (digits.startsWith("0")) candidates += allThreeFormats(digits.substring(1, 11))
            // EAN-13: strip leading digit + trailing check.
            13 -> {
                val eleven = digits.substring(1, 12)
                candidates += paddedNdcFormat(eleven)
                if (digits.startsWith("0")) candidates += allThreeFormats(digits.substring(1, 11))
                candidates += allThreeFormats(digits.substring(2, 12))
            }
            // Padded 11-digit NDC (5-4-2) — standard on US Rx bottles.
            11 -> {
                candidates += paddedNdcFormat(digits)
                // Might also be 10-digit NDC + check digit.
                candidates += allThreeFormats(digits.take(10))
            }
            // 10-digit NDC, configuration unknown — try all three.
            10 -> candidates += allThreeFormats(digits)
        }

        return candidates.distinct()
    }

    /** Formats 11 raw digits as the padded openFDA NDC form `XXXXX-XXXX-XX`. */
    private fun paddedNdcFormat(digits: String): String {
        require(digits.length == 11)
        return "${digits.substring(0, 5)}-${digits.substring(5, 9)}-${digits.substring(9)}"
    }

    /** All three FDA NDC configurations for 10 digits. */
    private fun allThreeFormats(digits: String): List<String> {
        if (digits.length != 10) return emptyList()
        return listOf(
            "${digits.substring(0, 5)}-${digits.substring(5, 9)}-${digits.substring(9)}",   // 5-4-1
            "${digits.substring(0, 5)}-${digits.substring(5, 8)}-${digits.substring(8)}",   // 5-3-2
            "${digits.substring(0, 4)}-${digits.substring(4, 8)}-${digits.substring(8)}",   // 4-4-2
        )
    }

    // ── openFDA NDC endpoint ──────────────────────────────────────────────

    private fun queryNdcEndpoint(ndc: String): DrugLookupResult? {
        // Try exact phrase match first (Elasticsearch quoted syntax).
        val encodedExact = URLEncoder.encode("\"$ndc\"", "UTF-8")
        fetchJson("https://api.fda.gov/drug/ndc.json?search=package_ndc:$encodedExact&limit=1")
            ?.let { parseNdcBody(it) }
            ?.let { return it }

        // Retry without quotes (handles cases where hyphens break tokenisation).
        val encodedPlain = URLEncoder.encode(ndc, "UTF-8")
        fetchJson("https://api.fda.gov/drug/ndc.json?search=package_ndc:$encodedPlain&limit=1")
            ?.let { parseNdcBody(it) }
            ?.let { return it }

        // Also try product_ndc (first two segments, no package code).
        val productNdc = ndc.substringBeforeLast("-")
        if (productNdc != ndc) {
            val encodedProduct = URLEncoder.encode("\"$productNdc\"", "UTF-8")
            fetchJson("https://api.fda.gov/drug/ndc.json?search=product_ndc:$encodedProduct&limit=1")
                ?.let { parseNdcBody(it) }
                ?.let { return it }
        }

        return null
    }

    private fun parseNdcBody(body: String): DrugLookupResult? {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)

        val name = r.optString("generic_name").ifBlank { r.optString("brand_name") }
            .ifBlank { return null }
            .trim()
            .split(" ")
            .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

        val strength = r.optJSONArray("active_ingredients")
            ?.optJSONObject(0)
            ?.optString("strength")
            ?.takeIf { it.isNotBlank() }

        val form = mapDosageForm(r.optString("dosage_form").takeIf { it.isNotBlank() })

        if (BuildConfig.DEBUG) Log.d(TAG, "NDC match: $name / $strength / $form")
        return DrugLookupResult(name = name, strength = strength, form = form)
    }

    // ── openFDA Label endpoint (UPC path) ─────────────────────────────────

    private fun queryLabelByUpc(upc: String): DrugLookupResult? {
        val encoded = URLEncoder.encode("\"$upc\"", "UTF-8")
        val body = fetchJson(
            "https://api.fda.gov/drug/label.json?search=openfda.upc:$encoded&limit=1",
        ) ?: return null
        return parseLabelBody(body)
    }

    private fun parseLabelBody(body: String): DrugLookupResult? {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        val openfda = r.optJSONObject("openfda") ?: return null

        val genericNames = openfda.optJSONArray("generic_name")
        val brandNames = openfda.optJSONArray("brand_name")
        val name = (genericNames?.optString(0) ?: brandNames?.optString(0))
            ?.ifBlank { null }
            ?.trim()
            ?.split(" ")
            ?.joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
            ?: return null

        // Label endpoint doesn't have structured active_ingredient strengths; leave null.
        // The dosage_form is sometimes present in openfda but not always.
        val form = openfda.optJSONArray("dosage_form")
            ?.optString(0)
            ?.let { mapDosageForm(it) }

        if (BuildConfig.DEBUG) Log.d(TAG, "UPC label match: $name / $form")
        return DrugLookupResult(name = name, strength = null, form = form)
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    private fun fetchJson(url: String): String? {
        if (BuildConfig.DEBUG) Log.d(TAG, "GET $url")
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                if (BuildConfig.DEBUG) Log.d(TAG, "HTTP ${conn.responseCode} for $url")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "IO error: ${e.javaClass.simpleName}")
            null
        } catch (e: JSONException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "JSON error: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun mapDosageForm(fdaForm: String?): MedicationForm? = when {
        fdaForm == null -> null
        fdaForm.contains("CAPLET", ignoreCase = true) -> MedicationForm.CAPLET
        fdaForm.contains("TABLET", ignoreCase = true) -> MedicationForm.TABLET
        fdaForm.contains("CAPSULE", ignoreCase = true) -> MedicationForm.CAPSULE
        fdaForm.contains("SOLUTION", ignoreCase = true) ||
            fdaForm.contains("LIQUID", ignoreCase = true) ||
            fdaForm.contains("SUSPENSION", ignoreCase = true) ||
            fdaForm.contains("SYRUP", ignoreCase = true) -> MedicationForm.LIQUID
        fdaForm.contains("INJECT", ignoreCase = true) -> MedicationForm.INJECTION
        fdaForm.contains("INHALER", ignoreCase = true) ||
            fdaForm.contains("AEROSOL", ignoreCase = true) -> MedicationForm.INHALER
        fdaForm.contains("PATCH", ignoreCase = true) ||
            fdaForm.contains("TRANSDERMAL", ignoreCase = true) -> MedicationForm.PATCH
        fdaForm.contains("DROP", ignoreCase = true) ||
            fdaForm.contains("OPHTHALMIC", ignoreCase = true) -> MedicationForm.DROPS
        fdaForm.contains("CREAM", ignoreCase = true) ||
            fdaForm.contains("OINTMENT", ignoreCase = true) ||
            fdaForm.contains("GEL", ignoreCase = true) -> MedicationForm.CREAM
        else -> null
    }

    private const val TAG = "BarcodeNdcService"
}
