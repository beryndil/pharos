package com.beryndil.pharos.data.drugref

import android.util.Log
import com.beryndil.pharos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches drug label sections from the openFDA Drug Label API.
 *
 * Primary search: openfda.rxcui:"<rxcui>". Fallback when no results: openfda.generic_name or
 * openfda.substance_name search by drug name, which catches drugs whose labels are indexed
 * by name rather than RxCUI.
 *
 * Fields extracted from each result:
 *  - adverse_reactions        → side effects
 *  - drug_interactions        → interactions
 *  - warnings / warnings_and_cautions → warnings (first non-null wins)
 *  - precautions              → precautions
 *  - contraindications        → contraindications
 *  - boxed_warning            → black-box warning
 *
 * Per spec §2.10: cached locally after fetch; source + fetch date displayed (Law 9).
 * Per Law 3: surface as reference text only — never advise.
 * Do NOT pin openFDA/NLM (gov cert rotation — Standards §6).
 */
class OpenFdaDrugLabelService : DrugLabelService {

    override suspend fun fetchLabel(productRxcui: String, medicationName: String?): FetchedLabel? =
        withContext(Dispatchers.IO) {
            // Primary: search by RxCUI
            val byRxcui = fetchUrl(rxcuiUrl(productRxcui))
            if (byRxcui != null) return@withContext byRxcui

            // Fallback: search by drug name when RxCUI returns nothing
            if (!medicationName.isNullOrBlank()) {
                val byName = fetchUrl(nameUrl(medicationName))
                if (byName != null) return@withContext byName
            }

            null
        }

    private fun rxcuiUrl(rxcui: String): String {
        val encoded = rxcui.trim()
        return "https://api.fda.gov/drug/label.json?search=openfda.rxcui:%22${encoded}%22&limit=1"
    }

    private fun nameUrl(name: String): String {
        val clean = name.trim().replace(Regex("\\s+\\d.*"), "").trim() // strip "500 mg" suffix
        val encoded = URLEncoder.encode(clean, "UTF-8")
        return "https://api.fda.gov/drug/label.json" +
            "?search=(openfda.generic_name:%22${encoded}%22+openfda.substance_name:%22${encoded}%22)&limit=1"
    }

    private fun fetchUrl(urlString: String): FetchedLabel? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                if (BuildConfig.DEBUG) Log.d(TAG, "openFDA HTTP $code for $urlString")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseResponse(body)
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "openFDA fetch failed (likely offline): ${e.javaClass.simpleName}")
            null
        } catch (e: JSONException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "openFDA JSON parse error: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun parseResponse(body: String): FetchedLabel? {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)

        fun field(vararg keys: String): String? {
            for (key in keys) {
                val v = r.optJSONArray(key)?.optString(0)
                if (!v.isNullOrBlank()) return v
            }
            return null
        }

        return FetchedLabel(
            sideEffectsText = field("adverse_reactions"),
            interactionsText = field("drug_interactions"),
            warningsText = field("warnings", "warnings_and_cautions"),
            precautionsText = field("precautions"),
            contraindicationsText = field("contraindications"),
            boxedWarningText = field("boxed_warning"),
            source = SOURCE,
        )
    }

    companion object {
        private const val TAG = "OpenFdaDrugLabelSvc"
        const val SOURCE = "openFDA"
    }
}
