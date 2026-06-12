package com.beryndil.pharos.data.drugref

/**
 * Network service that fetches drug label sections from a remote source (openFDA / DailyMed).
 *
 * Returns null on network failure or when the source has no record for the given RxCUI.
 * Never throws — callers treat null as "not available right now."
 *
 * Production implementation: [OpenFdaDrugLabelService].
 * Test implementation: any [FetchedLabel]-returning fake.
 */
interface DrugLabelService {

    /**
     * Fetches the adverse-reactions (side effects) and drug-interactions label sections for
     * the drug identified by [productRxcui].
     *
     * @param productRxcui RxNorm RxCUI of the product (as stored in [MedicationEntity.rxcui]).
     * @return [FetchedLabel] on success; null if the request fails or no data found.
     */
    suspend fun fetchLabel(productRxcui: String): FetchedLabel?
}
