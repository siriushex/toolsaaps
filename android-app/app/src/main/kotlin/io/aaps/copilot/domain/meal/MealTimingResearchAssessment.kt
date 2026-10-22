package io.aaps.copilot.domain.meal

import io.aaps.copilot.domain.model.resolveTherapyComponents
import io.aaps.copilot.domain.model.TherapyCarbComponentKind
import io.aaps.copilot.domain.model.isExcludedInsulinTimingRecord
import io.aaps.copilot.domain.nutrition.PersonalGiEvidence
import io.aaps.copilot.domain.nutrition.PersonalGiModel
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionShape
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionResearchModel
import io.aaps.copilot.domain.nutrition.MealPortionObservation
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshot
import io.aaps.copilot.domain.predict.InsulinRuntimeSnapshotResolver
import io.aaps.copilot.domain.predict.MealInsulinKernelIdentity
import io.aaps.copilot.domain.predict.MealBasalHistory
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Declared sensitivity experiment. Supplying coefficients is not qualification of a personal model. */
internal data class MealGiScenarioInput(val evidence: PersonalGiEvidence, val model: PersonalGiModel)

internal enum class MealTimingResearchStatus {
    EVALUATED, EPISODE_MISMATCH, IOB_UNAVAILABLE, GI_UNAVAILABLE,
    PERSONAL_MODEL_UNAVAILABLE, PERSONAL_SCENARIO_UNSUPPORTED, BASAL_HISTORY_UNAVAILABLE
}

/** Conditional-mean crossing interval on a five-minute grid, not a confidence interval or safe wait. */
internal data class MealThresholdWindow(val afterMinutes: Int, val byMinutes: Int)

internal fun firstMealThresholdWindow(values: List<Double>, threshold: Double): MealThresholdWindow? {
    require(values.isNotEmpty() && values.all { it.isFinite() } && threshold.isFinite() && threshold > 0)
    val index = values.indexOfFirst { it < threshold }
    return if (index < 0) null else MealThresholdWindow(maxOf(0, index - 1) * 5, index * 5)
}

internal data class MealNoFoodPathRisk(
    val trajectory: MealConditionalTrajectory,
    val below46: MealThresholdWindow?,
    val below39: MealThresholdWindow?,
    val below30: MealThresholdWindow?
)

internal class MealTimingResearchAssessment(
    val status: MealTimingResearchStatus,
    val episode: MealMessageEpisode,
    val iob: InsulinRuntimeSnapshot? = null,
    val batch: MealSimulationBatch? = null,
    val giInput: MealGiScenarioInput? = null,
    val insulinAccounting: MealInsulinAccounting? = null,
    val absorptionShape: PersonalAbsorptionShape? = null,
    val personalCandidate: MealProspectiveCandidate? = null,
    val personalQualificationStatus: PersonalMealCandidateStatus? = null,
    noFoodPaths: List<MealNoFoodPathRisk> = emptyList()
) {
    val noFoodPaths: List<MealNoFoodPathRisk> = Collections.unmodifiableList(noFoodPaths.toList())
    val giApplied: Boolean get() = batch != null && giInput != null
    val candidateHighlighted: Boolean get() = (iob?.effectivePositiveIobUnits ?: 0.0) > 2.0
    val notificationAllowed: Boolean get() = false
    val therapyAllowed: Boolean get() = false
    val recommendedStartAtMs: Long? get() = null
    val blockers: List<String> = Collections.unmodifiableList(listOf("SIMULTANEOUS_UNCERTAINTY_UNVALIDATED",
        "FUTURE_CONTROL_UNSUPPORTED", "IOB_CURVE_RECONCILIATION_REQUIRED", "PERSONAL_FORECAST_VALIDATION_REQUIRED",
        "UPSTREAM_DELIVERY_COVERAGE_UNVERIFIED", "BASAL_HISTORY_NOT_ASSIMILATED"))
}

/** On-demand frozen research calculation; no subscription, message sender or treatment dependency. */
internal class MealTimingResearchEvaluator {
    suspend fun evaluate(context: MealSimulationContext, belief: MealBelief, episode: MealMessageEpisode,
        insulinTelemetry: Map<String, Double?>, horizonMinutes: Int, gi: MealGiScenarioInput? = null,
        futureInsulinScenarios: List<MealInsulinScenario>? = null,
        referenceInsulinKernel: MealInsulinKernelIdentity? = null,
        basalHistory: MealBasalHistory? = null): MealTimingResearchAssessment =
        evaluateScenarios(context, belief, episode, insulinTelemetry, horizonMinutes, gi,
            futureInsulinScenarios, referenceInsulinKernel, basalHistory)

    suspend fun evaluatePersonal(context: MealSimulationContext, identity: MealForecastIdentity,
        model: PersonalAbsorptionResearchModel, meal: MealPortionObservation, knownGi: PersonalGiEvidence?,
        belief: MealBelief, episode: MealMessageEpisode, insulinTelemetry: Map<String, Double?>, horizonMinutes: Int,
        futureInsulinScenarios: List<MealInsulinScenario>? = null,
        referenceInsulinKernel: MealInsulinKernelIdentity? = null,
        basalHistory: MealBasalHistory? = null, clockMs: () -> Long): MealTimingResearchAssessment {
        currentCoroutineContext().ensureActive()
        fun reject(status: MealTimingResearchStatus, qualification: PersonalMealCandidateStatus? = null) =
            MealTimingResearchAssessment(status, episode, personalQualificationStatus = qualification)
        if (meal.deleted || meal.canonicalId != episode.meal.canonicalId || meal.therapyRevision != episode.canonicalTherapyRevision ||
            meal.timestamp.toEpochMilli() != episode.meal.recordedAtMs || meal.grams != episode.meal.grams ||
            meal.availableAt.toEpochMilli() > context.glucose.last().ts)
            return reject(MealTimingResearchStatus.EPISODE_MISMATCH)
        val qualified = PersonalMealCandidateBuilder.build(context, identity, model, meal, knownGi, clockMs)
        if (qualified.status != PersonalMealCandidateStatus.CREATED)
            return reject(MealTimingResearchStatus.PERSONAL_MODEL_UNAVAILABLE, qualified.status)
        val shape = requireNotNull(qualified.absorptionShape)
        val candidate = requireNotNull(qualified.candidate)
        if (belief.scenarios.filter { it.carbs.maximumGrams > 0 }.any { case ->
                case.absorption.any { it.profile != shape.profile || it.durationMinutes != shape.durationMinutes }
            }) return reject(MealTimingResearchStatus.PERSONAL_SCENARIO_UNSUPPORTED, qualified.status)
        val gi = model.giModel?.let { MealGiScenarioInput(requireNotNull(knownGi), it) }
        val result = evaluateScenarios(context, belief, episode, insulinTelemetry, horizonMinutes, gi,
            futureInsulinScenarios, referenceInsulinKernel, basalHistory, shape, candidate)
        currentCoroutineContext().ensureActive()
        val completed = clockMs()
        if (completed < candidate.completedAtMs || completed >= identity.predictionAtMs + 300_000L)
            return reject(MealTimingResearchStatus.PERSONAL_MODEL_UNAVAILABLE, PersonalMealCandidateStatus.LATE)
        return result
    }

    private suspend fun evaluateScenarios(context: MealSimulationContext, belief: MealBelief, episode: MealMessageEpisode,
        insulinTelemetry: Map<String, Double?>, horizonMinutes: Int, gi: MealGiScenarioInput?,
        futureInsulinScenarios: List<MealInsulinScenario>?, referenceInsulinKernel: MealInsulinKernelIdentity?,
        basalHistory: MealBasalHistory?,
        qualifiedShape: PersonalAbsorptionShape? = null, personalCandidate: MealProspectiveCandidate? = null): MealTimingResearchAssessment {
        currentCoroutineContext().ensureActive()
        fun reject(status: MealTimingResearchStatus) = MealTimingResearchAssessment(status, episode)
        val anchor = context.glucose.last().ts
        val record = episode.meal
        val linked = context.therapy.filter { it.componentTrust.canonicalCarbId?.toString() == record.canonicalId }
        val insulin = context.therapy.singleOrNull { it.sourceRowId == episode.insulinRecordId }
        if (record.deleted || record.recordedAtMs != belief.input.recordedAtMs ||
            belief.input.carbs.minimumGrams > record.grams || belief.input.carbs.maximumGrams < record.grams ||
            belief.scenarios.any { it.canonicalMealId != record.canonicalId } ||
            episode.messagePostedAtMs?.let { it > anchor } == true ||
            linked.isEmpty() || linked.any {
                val parts = resolveTherapyComponents(it)
                it.ts != record.recordedAtMs || it.componentTrust.canonicalCarbRevision != episode.canonicalTherapyRevision ||
                    !parts.wholeEventValid || parts.carbKind != TherapyCarbComponentKind.REAL || parts.carbsG != record.grams
            } ||
            insulin == null || insulin.ts != episode.insulinRecordedAtMs ||
            isExcludedInsulinTimingRecord(episode.insulinRecordId, insulin.payload) ||
            !resolveTherapyComponents(insulin).wholeEventValid ||
            (resolveTherapyComponents(insulin).insulinU ?: 0.0) <= 0)
            return reject(MealTimingResearchStatus.EPISODE_MISMATCH)
        // The scalar qualifies context/salience only. Existing supported delivery kernels drive paths.
        val iob = InsulinRuntimeSnapshotResolver.resolveTelemetry(anchor, insulinTelemetry).snapshot
            ?: return reject(MealTimingResearchStatus.IOB_UNAVAILABLE)
        val basal = basalHistory?.let { history ->
            try { context.basalProjection(history, horizonMinutes) }
            catch (_: IllegalArgumentException) { return reject(MealTimingResearchStatus.BASAL_HISTORY_UNAVAILABLE) }
        }
        val accounting = MealInsulinAccounting.compare(context.insulinProjection(horizonMinutes),
            context.insulinKernelIdentity(), iob, referenceInsulinKernel, basal)
        val shape = qualifiedShape ?: if (gi == null) null else {
            val evidence = gi.evidence
            if (evidence.canonicalId != record.canonicalId || evidence.therapyRevision != episode.canonicalTherapyRevision ||
                evidence.availableAtMs !in record.recordedAtMs..anchor || evidence.source !in setOf("USER", "CATALOG") ||
                (evidence.source == "CATALOG" && evidence.reference.isNullOrBlank()) ||
                (evidence.reference?.length ?: 0) > 256)
                return reject(MealTimingResearchStatus.GI_UNAVAILABLE)
            gi.model.shapeFor(evidence.value) ?: return reject(MealTimingResearchStatus.GI_UNAVAILABLE)
        }
        val simulation = MealScenarioSimulator().simulateUncertain(context, belief, horizonMinutes,
            futureInsulinScenarios = futureInsulinScenarios, absorptionShape = shape)
        val batch = simulation.batch
        currentCoroutineContext().ensureActive()
        val absentIds = simulation.expansion.belief.scenarios.filter { it.kind in setOf(MealHypothesisKind.NOT_HAPPENING,
            MealHypothesisKind.NO_NEW_MEAL) }.map { it.id }.toSet()
        val risks = batch.trajectories.filter { it.scenarioId in absentIds }.map {
            MealNoFoodPathRisk(it, firstMealThresholdWindow(it.forecast.glucoseMmol, 4.6),
                firstMealThresholdWindow(it.forecast.glucoseMmol, 3.9), firstMealThresholdWindow(it.forecast.glucoseMmol, 3.0))
        }
        require(risks.isNotEmpty())
        return MealTimingResearchAssessment(MealTimingResearchStatus.EVALUATED, episode, iob, batch, gi,
            insulinAccounting = accounting, absorptionShape = shape, personalCandidate = personalCandidate,
            personalQualificationStatus = personalCandidate?.let { PersonalMealCandidateStatus.CREATED }, noFoodPaths = risks)
    }
}
