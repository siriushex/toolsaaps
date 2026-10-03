package io.aaps.copilot.data.repository

import java.math.BigDecimal
import io.aaps.copilot.data.local.dao.EnergyProfileDao
import io.aaps.copilot.data.local.entity.EnergyProfileSnapshotEntity
import io.aaps.copilot.data.local.entity.GlucoseSampleEntity
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.PendingMealProfileIntentEntity
import io.aaps.copilot.data.local.entity.PlannedActivityEventEntity
import io.aaps.copilot.data.local.entity.TelemetrySampleEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.config.AppSettings
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.model.ActionCommand
import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.profile.FoodProfileMode
import io.aaps.copilot.domain.profile.ActivityScheduleEngine
import io.aaps.copilot.domain.profile.EnergyInferenceMeal
import io.aaps.copilot.domain.profile.EnergyInferenceResult
import io.aaps.copilot.domain.profile.EnergyProfileInference
import io.aaps.copilot.domain.profile.EnergyProfileInferenceInput
import io.aaps.copilot.domain.profile.EnergyProfileSettings
import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealAbsorptionContext
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionSelection
import io.aaps.copilot.domain.profile.MealTherapyReferenceTrust
import io.aaps.copilot.domain.profile.PlannedActivitySchedule
import io.aaps.copilot.domain.profile.PlannedActivityScheduleSaveResult
import io.aaps.copilot.domain.profile.ScheduleValidation
import io.aaps.copilot.domain.profile.toMealTherapyReference
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

data class EnergyProfileInferenceDataSource(
    val loadSettings: suspend () -> EnergyProfileSettings,
    val loadTherapy: suspend (Long, Long) -> List<TherapyEventEntity>,
    val loadGlucose: suspend (Long, Long) -> List<GlucoseSampleEntity>,
    val loadTelemetry: suspend (Long, Long) -> List<TelemetrySampleEntity>,
    val decodeTherapy: (TherapyEventEntity) -> TherapyEvent
)

sealed interface EnergyProfileInferenceRefreshResult {
    data object Unavailable : EnergyProfileInferenceRefreshResult
    data class Reused(val snapshot: EnergyProfileSnapshotEntity) : EnergyProfileInferenceRefreshResult
    data class Stored(
        val snapshot: EnergyProfileSnapshotEntity,
        val inference: EnergyInferenceResult
    ) : EnergyProfileInferenceRefreshResult
}

sealed interface CanonicalCarbActionResult {
    data class Canonical(
        val therapyIdentity: String,
        val therapyRevision: String
    ) : CanonicalCarbActionResult

    data object NoCanonicalReference : CanonicalCarbActionResult
}

class EnergyProfileRepository(
    private val energyProfileDao: EnergyProfileDao,
    private val mealEnergyOverrideRepository: MealEnergyOverrideRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val activityScheduleEngine: ActivityScheduleEngine = ActivityScheduleEngine(),
    private val inferenceDataSource: EnergyProfileInferenceDataSource? = null,
    private val inference: EnergyProfileInference = EnergyProfileInference()
) {
    /**
     * Persists a diagnostic-only, idempotent snapshot for the previous local day.
     * No inference result is consumed by prediction, Target Manager, or therapy.
     */
    suspend fun refreshInference(
        now: Long = clock(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): EnergyProfileInferenceRefreshResult {
        val source = inferenceDataSource ?: return EnergyProfileInferenceRefreshResult.Unavailable
        val completedDate = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate().minusDays(1)
        val evidenceStart = completedDate.minusDays((MAX_INFERENCE_DAYS - 1).toLong())
            .atStartOfDay(zoneId).toInstant().toEpochMilli()
        val evidenceEnd = completedDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1L
        val settings = source.loadSettings()
        val therapyRows = source.loadTherapy(evidenceStart, evidenceEnd)
        val glucoseRows = source.loadGlucose(evidenceStart, evidenceEnd)
        val telemetryRows = source.loadTelemetry(evidenceStart, evidenceEnd)
        val mealEvidence = mealEvidence(
            therapyRows = therapyRows,
            glucoseRows = glucoseRows,
            telemetryRows = telemetryRows,
            decodeTherapy = source.decodeTherapy
        )
        val result = inference.infer(
            EnergyProfileInferenceInput(
                completedLocalDate = completedDate,
                zoneId = zoneId,
                settings = settings,
                meals = mealEvidence,
                // Activity telemetry has no persisted reconciliation marker yet.
                activities = emptyList()
            )
        )
        val snapshotId = "$INFERENCE_SNAPSHOT_PREFIX:${completedDate}:${result.sourceHashSha256}"
        energyProfileDao.snapshot(snapshotId)?.let { existing ->
            return EnergyProfileInferenceRefreshResult.Reused(existing)
        }
        val snapshot = EnergyProfileSnapshotEntity(
            snapshotId = snapshotId,
            schemaVersion = INFERENCE_SCHEMA_VERSION,
            evidenceStartMs = evidenceStart,
            evidenceEndMs = evidenceEnd,
            qualityDays = result.qualityDays,
            tier = result.tier.name,
            foodProfile = result.foodProfile?.name,
            foodDurationMinutes = result.foodDurationMinutes,
            activityProfile = result.activityProfile?.name,
            confidence = result.confidence,
            // No canonical replay source exists yet. Keep all downstream influence disabled.
            replayPassed = false,
            sourceHashSha256 = result.sourceHashSha256,
            calculatedAtMs = now,
            stale = true
        )
        energyProfileDao.insertSnapshotAndTrim(snapshot)
        return EnergyProfileInferenceRefreshResult.Stored(snapshot, result)
    }

    suspend fun savePlannedActivitySchedule(
        events: List<PlannedActivitySchedule>
    ): PlannedActivityScheduleSaveResult {
        val validation = activityScheduleEngine.validate(events)
        if (validation != ScheduleValidation.Valid) {
            return PlannedActivityScheduleSaveResult.Invalid(validation)
        }
        energyProfileDao.replaceEvents(events.map { event -> event.toEntity() })
        return PlannedActivityScheduleSaveResult.Saved
    }

    suspend fun resolveMealOverride(
        therapyIdentity: String,
        therapyRevision: String
    ): MealAbsorptionSelection? {
        if (therapyIdentity.isBlank() || therapyRevision.isBlank()) return null
        val override = energyProfileDao.resolveMealOverrideForRevision(
            canonicalTherapyIdentity = therapyIdentity,
            expectedTherapyRevisionHash = therapyRevision
        ) ?: return null
        val profile = runCatching { MealAbsorptionProfile.valueOf(override.profile) }.getOrNull()
            ?: return null
        val curve = MealAbsorptionCurve(profile, override.durationMinutes)
        return MealAbsorptionSelection(
            profile = curve.profile,
            durationMinutes = curve.durationMinutes,
            therapyRevision = override.therapyRevisionHash
        )
    }

    suspend fun mealAbsorptionContext(
        settings: AppSettings,
        therapy: List<TherapyEvent>
    ): MealAbsorptionContext {
        val profileSettings = settings.energyProfile
        if (!profileSettings.enabled) return MealAbsorptionContext.DISABLED
        val overrides = linkedMapOf<String, MealAbsorptionSelection>()
        therapy.map(TherapyEvent::toMealTherapyReference)
            .filter { it.trust == MealTherapyReferenceTrust.TRUSTED }
            .distinctBy { it.identity to it.revision }
            .forEach { reference ->
                val revision = reference.revision ?: return@forEach
                resolveMealOverride(reference.identity, revision)?.let { selection ->
                    overrides[reference.identity] = selection
                }
            }
        val manual = profileSettings.manualFoodProfile.takeIf {
            profileSettings.foodProfileMode == FoodProfileMode.MANUAL
        }?.let { MealAbsorptionSelection(it) }
        return MealAbsorptionContext(
            enabled = true,
            perMealOverrides = overrides,
            manual = manual
        )
    }

    suspend fun stageSelectionAfterSubmittedCarbAction(
        command: ActionCommand,
        selection: MealAbsorptionSelection,
        manualMealEnergyKcal: Double? = null,
        portion: io.aaps.copilot.domain.nutrition.MealPortion? = null,
        portionProvenance: io.aaps.copilot.domain.nutrition.MealPortionProvenance? = null
    ) {
        val idempotencyKey = command.idempotencyKey.trim()
        val expectedCarbsGrams = command.expectedCarbsGramsOrNull()
        if (
            idempotencyKey.isBlank() || expectedCarbsGrams == null ||
            !expectedCarbsGrams.isFinite() || expectedCarbsGrams <= 0.0 ||
            manualMealEnergyKcal != null &&
            !MealEnergyOverrideRepository.isValidManualMealEnergyKcal(manualMealEnergyKcal)
        ) {
            return
        }
        val curve = MealAbsorptionCurve(selection.profile, selection.durationMinutes)
        val submittedAtMs = clock()
        energyProfileDao.upsertPendingMealProfileIntent(
            PendingMealProfileIntentEntity(
                idempotencyKey = idempotencyKey,
                copilotNote = "copilot:$idempotencyKey",
                profile = curve.profile.name,
                durationMinutes = curve.durationMinutes,
                expectedCarbsGrams = expectedCarbsGrams,
                manualMealEnergyKcal = manualMealEnergyKcal,
                submittedAtMs = submittedAtMs,
                expiresAtMs = submittedAtMs + PENDING_INTENT_TTL_MS,
                portion = portion?.name,
                portionProvenance = portionProvenance?.name
            )
        )
    }

    fun canonicalReferenceForCopilotAction(
        command: ActionCommand,
        therapy: List<TherapyEvent>
    ): CanonicalCarbActionResult {
        val expectedCarbsGrams = command.expectedCarbsGramsOrNull()
            ?: return CanonicalCarbActionResult.NoCanonicalReference
        return canonicalReferenceForCopilotNote(
            expectedNote = "copilot:${command.idempotencyKey}",
            expectedCarbsGrams = expectedCarbsGrams,
            therapy = therapy
        )
    }

    internal suspend fun refreshPendingSelections(therapy: List<TherapyEvent>) {
        val nowMs = clock()
        energyProfileDao.deleteExpiredPendingMealProfileIntents(nowMs)
        energyProfileDao.activePendingMealProfileIntents(nowMs).forEach { intent ->
            val result = canonicalReferenceForCopilotNote(
                expectedNote = intent.copilotNote,
                expectedCarbsGrams = intent.expectedCarbsGrams,
                therapy = therapy
            )
            if (result !is CanonicalCarbActionResult.Canonical) return@forEach
            val selection = intent.toSelectionOrNull() ?: return@forEach
            if (!saveManualMealEnergyIfPresent(intent, result)) return@forEach
            energyProfileDao.promotePendingMealProfileIntent(
                expectedIntent = intent,
                override = overrideEntity(selection, result, nowMs).copy(
                    portion = intent.portion,
                    portionProvenance = intent.portionProvenance,
                    confirmedCarbsGrams = intent.expectedCarbsGrams.takeIf {
                        intent.portion != null && intent.portionProvenance != null
                    }
                ),
                nowMs = nowMs
            )
        }
    }

    private suspend fun saveManualMealEnergyIfPresent(
        intent: PendingMealProfileIntentEntity,
        result: CanonicalCarbActionResult.Canonical
    ): Boolean {
        val caloriesKcal = intent.manualMealEnergyKcal ?: return true
        val repository = mealEnergyOverrideRepository ?: return false
        return repository.saveForTrustedCanonicalMeal(
            canonicalTherapyIdentity = result.therapyIdentity,
            therapyRevisionHash = result.therapyRevision,
            caloriesKcal = caloriesKcal
        ) is ManualMealEnergySaveResult.Saved
    }

    private fun canonicalReferenceForCopilotNote(
        expectedNote: String,
        expectedCarbsGrams: Double,
        therapy: List<TherapyEvent>
    ): CanonicalCarbActionResult {
        if (expectedNote.isBlank() || !expectedCarbsGrams.isFinite() || expectedCarbsGrams <= 0.0) {
            return CanonicalCarbActionResult.NoCanonicalReference
        }
        val candidates = therapy.filter { event ->
            event.payload["note"] == expectedNote || event.payload["notes"] == expectedNote
        }
        val candidate = candidates.singleOrNull() ?: return CanonicalCarbActionResult.NoCanonicalReference
        val noteAliases = listOfNotNull(candidate.payload["note"], candidate.payload["notes"])
        if (noteAliases.isEmpty() || noteAliases.any { it != expectedNote }) {
            return CanonicalCarbActionResult.NoCanonicalReference
        }
        val reference = candidate.toMealTherapyReference()
        if (reference.trust != MealTherapyReferenceTrust.TRUSTED || reference.revision == null) {
            return CanonicalCarbActionResult.NoCanonicalReference
        }
        val components = resolveTherapyComponents(candidate)
        val canonicalCarbsGrams = components.carbsG
        if (
            !components.canonicalCarbAuthoritative ||
            components.carbKind != TherapyCarbComponentKind.REAL ||
            canonicalCarbsGrams == null ||
            !canonicalCarbsMatch(expectedCarbsGrams, canonicalCarbsGrams)
        ) {
            return CanonicalCarbActionResult.NoCanonicalReference
        }
        return CanonicalCarbActionResult.Canonical(
            therapyIdentity = reference.identity,
            therapyRevision = checkNotNull(reference.revision)
        )
    }

    private fun ActionCommand.expectedCarbsGramsOrNull(): Double? =
        params["carbsGrams"]?.toDoubleOrNull()
            ?: params["carbs"]?.toDoubleOrNull()
            ?: params["grams"]?.toDoubleOrNull()

    private fun canonicalCarbsMatch(expectedCarbsGrams: Double, canonicalCarbsGrams: Double): Boolean {
        if (!expectedCarbsGrams.isFinite() || !canonicalCarbsGrams.isFinite()) return false
        return BigDecimal.valueOf(canonicalCarbsGrams)
            .subtract(BigDecimal.valueOf(expectedCarbsGrams))
            .abs()
            .compareTo(CANONICAL_CARB_MATCH_TOLERANCE_GRAMS) <= 0
    }

    private fun PendingMealProfileIntentEntity.toSelectionOrNull(): MealAbsorptionSelection? {
        val profile = runCatching { MealAbsorptionProfile.valueOf(profile) }.getOrNull() ?: return null
        val curve = MealAbsorptionCurve(profile, durationMinutes)
        return MealAbsorptionSelection(curve.profile, curve.durationMinutes)
    }

    private fun PlannedActivitySchedule.toEntity(): PlannedActivityEventEntity =
        PlannedActivityEventEntity(
            eventId = eventId,
            enabled = enabled,
            title = title,
            activityType = type.name,
            intensity = intensity.name,
            localStartIso = localStart.toString(),
            durationMinutes = durationMinutes,
            timezoneId = timezoneId,
            recurrenceDaysMask = recurrenceDays.fold(0) { mask, day ->
                mask or (1 shl (day.value - 1))
            },
            recurrenceEndEpochDay = recurrenceEndEpochDay,
            revision = revision,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs
        )

    private fun overrideEntity(
        selection: MealAbsorptionSelection,
        result: CanonicalCarbActionResult.Canonical,
        updatedAtMs: Long
    ): MealProfileOverrideEntity {
        val curve = MealAbsorptionCurve(selection.profile, selection.durationMinutes)
        return MealProfileOverrideEntity(
            canonicalTherapyIdentity = result.therapyIdentity,
            therapyRevisionHash = result.therapyRevision,
            profile = curve.profile.name,
            durationMinutes = curve.durationMinutes,
            source = SOURCE_COPILOT_UI,
            revision = OVERRIDE_SCHEMA_REVISION,
            updatedAtMs = updatedAtMs
        )
    }

    private fun mealEvidence(
        therapyRows: List<TherapyEventEntity>,
        glucoseRows: List<GlucoseSampleEntity>,
        telemetryRows: List<TelemetrySampleEntity>,
        decodeTherapy: (TherapyEventEntity) -> TherapyEvent
    ): List<EnergyInferenceMeal> {
        val glucose = glucoseRows.asSequence()
            .filter { sample -> sample.mmol.isFinite() && sample.quality.equals("OK", ignoreCase = true) }
            .groupBy(GlucoseSampleEntity::timestamp)
            .toSortedMap()
            .values
            .map { duplicates -> duplicates.minWith(compareBy<GlucoseSampleEntity> { it.source }.thenBy { it.id }) }
            .toList()
        val blockedAt = telemetryRows.asSequence()
            .filter { row -> row.key.equals("sensor_block", ignoreCase = true) }
            .filter { row -> row.valueDouble?.let { it.isFinite() && it > 0.0 } == true || row.valueText.equals("true", true) }
            .map(TelemetrySampleEntity::timestamp)
            .toList()
        val realMeals = TherapySanitizer.filterEntities(therapyRows).mapNotNull { row ->
            val event = runCatching { decodeTherapy(row) }.getOrNull() ?: return@mapNotNull null
            val components = resolveTherapyComponents(event)
            val carbs = components.carbsG
            if (components.carbKind != TherapyCarbComponentKind.REAL || carbs == null) return@mapNotNull null
            MealCandidate(
                row = row,
                event = event,
                canonicalIdentity = components.canonicalCarbId?.toString() ?: row.id,
                revision = event.componentTrust.canonicalCarbRevision ?: payloadSha256(row.payloadJson),
                carbsG = carbs,
                therapyCovered = components.wholeEventValid || components.canonicalCarbAuthoritative
            )
        }
        return realMeals.mapNotNull { candidate ->
            val baseline = glucose.asSequence()
                .filter { sample ->
                    sample.timestamp in (candidate.event.ts - PRE_MEAL_BASELINE_WINDOW_MS)..
                        (candidate.event.ts - PRE_MEAL_BASELINE_GAP_MS)
                }
                .map(GlucoseSampleEntity::mmol)
                .toList()
                .medianOrNull()
                ?: return@mapNotNull null
            val post = glucose.filter { sample ->
                sample.timestamp in (candidate.event.ts + POST_MEAL_MIN_MS)..(candidate.event.ts + POST_MEAL_MAX_MS)
            }
            val postCovered = post.size >= MIN_POST_MEAL_GLUCOSE_POINTS &&
                (post.lastOrNull()?.timestamp ?: Long.MIN_VALUE) - (post.firstOrNull()?.timestamp ?: Long.MAX_VALUE) >=
                MIN_POST_MEAL_GLUCOSE_SPAN_MS
            val overlaps = realMeals.any { other ->
                other !== candidate && kotlin.math.abs(other.event.ts - candidate.event.ts) < MEAL_OVERLAP_WINDOW_MS
            }
            val sensorBlocked = blockedAt.any { it in candidate.event.ts..(candidate.event.ts + POST_MEAL_MAX_MS) }
            val duration = settledDurationMinutes(
                mealTs = candidate.event.ts,
                baselineMmol = baseline,
                postMealGlucose = post,
                therapyNotBeforeTs = relevantTherapyNotBefore(candidate, therapyRows)
            ) ?: return@mapNotNull null
            EnergyInferenceMeal(
                occurredAtMs = candidate.event.ts,
                canonicalIdentity = candidate.canonicalIdentity,
                revision = candidate.revision,
                carbsG = candidate.carbsG,
                absorptionMinutes = duration,
                postMealGlucoseCovered = postCovered,
                therapyCovered = candidate.therapyCovered,
                overlapsAnotherMeal = overlaps,
                staleOrSensorBlocked = sensorBlocked,
                superseded = false
            )
        }
    }

    private fun settledDurationMinutes(
        mealTs: Long,
        baselineMmol: Double,
        postMealGlucose: List<GlucoseSampleEntity>,
        therapyNotBeforeTs: Long?
    ): Int? {
        if (!baselineMmol.isFinite()) return null
        val peak = postMealGlucose.maxWithOrNull(
            compareBy<GlucoseSampleEntity> { it.mmol }.thenByDescending { it.timestamp }
        ) ?: return null
        if (peak.mmol - baselineMmol < MIN_MEAL_EXCURSION_MMOL) return null
        val notBefore = maxOf(
            mealTs + MIN_SETTLED_DURATION_MS,
            peak.timestamp,
            therapyNotBeforeTs ?: Long.MIN_VALUE
        )
        val candidates = postMealGlucose.filter { it.timestamp >= notBefore }
        candidates.forEachIndexed { index, point ->
            if (abs(point.mmol - baselineMmol) > SETTLED_BASELINE_TOLERANCE_MMOL) return@forEachIndexed
            val confirmingPoint = candidates.drop(index + 1).firstOrNull { next ->
                next.timestamp - point.timestamp in MIN_STABLE_CGM_GAP_MS..MAX_STABLE_CGM_GAP_MS
            } ?: return@forEachIndexed
            if (
                abs(confirmingPoint.mmol - baselineMmol) <= SETTLED_BASELINE_TOLERANCE_MMOL &&
                abs(confirmingPoint.mmol - point.mmol) <= MAX_STABLE_CGM_DELTA_MMOL
            ) {
                return ((point.timestamp - mealTs) / 60_000L).toInt()
                    .takeIf { it in MIN_SETTLED_DURATION_MINUTES..MAX_SETTLED_DURATION_MINUTES }
            }
        }
        return null
    }

    private fun relevantTherapyNotBefore(
        candidate: MealCandidate,
        therapyRows: List<TherapyEventEntity>
    ): Long? = therapyRows.asSequence()
        .filter { row ->
            row.id != candidate.row.id &&
                row.timestamp > candidate.event.ts &&
                row.timestamp <= candidate.event.ts + POST_MEAL_MAX_MS &&
                row.type.lowercase() in THERAPY_TIMING_TYPES
        }
        .map(TherapyEventEntity::timestamp)
        .maxOrNull()
        ?.plus(THERAPY_SETTLING_DELAY_MS)

    private fun List<Double>.medianOrNull(): Double? =
        sorted().getOrNull((size - 1) / 2)

    private data class MealCandidate(
        val row: TherapyEventEntity,
        val event: TherapyEvent,
        val canonicalIdentity: String,
        val revision: String,
        val carbsG: Double,
        val therapyCovered: Boolean
    )

    private fun payloadSha256(payload: String): String = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val SOURCE_COPILOT_UI = "COPILOT_UI"
        const val OVERRIDE_SCHEMA_REVISION = 1L
        const val PENDING_INTENT_TTL_MS = 24L * 60L * 60L * 1000L
        const val INFERENCE_SCHEMA_VERSION = 1
        const val MAX_INFERENCE_DAYS = 14
        const val INFERENCE_SNAPSHOT_PREFIX = "energy-profile-inference-v1"
        const val POST_MEAL_MIN_MS = 30L * 60L * 1000L
        const val POST_MEAL_MAX_MS = 6L * 60L * 60L * 1000L
        const val MIN_POST_MEAL_GLUCOSE_POINTS = 3
        const val MIN_POST_MEAL_GLUCOSE_SPAN_MS = 90L * 60L * 1000L
        const val MEAL_OVERLAP_WINDOW_MS = 2L * 60L * 60L * 1000L
        const val PRE_MEAL_BASELINE_WINDOW_MS = 90L * 60L * 1000L
        const val PRE_MEAL_BASELINE_GAP_MS = 5L * 60L * 1000L
        const val MIN_MEAL_EXCURSION_MMOL = 0.5
        const val SETTLED_BASELINE_TOLERANCE_MMOL = 0.6
        const val MAX_STABLE_CGM_DELTA_MMOL = 0.25
        const val MIN_STABLE_CGM_GAP_MS = 5L * 60L * 1000L
        const val MAX_STABLE_CGM_GAP_MS = 30L * 60L * 1000L
        const val MIN_SETTLED_DURATION_MS = 60L * 60L * 1000L
        const val MIN_SETTLED_DURATION_MINUTES = 60
        const val MAX_SETTLED_DURATION_MINUTES = 360
        const val THERAPY_SETTLING_DELAY_MS = 30L * 60L * 1000L
        val THERAPY_TIMING_TYPES = setOf(
            "carbs", "meal", "meal_bolus", "bolus", "insulin", "correction_bolus"
        )
        val CANONICAL_CARB_MATCH_TOLERANCE_GRAMS = BigDecimal("0.1")
    }
}
