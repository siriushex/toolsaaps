package io.aaps.copilot.data.repository

import com.google.gson.Gson
import io.aaps.copilot.data.local.entity.ForecastEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.BloodGlucoseCheckEntity
import io.aaps.copilot.data.local.entity.GlucoseCalibrationModelEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.domain.model.BloodGlucoseCheck
import io.aaps.copilot.domain.model.BloodGlucoseCheckStatus
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucoseCalibrationModel
import io.aaps.copilot.domain.model.GlucoseCalibrationModelStatus
import io.aaps.copilot.domain.model.GlucoseCalibrationModelType
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.TherapyEventComponentTrust

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

fun BloodGlucoseCheckEntity.toDomain(): BloodGlucoseCheck = BloodGlucoseCheck(
    id = id,
    timestamp = timestamp,
    mmol = mmol,
    units = units,
    source = source,
    note = note.takeIf { it.isNotBlank() },
    enteredAt = enteredAt,
    sensorSessionKey = sensorSessionKey,
    lagAlignedTs = lagAlignedTs,
    matchedRawGlucose = matchedRawGlucose,
    status = runCatching { BloodGlucoseCheckStatus.valueOf(status) }
        .getOrDefault(BloodGlucoseCheckStatus.REJECTED),
    reason = reason.takeIf { it.isNotBlank() }
)

fun BloodGlucoseCheck.toEntity(): BloodGlucoseCheckEntity = BloodGlucoseCheckEntity(
    id = id,
    timestamp = timestamp,
    mmol = mmol,
    units = units,
    source = source,
    note = note.orEmpty(),
    enteredAt = enteredAt,
    sensorSessionKey = sensorSessionKey,
    lagAlignedTs = lagAlignedTs,
    matchedRawGlucose = matchedRawGlucose,
    status = status.name,
    reason = reason.orEmpty()
)

fun GlucoseCalibrationModelEntity.toDomain(): GlucoseCalibrationModel = GlucoseCalibrationModel(
    id = id,
    sensorSessionKey = sensorSessionKey,
    createdAt = createdAt,
    validFromTs = validFromTs,
    validToTs = validToTs,
    modelType = runCatching { GlucoseCalibrationModelType.valueOf(modelType) }
        .getOrDefault(GlucoseCalibrationModelType.OFFSET),
    gain = gain,
    offsetMmol = offsetMmol,
    confidence = confidence,
    checkCount = checkCount,
    sensorAgeHours = sensorAgeHours,
    lagMinutesAtFit = lagMinutesAtFit,
    status = runCatching { GlucoseCalibrationModelStatus.valueOf(status) }
        .getOrDefault(GlucoseCalibrationModelStatus.RETIRED),
    diagnosticsJson = diagnosticsJson
)

fun GlucoseCalibrationModel.toEntity(): GlucoseCalibrationModelEntity = GlucoseCalibrationModelEntity(
    id = id,
    sensorSessionKey = sensorSessionKey,
    createdAt = createdAt,
    validFromTs = validFromTs,
    validToTs = validToTs,
    modelType = modelType.name,
    gain = gain,
    offsetMmol = offsetMmol,
    confidence = confidence,
    checkCount = checkCount,
    sensorAgeHours = sensorAgeHours,
    lagMinutesAtFit = lagMinutesAtFit,
    status = status.name,
    diagnosticsJson = diagnosticsJson
)

fun TherapyEventEntity.toDomain(gson: Gson): TherapyEvent {
    val decoded = decodeTherapyEventPayloadWithTrust(gson, payloadJson)
    return TherapyEvent(
        ts = timestamp,
        type = type,
        payload = decoded.payload,
        componentTrust = decoded.componentTrust,
        sourceRowId = id
    )
}

internal data class DecodedTherapyEventPayload(
    val payload: Map<String, String>,
    val componentTrust: TherapyEventComponentTrust
)

internal fun decodeTherapyEventPayloadWithTrust(
    @Suppress("UNUSED_PARAMETER") gson: Gson,
    payloadJson: String
): DecodedTherapyEventPayload {
    val parsed = BoundedClinicalPayloadParser.parse(payloadJson)
        ?: return DecodedTherapyEventPayload(emptyMap(), TherapyEventComponentTrust.NONE)
    val payload = parsed.scalarValues().toMutableMap().apply {
        parsed.exactPositiveJsonLong("aapsCarbId")?.let { put("aapsCarbId", it.toString()) }
    }
    return DecodedTherapyEventPayload(payload, parsed.componentTrust())
}

internal fun decodeTherapyEventPayload(
    gson: Gson,
    payloadJson: String
): Map<String, String> = decodeTherapyEventPayloadWithTrust(gson, payloadJson).payload

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
