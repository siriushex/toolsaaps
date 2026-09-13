package io.aaps.copilot.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.aaps.copilot.data.local.entity.TherapyEventEntity

internal fun preserveCanonicalAapsCarbMetadata(
    incoming: JsonObject,
    existing: JsonObject
): JsonObject {
    if (!existing.hasAuthoritativeAapsCarbOwnership()) return incoming.deepCopy()
    val merged = incoming.deepCopy()
    CANONICAL_AAPS_CARB_PAYLOAD_KEYS.forEach { key ->
        if (existing.has(key)) {
            merged.add(key, existing.get(key).deepCopy())
        }
    }
    return merged
}

internal fun mergeTherapyEventWithCanonicalAapsCarbMetadata(
    incoming: TherapyEventEntity,
    latestExisting: TherapyEventEntity?,
    decodePayload: (String) -> JsonObject?,
    encodePayload: (JsonObject) -> String
): TherapyEventEntity {
    if (latestExisting == null) return incoming
    val incomingPayload = decodePayload(incoming.payloadJson) ?: return incoming
    val existingPayload = decodePayload(latestExisting.payloadJson) ?: return incoming
    if (!existingPayload.hasAuthoritativeAapsCarbOwnership()) return incoming
    val mergedPayload = preserveCanonicalAapsCarbMetadata(
        incoming = incomingPayload,
        existing = existingPayload
    )
    return incoming.copy(payloadJson = encodePayload(mergedPayload))
}

internal fun parseTherapyPayloadJsonObject(raw: String): JsonObject? = runCatching {
    JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
}.getOrNull()

private fun JsonObject.hasAuthoritativeAapsCarbOwnership(): Boolean {
    val id = get("aapsCarbId") ?: return false
    if (!id.isJsonPrimitive || !id.asJsonPrimitive.isNumber) return false
    val rawId = id.asJsonPrimitive.toString()
    if (rawId.isEmpty() || rawId.any { it !in '0'..'9' }) return false
    return rawId.toLongOrNull()?.let { it > 0L } == true
}

private val CANONICAL_AAPS_CARB_PAYLOAD_KEYS = setOf(
    "aapsCarbId",
    "aapsVersion",
    "aapsDateCreated",
    "aapsReferenceId",
    "aapsTimestamp",
    "aapsDuration",
    "aapsRevisionId",
    "aapsPageGeneratedAt",
    "aapsCarbAmount",
    "aapsCarbIsValid",
    "aapsCarbClassification",
    "aapsCarbSource",
    "aapsCarbSynthetic",
    "aapsCarbSuperseded",
    "aapsCarbSupersededBy",
    "notesSha256",
    "notesTruncated",
    "nightscoutId",
    "nightscoutIdSha256",
    "nightscoutIdTruncated",
    // Compatibility aliases retained if a prior producer persisted them.
    "aapsCarbVersion",
    "aapsCarbDateCreated",
    "aapsCarbReferenceId",
    "aapsCarbRevisionId",
    "aapsCarbIdentity",
    "aapsCarbDigest",
    "aapsCarbNotesSha256",
    "aapsCarbNotesTruncated",
    "aapsCarbNightscoutIdSha256",
    "aapsCarbNightscoutIdTruncated"
)
