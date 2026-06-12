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

/**
 * Fetches drug label sections from the openFDA Drug Label API.
 *
 * Endpoint: https://api.fda.gov/drug/label.json?search=openfda.rxcui:%22{rxcui}%22&limit=1
 *
 * Extracts:
 *  - `adverse_reactions[0]` → side-effects / adverse-reactions text
 *  - `drug_interactions[0]` → drug-interactions reference text
 *
 * Per spec §2.10: cached locally forever after first fetch; source + fetch date displayed (Law 9).
 * Per spec §2.10 / Law 3: surface as reference text only — never advise.
 * Do NOT pin openFDA/NLM (gov cert rotation — Standards §6).
 */
class OpenFdaDrugLabelService : DrugLabelService {

    override suspend fun fetchLabel(productRxcui: String): FetchedLabel? =
        withContext(Dispatchers.IO) {
            val encodedRxcui = productRxcui.trim()
            val urlString =
                "https://api.fda.gov/drug/label.json" +
                    "?search=openfda.rxcui:%22${encodedRxcui}%22&limit=1"
            try {
                val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "openFDA returned HTTP $code for rxcui=$encodedRxcui")
                    }
                    return@withContext null
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseResponse(body)
            } catch (e: IOException) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "openFDA fetch failed (likely offline): ${e.javaClass.simpleName}")
                }
                null
            } catch (e: JSONException) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "openFDA JSON parse error: ${e.javaClass.simpleName}")
                }
                null
            }
        }

    private fun parseResponse(body: String): FetchedLabel? {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val result = results.getJSONObject(0)
        val sideEffects = result.optJSONArray("adverse_reactions")?.optString(0)
        val interactions = result.optJSONArray("drug_interactions")?.optString(0)
        return FetchedLabel(
            sideEffectsText = sideEffects?.takeIf { it.isNotBlank() },
            interactionsText = interactions?.takeIf { it.isNotBlank() },
            source = SOURCE,
        )
    }

    companion object {
        private const val TAG = "OpenFdaDrugLabelSvc"
        const val SOURCE = "openFDA"
    }
}
