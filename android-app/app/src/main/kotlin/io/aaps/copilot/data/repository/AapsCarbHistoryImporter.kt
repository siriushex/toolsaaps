package io.aaps.copilot.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.aaps.copilot.data.local.dao.TherapyDao
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

internal fun interface AapsCarbImportTransactionRunner {
    suspend fun runInTransaction(block: suspend () -> Unit)
}

internal class AapsCarbImportSupersededException :
    RuntimeException("AAPS carb import revision was superseded before commit")

private object DirectAapsCarbImportTransactionRunner : AapsCarbImportTransactionRunner {
    override suspend fun runInTransaction(block: suspend () -> Unit) = block()
}

internal class AapsCarbHistoryImporter(
    private val therapyDao: TherapyDao,
    private val transactionRunner: AapsCarbImportTransactionRunner,
    private val gson: Gson = Gson(),
    private val onCommittedPage: (AapsCarbHistoryPage) -> Unit = {},
    private val persistMealPage: suspend (AapsCarbHistoryPage) -> Unit = {}
) {

    /**
     * Test/default-only compatibility path. Task5 production wiring must provide a
     * Room-backed [AapsCarbImportTransactionRunner] through the primary constructor.
     */
    @Deprecated("Production must provide a Room-backed AapsCarbImportTransactionRunner")
    internal constructor(
        therapyDao: TherapyDao,
        gson: Gson = Gson()
    ) : this(
        therapyDao = therapyDao,
        transactionRunner = DirectAapsCarbImportTransactionRunner,
        gson = gson
    )

    suspend fun importPage(
        page: AapsCarbHistoryPage,
        commitGuard: () -> Boolean = { true }
    ) {
        transactionRunner.runInTransaction {
            val persistedById = linkedMapOf<String, TherapyEventEntity>()
            val plannedById = linkedMapOf<String, TherapyEventEntity>()
            page.rows.forEach { row ->
                val fromTs = subtractSaturated(row.timestamp, MATCH_WINDOW_MS)
                val toTs = addSaturated(row.timestamp, MATCH_WINDOW_MS)
                val persistedCandidates = therapyDao.carbCandidates(
                    fromTs = fromTs,
                    toTs = toTs
                )
                persistedCandidates.forEach { candidate ->
                    persistedById.putIfAbsent(candidate.id, candidate)
                }
                val candidates = overlayCandidates(
                    persisted = persistedCandidates,
                    planned = plannedById.values,
                    fromTs = fromTs,
                    toTs = toTs
                )
                val selection = selectTarget(row, candidates)
                val survivor = buildEntity(row, page, selection.survivor)
                val tombstones = selection.supersededPureCarbs.map { candidate ->
                    buildSupersededPureCarb(
                        entity = buildEntity(row, page, candidate),
                        survivorId = survivor.id
                    )
                }
                (listOf(survivor) + tombstones).forEach { entity ->
                    plannedById[entity.id] = entity
                }
            }
            val changed = plannedById.values.filter { planned ->
                persistedById[planned.id]
                    ?.let { persisted -> clinicallyEquivalent(persisted, planned) } != true
            }
            if (changed.isNotEmpty()) {
                therapyDao.upsertAll(changed)
            }
            persistMealPage(page)
            if (!commitGuard()) {
                throw AapsCarbImportSupersededException()
            }
        }
        // Notification is observational and outside the therapy transaction.
        try { onCommittedPage(page) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    private fun overlayCandidates(
        persisted: List<TherapyEventEntity>,
        planned: Collection<TherapyEventEntity>,
        fromTs: Long,
        toTs: Long
    ): List<TherapyEventEntity> {
        val candidatesById = linkedMapOf<String, TherapyEventEntity>()
        persisted.forEach { candidatesById[it.id] = it }
        planned.asSequence()
            .filter { candidate ->
                candidate.timestamp in fromTs..toTs &&
                    (
                        candidate.type.equals(TYPE_CARBS, ignoreCase = true) ||
                            candidate.type.equals(TYPE_MEAL_BOLUS, ignoreCase = true)
                        )
            }
            .forEach { candidatesById[it.id] = it }
        return candidatesById.values.sortedWith(
            compareBy<TherapyEventEntity> { it.timestamp }.thenBy { it.id }
        )
    }

    private fun clinicallyEquivalent(
        persisted: TherapyEventEntity,
        planned: TherapyEventEntity
    ): Boolean =
        persisted.id == planned.id &&
            persisted.timestamp == planned.timestamp &&
            persisted.type == planned.type &&
            comparablePayload(persisted.payloadJson) == comparablePayload(planned.payloadJson)

    private fun comparablePayload(raw: String): String {
        val payload = parseObject(raw)?.deepCopy() ?: return raw
        payload.remove(AAPS_REVISION_ID)
        payload.remove(AAPS_PAGE_GENERATED_AT)
        return gson.toJson(canonicalize(payload))
    }

    private fun selectTarget(
        row: AapsCarbHistoryRow,
        candidates: List<TherapyEventEntity>
    ): TargetSelection {
        val stableId = "aaps-carb-${row.id}"
        val parsed = candidates.mapNotNull { candidate ->
            parseObject(candidate.payloadJson)?.let { payload ->
                ParsedCandidate(
                    entity = candidate,
                    payload = payload,
                    aapsCarbId = payload.aapsCarbIdExact()
                )
            }
        }
        val active = parsed.filterNot { it.payload.booleanValue(AAPS_CARB_SUPERSEDED) == true }
        val identityMatches = active.filter { candidate ->
            (candidate.aapsCarbId == null || candidate.aapsCarbId == row.id) &&
                abs(candidate.entity.timestamp - row.timestamp) <= MATCH_WINDOW_MS &&
                candidate.payload.grams()
                    ?.let { abs(it - row.amount) <= GRAMS_EPSILON } == true &&
                hasSameIdentity(row, candidate.entity, candidate.payload)
        }
        val sameIdMatches = active.filter { it.aapsCarbId == row.id }
        val survivor = preferredCandidate(
            candidates = identityMatches.filter { it.entity.id != stableId },
            stableId = stableId
        ) ?: preferredCandidate(sameIdMatches, stableId)
            ?: preferredCandidate(identityMatches, stableId)
        val supersededPureCarbs = if (survivor != null && survivor.entity.id != stableId) {
            parsed.filter { candidate ->
                candidate.entity.id == stableId &&
                    candidate.entity.type.equals(TYPE_CARBS, ignoreCase = true)
            }.map(ParsedCandidate::entity)
        } else {
            emptyList()
        }
        return TargetSelection(
            survivor = survivor?.entity,
            supersededPureCarbs = supersededPureCarbs
        )
    }

    private fun preferredCandidate(
        candidates: List<ParsedCandidate>,
        stableId: String
    ): ParsedCandidate? = candidates
        .sortedWith(
            compareByDescending<ParsedCandidate> {
                it.entity.type.equals(TYPE_MEAL_BOLUS, ignoreCase = true) &&
                    it.payload.insulinUnits() != null
            }.thenByDescending { it.entity.id != stableId }
                .thenBy { it.entity.timestamp }
                .thenBy { it.entity.id }
        )
        .firstOrNull()

    private fun buildSupersededPureCarb(
        entity: TherapyEventEntity,
        survivorId: String
    ): TherapyEventEntity {
        val payload = parseObject(entity.payloadJson)?.deepCopy() ?: JsonObject()
        payload.addProperty(IS_VALID, false)
        payload.addProperty(AAPS_CARB_IS_VALID, false)
        payload.zeroEffectiveCarbs()
        payload.addProperty(AAPS_CARB_SUPERSEDED, true)
        payload.addProperty(AAPS_CARB_SUPERSEDED_BY, survivorId)
        return entity.copy(payloadJson = gson.toJson(canonicalize(payload)))
    }

    private fun hasSameIdentity(
        row: AapsCarbHistoryRow,
        candidate: TherapyEventEntity,
        payload: JsonObject
    ): Boolean {
        val candidateNightscoutIds = listOfNotNull(
            payload.stringValue(NIGHTSCOUT_ID).nonBlank(),
            payload.stringValue(NIGHTSCOUT_LEGACY_ID).nonBlank(),
            candidate.id.nonBlank()
        )
        val sameNightscoutId = identityMatches(
            incomingValue = row.nightscoutId,
            incomingDigest = row.nightscoutIdSha256,
            truncated = row.nightscoutIdTruncated,
            candidateValues = candidateNightscoutIds
        )
        val sameNotes = identityMatches(
            incomingValue = row.notes,
            incomingDigest = row.notesSha256,
            truncated = row.notesTruncated,
            candidateValues = listOfNotNull(payload.stringValue(NOTES).nonBlank())
        )
        return sameNightscoutId || sameNotes
    }

    private fun identityMatches(
        incomingValue: String?,
        incomingDigest: String?,
        truncated: Boolean,
        candidateValues: List<String>
    ): Boolean {
        val incoming = incomingValue.nonBlank() ?: return false
        if (candidateValues.isEmpty()) return false
        if (!truncated) return candidateValues.any { it == incoming }

        val digest = incomingDigest.validSha256() ?: return false
        return candidateValues.any { candidateValue ->
            sha256(candidateValue) == digest
        }
    }

    private fun buildEntity(
        row: AapsCarbHistoryRow,
        page: AapsCarbHistoryPage,
        target: TherapyEventEntity?
    ): TherapyEventEntity {
        val stableId = "aaps-carb-${row.id}"
        val payload = target?.payloadJson?.let(::parseObject)?.deepCopy()
            ?: JsonObject()
        val isCanonicalOwnedTarget = target != null &&
            (payload.aapsCarbIdExact() != null || target.id == stableId)
        val preserveMissingIdentity = target != null && !isCanonicalOwnedTarget
        val isInsulinBearingMealBolus = target?.type.equals(
            TYPE_MEAL_BOLUS,
            ignoreCase = true
        ) && payload.insulinUnits() != null
        val classification = when {
            row.amount < 0.0 -> CLASSIFICATION_CORRECTION
            row.notes?.startsWith(UAM_PREFIX) == true -> CLASSIFICATION_UAM
            else -> CLASSIFICATION_REAL
        }

        payload.addProperty(AAPS_CARB_ID, row.id)
        payload.addProperty(AAPS_VERSION, row.version)
        payload.addProperty(AAPS_DATE_CREATED, row.dateCreated)
        payload.replaceNullable(AAPS_REFERENCE_ID, row.referenceId)
        payload.addProperty(AAPS_TIMESTAMP, row.timestamp)
        payload.addProperty(AAPS_DURATION, row.duration)
        payload.addProperty(AAPS_REVISION_ID, page.revisionId)
        payload.addProperty(AAPS_PAGE_GENERATED_AT, page.generatedAt)
        payload.addProperty(AAPS_CARB_AMOUNT, row.amount)
        if (row.isValid) {
            payload.addProperty(CARBS, row.amount)
        } else {
            payload.zeroEffectiveCarbs()
        }
        payload.addProperty(AAPS_CARB_IS_VALID, row.isValid)
        payload.addProperty(AAPS_CARB_CLASSIFICATION, classification)
        payload.addProperty(AAPS_CARB_SOURCE, SOURCE_AAPS_DIRECT_HISTORY)
        payload.addProperty(AAPS_CARB_SYNTHETIC, classification == CLASSIFICATION_UAM)
        payload.remove(AAPS_CARB_SUPERSEDED)
        payload.remove(AAPS_CARB_SUPERSEDED_BY)
        if (!isInsulinBearingMealBolus) {
            payload.addProperty(IS_VALID, row.isValid)
            payload.addProperty(CLASSIFICATION, classification)
            payload.addProperty(SOURCE, SOURCE_AAPS_DIRECT_HISTORY)
            payload.addProperty(SYNTHETIC, classification == CLASSIFICATION_UAM)
        }
        payload.mergeIdentity(
            valueKey = NOTES,
            digestKey = NOTES_SHA256,
            truncatedKey = NOTES_TRUNCATED,
            incomingValue = row.notes,
            incomingDigest = row.notesSha256,
            incomingTruncated = row.notesTruncated,
            candidateValues = listOfNotNull(payload.stringValue(NOTES).nonBlank()),
            preserveMissing = preserveMissingIdentity
        )
        payload.mergeIdentity(
            valueKey = NIGHTSCOUT_ID,
            digestKey = NIGHTSCOUT_ID_SHA256,
            truncatedKey = NIGHTSCOUT_ID_TRUNCATED,
            incomingValue = row.nightscoutId,
            incomingDigest = row.nightscoutIdSha256,
            incomingTruncated = row.nightscoutIdTruncated,
            candidateValues = listOfNotNull(
                payload.stringValue(NIGHTSCOUT_ID).nonBlank(),
                payload.stringValue(NIGHTSCOUT_LEGACY_ID).nonBlank(),
                target?.id.nonBlank()
            ),
            preserveMissing = preserveMissingIdentity
        )

        val isStableCanonicalTarget = target?.id == stableId
        return TherapyEventEntity(
            id = target?.id ?: stableId,
            timestamp = if (target == null || isStableCanonicalTarget) {
                row.timestamp
            } else {
                target.timestamp
            },
            type = target?.type ?: TYPE_CARBS,
            payloadJson = gson.toJson(canonicalize(payload))
        )
    }

    private fun parseObject(raw: String): JsonObject? = runCatching {
        JsonParser.parseString(raw) as? JsonObject
    }.getOrNull()

    private fun canonicalize(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().also { result ->
            element.asJsonObject.entrySet()
                .sortedBy { it.key }
                .forEach { (key, value) -> result.add(key, canonicalize(value)) }
        }

        element.isJsonArray -> JsonArray().also { result ->
            element.asJsonArray.forEach { result.add(canonicalize(it)) }
        }

        else -> element.deepCopy()
    }

    private fun JsonObject.grams(): Double? =
        listOf(CARBS, GRAMS, ENTERED_CARBS, MEAL_CARBS)
            .firstNotNullOfOrNull { key -> doubleValue(key) }

    private fun JsonObject.insulinUnits(): Double? =
        listOf(UNITS, BOLUS_UNITS, INSULIN_UNITS, INSULIN, ENTERED_INSULIN)
            .firstNotNullOfOrNull { key -> doubleValue(key) }

    private fun JsonObject.zeroEffectiveCarbs() {
        addProperty(CARBS, 0.0)
        listOf(GRAMS, ENTERED_CARBS, MEAL_CARBS)
            .filter(::has)
            .forEach { key -> addProperty(key, 0.0) }
    }

    private fun JsonObject.doubleValue(key: String): Double? =
        primitive(key)?.let { primitive ->
            runCatching {
                if (primitive.isNumber) primitive.asDouble else primitive.asString.toDouble()
            }.getOrNull()
        }?.takeIf(Double::isFinite)

    private fun JsonObject.aapsCarbIdExact(): Long? {
        val primitive = primitive(AAPS_CARB_ID)?.takeIf { it.isNumber } ?: return null
        val raw = primitive.asString
        if (raw.isEmpty() || raw.first() !in '1'..'9') return null
        if (raw.any { it !in '0'..'9' }) return null
        return raw.toLongOrNull()
    }

    private fun JsonObject.stringValue(key: String): String? =
        primitive(key)?.takeIf { it.isString }?.asString

    private fun JsonObject.booleanValue(key: String): Boolean? =
        primitive(key)?.takeIf { it.isBoolean }?.asBoolean

    private fun JsonObject.primitive(key: String): JsonPrimitive? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.replaceNullable(key: String, value: String?) {
        remove(key)
        value?.let { addProperty(key, it) }
    }

    private fun JsonObject.replaceNullable(key: String, value: Long?) {
        remove(key)
        value?.let { addProperty(key, it) }
    }

    private fun JsonObject.mergeIdentity(
        valueKey: String,
        digestKey: String,
        truncatedKey: String,
        incomingValue: String?,
        incomingDigest: String?,
        incomingTruncated: Boolean,
        candidateValues: List<String>,
        preserveMissing: Boolean
    ) {
        val incoming = incomingValue.nonBlank()
        if (incoming == null) {
            if (preserveMissing) return
            remove(valueKey)
            remove(digestKey)
            addProperty(truncatedKey, incomingTruncated)
            return
        }
        val valueToPersist = resolveIdentityValue(
            incomingValue = incoming,
            incomingDigest = incomingDigest,
            incomingTruncated = incomingTruncated,
            candidateValues = candidateValues,
        ) ?: return
        replaceNullable(valueKey, valueToPersist)
        replaceNullable(digestKey, incomingDigest)
        addProperty(truncatedKey, incomingTruncated)
    }

    private fun resolveIdentityValue(
        incomingValue: String,
        incomingDigest: String?,
        incomingTruncated: Boolean,
        candidateValues: List<String>
    ): String? {
        if (!incomingTruncated) return incomingValue
        val digest = incomingDigest.validSha256() ?: return null
        candidateValues.firstOrNull { candidateValue ->
            sha256(candidateValue) == digest
        }?.let { return it }
        return incomingValue
    }

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

    private fun String?.validSha256(): String? = this?.takeIf { value ->
        value.length == SHA_256_HEX_LENGTH &&
            value.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(SHA_256_HEX_LENGTH) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0f])
            }
        }
    }

    private fun subtractSaturated(value: Long, delta: Long): Long =
        if (value < Long.MIN_VALUE + delta) Long.MIN_VALUE else value - delta

    private fun addSaturated(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

    private companion object {
        const val MATCH_WINDOW_MS = 2_000L
        const val GRAMS_EPSILON = 0.01
        const val SHA_256_HEX_LENGTH = 64
        const val HEX_DIGITS = "0123456789abcdef"
        const val UAM_PREFIX = "UAM_ENGINE|"
        const val TYPE_CARBS = "carbs"
        const val TYPE_MEAL_BOLUS = "meal_bolus"

        const val CLASSIFICATION_REAL = "AAPS_REAL"
        const val CLASSIFICATION_UAM = "UAM_SYNTHETIC"
        const val CLASSIFICATION_CORRECTION = "AAPS_CORRECTION"
        const val SOURCE_AAPS_DIRECT_HISTORY = "aaps_direct_history"

        const val AAPS_CARB_ID = "aapsCarbId"
        const val AAPS_VERSION = "aapsVersion"
        const val AAPS_DATE_CREATED = "aapsDateCreated"
        const val AAPS_REFERENCE_ID = "aapsReferenceId"
        const val AAPS_TIMESTAMP = "aapsTimestamp"
        const val AAPS_DURATION = "aapsDuration"
        const val AAPS_REVISION_ID = "aapsRevisionId"
        const val AAPS_PAGE_GENERATED_AT = "aapsPageGeneratedAt"
        const val AAPS_CARB_AMOUNT = "aapsCarbAmount"
        const val AAPS_CARB_IS_VALID = "aapsCarbIsValid"
        const val AAPS_CARB_CLASSIFICATION = "aapsCarbClassification"
        const val AAPS_CARB_SOURCE = "aapsCarbSource"
        const val AAPS_CARB_SYNTHETIC = "aapsCarbSynthetic"
        const val AAPS_CARB_SUPERSEDED = "aapsCarbSuperseded"
        const val AAPS_CARB_SUPERSEDED_BY = "aapsCarbSupersededBy"
        const val IS_VALID = "isValid"
        const val CARBS = "carbs"
        const val GRAMS = "grams"
        const val ENTERED_CARBS = "enteredCarbs"
        const val MEAL_CARBS = "mealCarbs"
        const val UNITS = "units"
        const val BOLUS_UNITS = "bolusUnits"
        const val INSULIN_UNITS = "insulinUnits"
        const val INSULIN = "insulin"
        const val ENTERED_INSULIN = "enteredInsulin"
        const val CLASSIFICATION = "classification"
        const val SOURCE = "source"
        const val SYNTHETIC = "synthetic"
        const val NOTES = "notes"
        const val NOTES_SHA256 = "notesSha256"
        const val NOTES_TRUNCATED = "notesTruncated"
        const val NIGHTSCOUT_ID = "nightscoutId"
        const val NIGHTSCOUT_LEGACY_ID = "_id"
        const val NIGHTSCOUT_ID_SHA256 = "nightscoutIdSha256"
        const val NIGHTSCOUT_ID_TRUNCATED = "nightscoutIdTruncated"
    }

    private data class ParsedCandidate(
        val entity: TherapyEventEntity,
        val payload: JsonObject,
        val aapsCarbId: Long?
    )

    private data class TargetSelection(
        val survivor: TherapyEventEntity?,
        val supersededPureCarbs: List<TherapyEventEntity>
    )
}
