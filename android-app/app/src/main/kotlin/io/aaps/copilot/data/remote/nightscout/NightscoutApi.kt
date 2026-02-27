package io.aaps.copilot.data.remote.nightscout

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.QueryMap

interface NightscoutApi {

    @GET("api/v1/status.json")
    suspend fun getStatus(): NightscoutStatusResponse

    @GET("api/v1/entries/sgv.json")
    suspend fun getSgvEntries(@QueryMap query: Map<String, String>): List<NightscoutSgvEntry>

    @POST("api/v1/entries/sgv.json")
    suspend fun postSgvEntries(@Body payload: List<Map<String, @JvmSuppressWildcards Any?>>): Any

    @GET("api/v1/treatments.json")
    suspend fun getTreatments(@QueryMap query: Map<String, String>): List<NightscoutTreatment>

    @GET("api/v1/devicestatus.json")
    suspend fun getDeviceStatus(@QueryMap query: Map<String, String>): List<NightscoutDeviceStatus>

    @POST("api/v1/devicestatus.json")
    suspend fun postDeviceStatus(@Body payload: List<Map<String, @JvmSuppressWildcards Any?>>): Any

    @POST("api/v1/treatments.json")
    suspend fun postTreatment(@Body request: NightscoutTreatmentRequest): NightscoutTreatment
}
