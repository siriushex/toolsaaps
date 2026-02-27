package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent

fun GlucoseSampleEntity.toDomain(): GlucosePoint = GlucosePoint(
    ts = timestamp,
    valueMmol = mmol,
    source = source,
    quality = runCatching { DataQuality.valueOf(quality) }.getOrDefault(DataQuality.OK)
)

fun GlucosePoint.toEntity(): GlucoseSampleEntity = GlucoseSampleEntity(
    timestamp = ts,
    mmol = valueMmol,
    source = source,
    quality = quality.name
)

fun TherapyEventEntity.toDomain(gson: Gson): TherapyEvent {
    val mapType = object : TypeToken<Map<String, String>>() {}.type
    val payload = gson.fromJson<Map<String, String>>(payloadJson, mapType) ?: emptyMap()
    return TherapyEvent(
        ts = timestamp,
        type = type,
        payload = payload
    )
}

fun TherapyEvent.toEntity(gson: Gson, id: String): TherapyEventEntity = TherapyEventEntity(
    id = id,
    timestamp = ts,
    type = type,
    payloadJson = gson.toJson(payload)
)

fun Forecast.toEntity(): ForecastEntity = ForecastEntity(
    timestamp = ts,
    horizonMinutes = horizonMinutes,
    valueMmol = valueMmol,
    ciLow = ciLow,
    ciHigh = ciHigh,
    modelVersion = modelVersion
)

fun ForecastEntity.toDomain(): Forecast = Forecast(
    ts = timestamp,
    horizonMinutes = horizonMinutes,
    valueMmol = valueMmol,
    ciLow = ciLow,
    ciHigh = ciHigh,
    modelVersion = modelVersion
)
