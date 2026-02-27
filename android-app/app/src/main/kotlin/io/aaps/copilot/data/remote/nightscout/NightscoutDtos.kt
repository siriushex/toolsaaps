package io.aaps.copilot.data.remote.nightscout

import com.google.gson.annotations.SerializedName

data class NightscoutSgvEntry(
    @SerializedName("date") val date: Long,
    @SerializedName("sgv") val sgv: Double,
    @SerializedName("device") val device: String? = null,
    @SerializedName("type") val type: String? = null
)

data class NightscoutTreatment(
    @SerializedName("_id") val id: String? = null,
    @SerializedName("date") val date: Long? = null,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("eventType") val eventType: String,
    @SerializedName("carbs") val carbs: Double? = null,
    @SerializedName("insulin") val insulin: Double? = null,
    @SerializedName("enteredBy") val enteredBy: String? = null,
    @SerializedName("absolute") val absolute: Double? = null,
    @SerializedName("rate") val rate: Double? = null,
    @SerializedName("percentage") val percentage: Int? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("durationInMilliseconds") val durationInMilliseconds: Long? = null,
    @SerializedName("targetTop") val targetTop: Double? = null,
    @SerializedName("targetBottom") val targetBottom: Double? = null,
    @SerializedName("units") val units: String? = null,
    @SerializedName("isValid") val isValid: Boolean? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("notes") val notes: String? = null
)

data class NightscoutDeviceStatus(
    @SerializedName("_id") val id: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("date") val date: Long? = null,
    @SerializedName("openaps") val openaps: Map<String, Any?>? = null,
    @SerializedName("pump") val pump: Map<String, Any?>? = null,
    @SerializedName("uploader") val uploader: Map<String, Any?>? = null
)

data class NightscoutStatusResponse(
    @SerializedName("status") val status: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("version") val version: String? = null,
    @SerializedName("serverTime") val serverTime: String? = null
)

data class NightscoutTreatmentRequest(
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("date") val date: Long? = null,
    @SerializedName("mills") val mills: Long? = null,
    @SerializedName("eventType") val eventType: String,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("durationInMilliseconds") val durationInMilliseconds: Long? = null,
    @SerializedName("targetTop") val targetTop: Double? = null,
    @SerializedName("targetBottom") val targetBottom: Double? = null,
    @SerializedName("units") val units: String? = null,
    @SerializedName("carbs") val carbs: Double? = null,
    @SerializedName("insulin") val insulin: Double? = null,
    @SerializedName("isValid") val isValid: Boolean? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("notes") val notes: String? = null
)
