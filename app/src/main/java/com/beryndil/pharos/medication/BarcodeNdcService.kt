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
 * Looks up an NDC or UPC barcode value against the openFDA Drug NDC API and returns
 * pre-fill data for the Add Medication form.
 *
 * Pill bottles carry three main barcode formats:
 *  - UPC-A (12 digits): OTC meds — leading 0 + 10-digit NDC + check digit.
 *  - Code 128 (10–11 digits): Rx meds — NDC in 5-4-2 or 5-4-1 form.
 *  - GS1-128: contains Application Identifiers (e.g., "(01)…") with the NDC embedded.
 *
 * All are attempted as openFDA package_ndc searches. On failure, falls back to a generic
 * drug name search so the user can still start with a populated name query.
 *
 * Per spec Law 4: no off-device transmission of user data — only the raw barcode value
 * (a public drug identifier) is sent to the openFDA public API. No user health data leaves
 * the device.
 */
object BarcodeNdcService {

    data class DrugLookupResult(
        val name: String,
        val strength: String?,
        val form: MedicationForm?,
    )

    suspend fun lookup(rawBarcode: String): DrugLookupResult? = withContext(Dispatchers.IO) {
        val candidates = buildNdcCandidates(rawBarcode)
        for (ndc in candidates) {
            val result = queryOpenFda(ndc)
            if (result != null) return@withContext result
        }
        null
    }

    /** Generates a prioritized list of NDC format candidates to try from the raw barcode. */
    private fun buildNdcCandidates(raw: String): List<String> {
        val candidates = mutableListOf<String>()
        val digits = raw.filter { it.isDigit() }

        // GS1-128: strip Application Identifiers and extract the 14-digit GTIN.
        // GTIN-14 → drop first digit (indicator), last digit (check), get 12 → trim leading 0 → NDC.
        val stripped = raw.replace(Regex("""\(\d{2}\)"""), "").filter { it.isDigit() }
        if (stripped.length >= 10) {
            candidates += toNdcFormats(stripped)
        }

        // UPC-A (12 digits): strip leading 0 and trailing check digit → 10-digit NDC.
        if (digits.length == 12 && digits.startsWith("0")) {
            val ten = digits.substring(1, 11)
            candidates += toNdcFormats(ten)
        }

        // Raw digits as-is.
        candidates += toNdcFormats(digits)
        // Also try the raw string directly (some scanners return pre-formatted NDCs like "12345-6789-01").
        candidates += raw.trim()

        return candidates.distinct()
    }

    /**
     * Converts a digit string to the openFDA-expected NDC dash formats.
     * openFDA stores package_ndc as "XXXXX-XXXX-XX" or "XXXXX-XXXX".
     */
    private fun toNdcFormats(digits: String): List<String> {
        return when (digits.length) {
            10 -> listOf("${digits.substring(0, 5)}-${digits.substring(5, 9)}-${digits.substring(9)}")
            11 -> listOf(
                "${digits.substring(0, 5)}-${digits.substring(5, 9)}-${digits.substring(9)}",
                "${digits.substring(0, 5)}-${digits.substring(5, 10)}",
            )
            12 -> listOf("${digits.substring(0, 5)}-${digits.substring(5, 9)}-${digits.substring(9, 11)}")
            else -> emptyList()
        }
    }

    private fun queryOpenFda(ndcOrName: String): DrugLookupResult? {
        val encoded = URLEncoder.encode("\"$ndcOrName\"", "UTF-8")
        val url = "https://api.fda.gov/drug/ndc.json?search=package_ndc:$encoded&limit=1"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseNdcResponse(body)
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "NDC lookup failed (offline?): ${e.javaClass.simpleName}")
            null
        } catch (e: JSONException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "NDC parse error: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun parseNdcResponse(body: String): DrugLookupResult? {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)

        // Prefer generic name; fall back to brand name.
        val name = r.optString("generic_name").ifBlank { r.optString("brand_name") }.ifBlank { return null }
            .trim().lowercase().replaceFirstChar { it.uppercase() }

        // First active ingredient strength.
        val strength = r.optJSONArray("active_ingredients")
            ?.optJSONObject(0)
            ?.optString("strength")
            ?.takeIf { it.isNotBlank() }

        val form = mapDosageForm(r.optString("dosage_form").takeIf { it.isNotBlank() })

        return DrugLookupResult(name = name, strength = strength, form = form)
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
