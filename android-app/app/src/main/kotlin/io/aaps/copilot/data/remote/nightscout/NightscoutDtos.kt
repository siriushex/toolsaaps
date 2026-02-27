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
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("eventType") val eventType: String,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("targetTop") val targetTop: Double? = null,
    @SerializedName("targetBottom") val targetBottom: Double? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("notes") val notes: String? = null
)

data class NightscoutTreatmentRequest(
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("eventType") val eventType: String,
    @SerializedName("duration") val duration: Int,
    @SerializedName("targetTop") val targetTop: Double,
    @SerializedName("targetBottom") val targetBottom: Double,
    @SerializedName("reason") val reason: String,
    @SerializedName("notes") val notes: String
)
