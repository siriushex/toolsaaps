package io.aaps.copilot.data.repository

data class AapsCarbEntry(
    val remoteId: String?,
    val tsMs: Long,
    val grams: Double,
    val note: String?
)

interface AapsCarbGateway {
    suspend fun postCarbEntry(
        tsMs: Long,
        grams: Double,
        note: String
    ): Result<String>

    suspend fun fetchCarbEntries(
        sinceTsMs: Long
    ): Result<List<AapsCarbEntry>>
}
