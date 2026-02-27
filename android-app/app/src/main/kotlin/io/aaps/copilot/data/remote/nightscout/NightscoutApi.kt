package io.aaps.copilot.data.remote.nightscout

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.QueryMap

interface NightscoutApi {

    @GET("api/v1/entries/sgv.json")
    suspend fun getSgvEntries(@QueryMap query: Map<String, String>): List<NightscoutSgvEntry>

    @GET("api/v1/treatments.json")
    suspend fun getTreatments(@QueryMap query: Map<String, String>): List<NightscoutTreatment>

    @POST("api/v1/treatments.json")
    suspend fun postTreatment(@Body request: NightscoutTreatmentRequest): NightscoutTreatment
}
