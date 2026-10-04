package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.meal.MealAbsorptionProjection
import io.aaps.copilot.domain.meal.MealFoodComponent
import io.aaps.copilot.domain.meal.MealFoodOrigin
import io.aaps.copilot.domain.meal.MealScenarioFoodProjection
import io.aaps.copilot.domain.model.DataQuality
import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import io.aaps.copilot.domain.profile.MealAbsorptionContext
import io.aaps.copilot.domain.profile.MealAbsorptionCurve
import io.aaps.copilot.domain.profile.MealAbsorptionProfile
import io.aaps.copilot.domain.profile.MealAbsorptionProfileResolver
import io.aaps.copilot.domain.profile.MealGlycemicIndex
import io.aaps.copilot.domain.profile.MealGlycemicIndexContext
import io.aaps.copilot.domain.profile.MealTherapyReference
import io.aaps.copilot.domain.profile.MealTherapyReferenceTrust
import io.aaps.copilot.domain.profile.toMealTherapyReference
import io.aaps.copilot.domain.profile.ResolvedMealAbsorption
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

data class SensitivityMetricOverride(
    val value: Double,
    val confidence: Double,
    val minConfidenceRequired: Double,
    val blendWeight: Double,
    val source: String,
    val authoritative: Boolean = false
)

class HybridPredictionEngine(
    private val enableEnhancedPredictionV3: Boolean = false,
    private val enableUam: Boolean = true,
    // Retained for named-argument compatibility; unified V3 never runs legacy virtual-meal fitting.
    private val enableUamVirtualMealFit: Boolean = true,
    defaultInsulinProfileId: InsulinActionProfileId = InsulinActionProfileId.NOVORAPID,
    private val enableDebugLogs: Boolean = false,
    private val debugLogger: ((String) -> Unit)? = null
) : PredictionEngine {

    @Volatile
    private var insulinProfileId: InsulinActionProfileId = defaultInsulinProfileId

    @Volatile
    private var insulinProfile: InsulinActionProfile = InsulinActionProfiles.profile(defaultInsulinProfileId)

    @Volatile
    private var insulinDurationOverrideHours: Double? = null

    @Volatile
    private var insulinAgeScale: Double = 1.0

    @Volatile
    private var insulinOnsetShiftMinutes: Double = 0.0

    @Volatile
    private var lastDiagnostics: V3Diagnostics? = null

    @Volatile
    private var externalSensitivityOverrides = ExternalSensitivityOverrides()

    @Volatile
    private var carbAbsorptionMaxAgeMinutes: Double = DEFAULT_CARB_ABSORPTION_MAX_AGE_MINUTES

    @Volatile
    private var carbComputationMaxGrams: Double = DEFAULT_CARB_COMPUTATION_MAX_GRAMS

    @Volatile
    private var mealAbsorptionContext: MealAbsorptionContext = MealAbsorptionContext.DISABLED

    @Volatile
    private var mealGlycemicIndexContext: MealGlycemicIndexContext = MealGlycemicIndexContext.EMPTY

    @Volatile
    private var uamRuntimeHint: UamRuntimeHint? = null

    @Volatile
    private var uamRuntimeQualityContext: UamRuntimeQualityContext? = null

    @Volatile
    private var uamSensitivityRuntimeContext: SensitivityRuntimeConsumerContext? = null

    private var kalmanFilterV3 = RevisionAwareKalmanFilter()
    private var residualArModel = ResidualArModel()
    private val mealAbsorptionProfileResolver = MealAbsorptionProfileResolver()
    private var sensitivityProfileEstimator = ProfileEstimator(
        config = ProfileEstimatorConfig(
            telemetryMergeMode = TelemetryMergeMode.HISTORY_ONLY
        )
    )

    fun setInsulinProfile(profileId: InsulinActionProfileId) {
        insulinProfileId = profileId
        insulinProfile = InsulinActionProfiles.profile(profileId)
        val overrideHours = insulinDurationOverrideHours
        if (overrideHours != null) {
            setInsulinDurationHours(overrideHours)
        } else {
            insulinAgeScale = 1.0
        }
    }

    fun setInsulinProfile(profileIdRaw: String?) {
        setInsulinProfile(InsulinActionProfileId.fromRaw(profileIdRaw))
    }

    fun setInsulinDurationHours(hours: Double?) {
        if (hours == null || !hours.isFinite()) {
            insulinDurationOverrideHours = null
            insulinAgeScale = 1.0
            return
        }
        val boundedHours = hours.coerceIn(MIN_INSULIN_DURATION_HOURS, MAX_INSULIN_DURATION_HOURS)
        insulinDurationOverrideHours = boundedHours
        val baseMinutes = insulinProfile.defaultDurationMinutes
        val overrideMinutes = boundedHours * 60.0
        insulinAgeScale = (baseMinutes / overrideMinutes).coerceIn(INSULIN_AGE_SCALE_MIN, INSULIN_AGE_SCALE_MAX)
    }

    fun setInsulinOnsetMinutes(baseOnsetMinutes: Double?, realOnsetMinutes: Double?) {
        insulinOnsetShiftMinutes = if (
            baseOnsetMinutes?.isFinite() == true && realOnsetMinutes?.isFinite() == true
        ) {
            (realOnsetMinutes - baseOnsetMinutes).coerceIn(-30.0, 75.0)
        } else {
            0.0
        }
    }

    fun setSensitivityOverride(
        isfMmolPerUnit: Double?,
        crGramPerUnit: Double?,
        confidence: Double?,
        source: String = "external",
        minConfidenceRequired: Double = EXTERNAL_SENSITIVITY_CONFIDENCE_THRESHOLD,
        blendWeight: Double = 1.0
    ) {
        if (confidence == null || !confidence.isFinite()) {
            setSensitivityOverrides(isf = null, cr = null)
            return
        }
        setSensitivityOverrides(
            isf = isfMmolPerUnit
                ?.takeIf { it.isFinite() }
                ?.let {
                    SensitivityMetricOverride(
                        value = it,
                        confidence = confidence,
                        minConfidenceRequired = minConfidenceRequired,
                        blendWeight = blendWeight,
                        source = source
                    )
                },
            cr = crGramPerUnit
                ?.takeIf { it.isFinite() }
                ?.let {
                    SensitivityMetricOverride(
                        value = it,
                        confidence = confidence,
                        minConfidenceRequired = minConfidenceRequired,
                        blendWeight = blendWeight,
                        source = source
                    )
                }
        )
    }

    fun setSensitivityOverrides(
        isf: SensitivityMetricOverride?,
        cr: SensitivityMetricOverride?
    ) {
        externalSensitivityOverrides = ExternalSensitivityOverrides(
            isf = isf?.bounded(EXTERNAL_ISF_MIN, EXTERNAL_ISF_MAX),
            cr = cr?.bounded(EXTERNAL_CR_MIN, EXTERNAL_CR_MAX)
        )
    }

    fun setCarbSafetyLimits(maxAgeMinutes: Int?, maxGrams: Double?) {
        carbAbsorptionMaxAgeMinutes = (maxAgeMinutes ?: DEFAULT_CARB_ABSORPTION_MAX_AGE_MINUTES.toInt())
            .coerceIn(60, 180)
            .toDouble()
        carbComputationMaxGrams = (maxGrams ?: DEFAULT_CARB_COMPUTATION_MAX_GRAMS)
            .coerceIn(20.0, 60.0)
    }

    fun setMealAbsorptionContext(context: MealAbsorptionContext) {
        mealAbsorptionContext = context
    }

    fun setMealGlycemicIndexContext(context: MealGlycemicIndexContext) {
        mealGlycemicIndexContext = context
    }

    internal fun newSimulationEngine(): HybridPredictionEngine = HybridPredictionEngine(
        enableEnhancedPredictionV3 = enableEnhancedPredictionV3,
        enableUam = enableUam,
        enableUamVirtualMealFit = enableUamVirtualMealFit,
        defaultInsulinProfileId = insulinProfileId,
        enableDebugLogs = enableDebugLogs,
        debugLogger = debugLogger
    ).also {
        it.setInsulinDurationHours(insulinDurationOverrideHours)
        it.insulinOnsetShiftMinutes = insulinOnsetShiftMinutes
        it.setMealAbsorptionContext(MealAbsorptionContext.DISABLED)
    }

    /**
     * Caller must hold exclusive ownership of this engine while copying (no
     * concurrent prediction or setters). Keep the returned seed unmodified and
     * fork it for each candidate. This is not the legacy dry-run factory above.
     */
    internal fun forkForMealSimulation(): HybridPredictionEngine = HybridPredictionEngine(
        enableEnhancedPredictionV3 = enableEnhancedPredictionV3,
        enableUam = enableUam,
        enableUamVirtualMealFit = enableUamVirtualMealFit,
        defaultInsulinProfileId = insulinProfileId
    ).also {
        it.insulinProfile = insulinProfile.copy(points = insulinProfile.points.toList())
        it.insulinDurationOverrideHours = insulinDurationOverrideHours
        it.insulinAgeScale = insulinAgeScale
        it.insulinOnsetShiftMinutes = insulinOnsetShiftMinutes
        it.externalSensitivityOverrides = externalSensitivityOverrides
        it.carbAbsorptionMaxAgeMinutes = carbAbsorptionMaxAgeMinutes
        it.carbComputationMaxGrams = carbComputationMaxGrams
        it.mealAbsorptionContext = mealAbsorptionContext
        it.mealGlycemicIndexContext = mealGlycemicIndexContext
        it.uamRuntimeHint = uamRuntimeHint
        it.uamRuntimeQualityContext = uamRuntimeQualityContext
        it.uamSensitivityRuntimeContext = uamSensitivityRuntimeContext
        it.kalmanFilterV3 = kalmanFilterV3.copyForSimulation()
        it.residualArModel = residualArModel.copyForSimulation()
        // Stateless estimator retains the captured timezone; value contexts above
        // are immutable. Diagnostics and the live logging callback are not shared.
        it.sensitivityProfileEstimator = sensitivityProfileEstimator
    }

    fun setUamRuntimeHint(
        ingestionTs: Long?,
        carbsGrams: Double?,
        confidence: Double?,
        source: String = "uam_inference"
    ) {
        if (ingestionTs == null || carbsGrams == null || confidence == null) {
            uamRuntimeHint = null
            return
        }
        if (!carbsGrams.isFinite() || !confidence.isFinite() || carbsGrams <= 0.0 || confidence <= 0.0) {
            uamRuntimeHint = null
            return
        }
        uamRuntimeHint = UamRuntimeHint(
            ingestionTs = ingestionTs,
            carbsGrams = carbsGrams.coerceIn(5.0, 120.0),
            confidence = confidence.coerceIn(0.0, 1.0),
            source = source.ifBlank { "uam_inference" }
        )
    }

    fun setUamRuntimeQualityContext(
        sensorTrust: Double,
        therapyCoverage: Double,
        announcedCarbCoverage: Double,
        sensorBlocked: Boolean
    ) {
        uamRuntimeQualityContext = if (
            sensorTrust.isFinite() && sensorTrust in 0.0..1.0 &&
            therapyCoverage.isFinite() && therapyCoverage in 0.0..1.0 &&
            announcedCarbCoverage.isFinite() && announcedCarbCoverage in 0.0..1.0
        ) {
            UamRuntimeQualityContext(
                sensorTrust = sensorTrust,
                therapyCoverage = therapyCoverage,
                announcedCarbCoverage = announcedCarbCoverage,
                sensorBlocked = sensorBlocked
            )
        } else {
            null
        }
    }

    fun setUamSensitivityRuntimeContext(context: SensitivityRuntimeConsumerContext) {
        require(context.consumer == SensitivityRuntimeConsumer.UAM) {
            "HybridPredictionEngine requires UAM sensitivity context"
        }
        uamSensitivityRuntimeContext = context
    }

    override suspend fun predict(glucose: List<GlucosePoint>, therapyEvents: List<TherapyEvent>): List<Forecast> {
        if (glucose.isEmpty()) return emptyList()
        return if (enableEnhancedPredictionV3) {
            predictEnhancedV3(glucose, therapyEvents)
        } else {
            lastDiagnostics = null
            predictLegacy(glucose, therapyEvents)
        }
    }

    internal suspend fun predictLegacyForTest(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>
    ): List<Forecast> = predictLegacy(glucose, therapyEvents)

    internal fun diagnosticsSnapshot(): V3Diagnostics? = lastDiagnostics
    internal fun lastDiagnosticsForTest(): V3Diagnostics? = lastDiagnostics
    internal fun currentInsulinProfileForTest(): InsulinActionProfileId = insulinProfileId
    internal fun currentInsulinDurationHoursForTest(): Double = currentInsulinDurationHours()
    internal fun resolveMealPressureForTest(input: MealPressureInput): ResolvedMealPressureSnapshot =
        resolveMealPressure(input)

    internal fun modeledActiveInsulinUnitsForTest(
        therapyEvents: List<TherapyEvent>,
        nowTs: Long
    ): Double = modeledActiveInsulinEvidence(therapyEvents, nowTs).activeUnits

    internal fun modeledActiveInsulinEvidence(
        therapyEvents: List<TherapyEvent>,
        nowTs: Long
    ): ModeledActiveInsulinEvidence {
        if (nowTs <= 0L) return ModeledActiveInsulinEvidence()
        var activeUnits = 0.0
        var eventCount = 0
        var latestEvidenceTimestamp: Long? = null
        var qualifiedEventCount = 0
        var latestQualifiedEvidenceTimestamp: Long? = null
        therapyEvents.asSequence()
            .filter { it.ts in 0..nowTs }
            .filter { nowTs - it.ts <= EVENT_LOOKBACK_MS }
            .forEach { event ->
                val modeled = modeledInsulinEvent(event) ?: return@forEach
                val ageMinutes = (nowTs - event.ts) / 60_000.0
                val remaining = (1.0 - insulinCumulative(ageMinutes)).coerceIn(0.0, 1.0)
                activeUnits += modeled.units * modeled.impactScale * remaining
                eventCount += 1
                latestEvidenceTimestamp = maxOf(latestEvidenceTimestamp ?: Long.MIN_VALUE, event.ts)
                if (!modeled.inferred) {
                    qualifiedEventCount += 1
                    latestQualifiedEvidenceTimestamp = maxOf(
                        latestQualifiedEvidenceTimestamp ?: Long.MIN_VALUE,
                        event.ts
                    )
                }
            }
        return ModeledActiveInsulinEvidence(
            activeUnits = activeUnits.coerceIn(0.0, 30.0),
            eventCount = eventCount,
            latestEvidenceTimestamp = latestEvidenceTimestamp,
            qualifiedEventCount = qualifiedEventCount,
            latestQualifiedEvidenceTimestamp = latestQualifiedEvidenceTimestamp
        )
    }

    internal fun isModeledInsulinEvent(event: TherapyEvent): Boolean = modeledInsulinEvent(event) != null

    /** Announced component only; the unified residual UAM component is not food history. */
    internal fun projectMealTimingAnnouncedFood(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        horizonMinutes: Int
    ): List<MealFoodComponent> {
        require(enableEnhancedPredictionV3)
        require(horizonMinutes in 5..720 && horizonMinutes % 5 == 0)
        require(glucose.size in 1..20_000 && therapyEvents.size <= 5_000)
        require(glucose.all { it.ts > 0 && it.valueMmol.isFinite() && it.valueMmol > 0 })
        val raw = deduplicateAndSort(glucose)
        val canonical = Glucose5mCanonicalizer.build(raw).points.ifEmpty { raw }
        val nowTs = canonical.last().ts
        require(therapyEvents.all { it.ts > 0 && it.ts <= nowTs })
        // Filtering invalid components must not turn conflicting research input into a partial baseline.
        require(therapyEvents.none { event -> with(event.componentTrust) {
            canonicalSemanticConflict || canonicalReferenceConflict || legacyValidityConflict
        } }) { "Conflicting meal input" }
        val profiled = profileCarbEvents(therapyEvents, canonical, nowTs)
        val components = profiled.map { food ->
            val reference = food.event.toMealTherapyReference()
            require(reference.trust == MealTherapyReferenceTrust.TRUSTED &&
                !reference.revision.isNullOrBlank() &&
                !food.event.componentTrust.legacyValidityConflict) { "Untrusted meal reference" }
            val projection = MealAbsorptionProjection.fromCumulative(
                nowTs, food.event.ts, food.grams, horizonMinutes
            ) { age -> carbCumulativeWithCutoff(food.type, age, food.mealAbsorptionCurve) }
            MealFoodComponent(
                "${reference.identity}:${reference.revision}", reference.identity,
                MealFoodOrigin.ANNOUNCED, projection)
        }
        require(components.map { it.canonicalMealId }.distinct().size == components.size) {
            "Duplicate canonical meal records"
        }
        return java.util.Collections.unmodifiableList(components.sortedBy { it.componentId })
    }

    /** Read-only component projection on an exclusively owned or frozen engine. */
    internal fun projectMealTimingInsulin(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        horizonMinutes: Int
    ): MealInsulinProjection {
        require(enableEnhancedPredictionV3)
        require(horizonMinutes in 5..720 && horizonMinutes % 5 == 0)
        require(glucose.isNotEmpty() && glucose.all { it.ts > 0 && it.valueMmol.isFinite() && it.valueMmol > 0 })
        val raw = deduplicateAndSort(glucose)
        val canonical = Glucose5mCanonicalizer.build(raw).points.ifEmpty { raw }
        val nowTs = canonical.last().ts
        require(therapyEvents.all { it.ts > 0 && it.ts <= nowTs })
        val modeled = therapyEvents.mapNotNull { event ->
            modeledInsulinEvent(event)?.let { event.ts to it }
        }
        require(modeled.none { (ts, _) ->
            nowTs - ts > EVENT_LOOKBACK_MS && insulinCumulative((nowTs - ts) / 60_000.0) < 1.0
        }) { "Active insulin outside prediction lookback" }
        val relevant = modeled.filter { nowTs - it.first <= EVENT_LOOKBACK_MS }
        require(relevant.size.toLong() * (horizonMinutes / 5) <= 1_000_000L) {
            "Meal insulin projection budget exceeded"
        }
        val factors = estimateSensitivityFactors(canonical, therapyEvents)
        val steps = (0..horizonMinutes / 5).map { step ->
            if (step == 0) 0.0 else relevant.sumOf { (ts, insulin) ->
                val age = (nowTs - ts) / 60_000.0
                -insulin.units * factors.isfMmolPerUnit * insulin.impactScale * maxOf(0.0,
                    insulinCumulative(age + step * 5.0) - insulinCumulative(age + (step - 1) * 5.0))
            }
        }
        val remaining = relevant.sumOf { (ts, insulin) ->
            insulin.units * insulin.impactScale *
                (1.0 - insulinCumulative((nowTs - ts) / 60_000.0 + horizonMinutes)).coerceIn(0.0, 1.0)
        }
        require(steps.all { it.isFinite() } && remaining.isFinite())
        return MealInsulinProjection(nowTs, factors.isfMmolPerUnit, factors.carbSensitivityMmolPerGram,
            steps, remaining, relevant.all { (ts, _) ->
                insulinCumulative((nowTs - ts) / 60_000.0 + horizonMinutes) >= 1.0
            }, relevant.size, relevant.count { it.second.inferred })
    }

    internal fun projectMealTimingFutureInsulin(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        plan: MealFutureInsulinPlan,
        horizonMinutes: Int
    ): MealFutureInsulinProjection {
        val known = projectMealTimingInsulin(glucose, therapyEvents, horizonMinutes)
        return projectFutureInsulin(plan, known.predictionAtMs, known.isfMmolPerUnit, horizonMinutes)
    }

    private fun projectFutureInsulin(
        plan: MealFutureInsulinPlan,
        predictionAtMs: Long,
        isf: Double,
        horizonMinutes: Int
    ): MealFutureInsulinProjection {
        require(plan.predictionAtMs == predictionAtMs)
        require(plan.deliveries.all { it.offsetMinutes <= horizonMinutes }) { "Future delivery outside scenario horizon" }
        require(plan.deliveries.isEmpty() || insulinCumulative(0.0) == 0.0) {
            "Shifted insulin kernel has pre-delivery effect"
        }
        val steps = (0..horizonMinutes / 5).map { step ->
            if (step == 0) 0.0 else plan.deliveries.sumOf { delivery ->
                val age = step * 5.0 - delivery.offsetMinutes
                -delivery.units * isf * maxOf(0.0, insulinCumulative(age) - insulinCumulative(age - 5.0))
            }
        }
        val remaining = plan.deliveries.sumOf {
            it.units * (1.0 - insulinCumulative(horizonMinutes.toDouble() - it.offsetMinutes)).coerceIn(0.0, 1.0)
        }
        require(steps.all { it.isFinite() } && remaining.isFinite())
        return MealFutureInsulinProjection(steps, remaining)
    }

    /** Research-only forward intervention. Caller owns inputs and engine while the snapshot is copied. */
    internal fun forecastMealTimingForward(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        canonicalMealId: String,
        replacement: MealAbsorptionProjection,
        horizonMinutes: Int = 60,
        futureInsulinPlan: MealFutureInsulinPlan? = null
    ): MealForwardForecast {
        require(enableEnhancedPredictionV3)
        require(horizonMinutes in 60..720 && horizonMinutes % 5 == 0)
        require(replacement.points.last().offsetMinutes >= maxOf(120, horizonMinutes)) {
            "Food projection must cover residual COB and the requested horizon"
        }
        val engine = forkForMealSimulation()
        val baseline = engine.projectMealTimingAnnouncedFood(glucose, therapyEvents,
            replacement.points.last().offsetMinutes)
        require(baseline.any { it.canonicalMealId == canonicalMealId }) { "Unknown canonical meal" }
        val food = MealScenarioFoodProjection.replace(baseline, canonicalMealId, replacement)
        val insulin = engine.projectMealTimingInsulin(glucose, therapyEvents, horizonMinutes)
        val futureInsulin = futureInsulinPlan?.let {
            engine.projectFutureInsulin(it, insulin.predictionAtMs, insulin.isfMmolPerUnit, horizonMinutes)
        }
        val forecasts = engine.predictEnhancedV3(glucose, therapyEvents, food, horizonMinutes / 5,
            futureInsulin?.stepEffectsMmol)
        val diagnostics = requireNotNull(engine.diagnosticsSnapshot())
        val limitsReached = abs(diagnostics.trendCum60Raw - diagnostics.trendCum60Clamped) > 1e-9 ||
            (1 until diagnostics.glucosePath.size).any { index ->
                val rawTherapy = diagnostics.insulinStep[index] + diagnostics.resolvedMealPressureStep[index]
                val therapy = diagnostics.resolvedTherapyStep[index]
                val rawGlucose = diagnostics.glucosePath[index - 1] + diagnostics.trendStep[index] + therapy
                abs(rawTherapy - therapy) > 1e-9 || abs(rawGlucose - diagnostics.glucosePath[index]) > 1e-9
            }
        return MealForwardForecast(food.predictionAtMs, forecasts, diagnostics.glucosePath, diagnostics.trendStep,
            food.points[horizonMinutes / 5].remainingGrams,
            insulin.remainingModeledUnitsAtHorizon + (futureInsulin?.remainingUnitsAtHorizon ?: 0.0),
            limitsReached, futureInsulinPlan?.totalUnits ?: 0.0)
    }

    private fun predictEnhancedV3(
        glucose: List<GlucosePoint>,
        therapyEvents: List<TherapyEvent>,
        forwardFood: MealScenarioFoodProjection? = null,
        predictionSteps: Int = STEPS_MAX,
        forwardInsulinSteps: List<Double>? = null
    ): List<Forecast> {
        val rawGlucose = deduplicateAndSort(glucose)
        if (rawGlucose.isEmpty()) return emptyList()

        val canonicalGlucose = Glucose5mCanonicalizer.build(rawGlucose).points.ifEmpty { rawGlucose }
        val rawNowPoint = rawGlucose.last()
        val nowPoint = canonicalGlucose.last()
        val nowTs = nowPoint.ts
        val gNowRaw = rawNowPoint.valueMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val causalTherapyEvents = therapyEvents.filter { it.ts <= nowTs }

        val trend = estimateTrend(canonicalGlucose)
        val factors = estimateSensitivityFactors(canonicalGlucose, causalTherapyEvents)
        val volatility = estimateVolatility(canonicalGlucose)
        val intervalPenalty = estimateIntervalPenalty(canonicalGlucose)
        val volNorm = (volatility / 1.5).coerceIn(0.0, 1.0)
        val profiledCarbEvents = profileCarbEvents(
            events = causalTherapyEvents,
            glucose = canonicalGlucose,
            nowTs = nowTs
        )
        var therapySeries = buildTherapyStepSeries(
            events = causalTherapyEvents,
            profiledCarbEvents = profiledCarbEvents,
            nowTs = nowTs,
            factors = factors,
            predictionSteps = predictionSteps
        )
        val causalTherapyComponents = if (canonicalGlucose.size >= 2) {
            intervalTherapyComponents(
                events = causalTherapyEvents,
                profiledCarbEvents = profiledCarbEvents,
                startTs = canonicalGlucose[canonicalGlucose.lastIndex - 1].ts,
                endTs = nowTs,
                factors = factors
            )
        } else {
            TherapyIntervalComponents()
        }
        val runtimeQuality = uamRuntimeQualityContext
        val sensorBlocked = rawNowPoint.quality != DataQuality.OK || runtimeQuality?.sensorBlocked == true
        val sensorTrust = if (sensorBlocked) {
            UNKNOWN_UAM_SENSOR_TRUST
        } else {
            runtimeQuality?.sensorTrust ?: UNKNOWN_UAM_SENSOR_TRUST
        }
        val therapyCoverage = runtimeQuality?.therapyCoverage ?: UNKNOWN_UAM_THERAPY_COVERAGE
        val announcedCarbCoverage = runtimeQuality?.announcedCarbCoverage
            ?: UNKNOWN_ANNOUNCED_CARB_COVERAGE
        val uamSensitivityRuntime = uamSensitivityRuntimeContext
        val unifiedUam = if (enableUam && uamSensitivityRuntime != null) {
            UnifiedUamEstimator.estimate(
                UnifiedUamInput(
                    nowTs = nowTs,
                    glucose = canonicalGlucose,
                    insulinImpactMmol5 = causalTherapyComponents.insulinDelta,
                    announcedCarbImpactMmol5 = causalTherapyComponents.announcedCarbDelta,
                    csfMmolPerGram = factors.carbSensitivityMmolPerGram,
                    sensorTrust = sensorTrust,
                    therapyCoverage = therapyCoverage,
                    sensorBlocked = sensorBlocked,
                    sensitivityRuntime = uamSensitivityRuntime
                )
            )
        } else {
            null
        }
        val unifiedForecastSteps = unifiedUam?.forecastStepsMmol
        require(predictionSteps == STEPS_MAX || unifiedForecastSteps?.any { it != 0.0 } != true) {
            "Extended UAM tail is not modeled"
        }
        val rawUnifiedUamSteps = DoubleArray(predictionSteps + 1)
        if (unifiedForecastSteps != null) {
            for (j in 1..predictionSteps) {
                rawUnifiedUamSteps[j] = unifiedForecastSteps.getOrElse(j - 1) { 0.0 }
            }
        }
        val forecastUamCandidates = if (unifiedUam?.activeForForecast == true) {
            rawUnifiedUamSteps
        } else {
            DoubleArray(predictionSteps + 1)
        }
        val baselineMealPressure = resolveMealPressure(
            MealPressureInput(
                announcedCarbSteps = therapySeries.announcedCarbSteps,
                uamSteps = forecastUamCandidates,
                residualCobNowGrams = therapySeries.residualCarbsNowGrams,
                announcedCarbCoverage = announcedCarbCoverage,
                uamConfidence = unifiedUam?.confidence ?: 0.0
            )
        )
        // Historical trend cannot learn from hypothetical future food or insulin.
        val baselineFirstTherapyStep = if (forwardFood != null || forwardInsulinSteps != null) {
            therapySeries.withResolvedMealPressure(baselineMealPressure.steps).resolvedSteps[1]
        } else null
        if (forwardInsulinSteps != null) {
            require(forwardInsulinSteps.size == predictionSteps + 1 && forwardInsulinSteps[0] == 0.0)
            require(forwardInsulinSteps.all { it.isFinite() && it <= 0.0 })
            val insulin = DoubleArray(predictionSteps + 1) { therapySeries.insulinSteps[it] + forwardInsulinSteps[it] }
            require(insulin.all { it.isFinite() })
            val (legacy, cumulative) = combinedClampedSteps(insulin, therapySeries.announcedCarbSteps)
            therapySeries = therapySeries.copy(insulinSteps = insulin, legacySteps = legacy, cumClamped = cumulative)
        }
        if (forwardFood != null) {
            require(forwardFood.predictionAtMs == nowTs)
            val announced = DoubleArray(predictionSteps + 1) { index ->
                forwardFood.points[index].absorbedStepGrams * factors.carbSensitivityMmolPerGram
            }
            require(announced.all { it.isFinite() })
            val (legacy, cumulative) = combinedClampedSteps(therapySeries.insulinSteps, announced)
            therapySeries = therapySeries.copy(
                announcedCarbSteps = announced,
                legacySteps = legacy,
                cumClamped = cumulative,
                residualCarbsNowGrams = forwardFood.points[0].remainingGrams,
                residualCarbs30mGrams = forwardFood.points[6].remainingGrams,
                residualCarbs60mGrams = forwardFood.points[12].remainingGrams,
                residualCarbs120mGrams = forwardFood.points[24].remainingGrams
            )
        }
        val resolvedMealPressure = if (forwardFood == null) baselineMealPressure else resolveMealPressure(
            MealPressureInput(announcedCarbSteps = therapySeries.announcedCarbSteps,
                uamSteps = forecastUamCandidates, residualCobNowGrams = therapySeries.residualCarbsNowGrams,
                announcedCarbCoverage = announcedCarbCoverage, uamConfidence = unifiedUam?.confidence ?: 0.0)
        )
        val resolvedMealSteps = resolvedMealPressure.steps
        therapySeries = therapySeries.withResolvedMealPressure(resolvedMealSteps)
        val legacyTherapySteps = therapySeries.legacySteps
        val resolvedTherapySteps = therapySeries.resolvedSteps
        val resolvedUamAttributionSteps = DoubleArray(predictionSteps + 1) { index ->
            resolvedTherapySteps[index] - legacyTherapySteps[index]
        }
        val historicalKnownInputs = buildHistoricalKnownInputSeries(
            glucose = canonicalGlucose,
            events = causalTherapyEvents,
            profiledCarbEvents = profiledCarbEvents,
            factors = factors
        )

        val historyUpdate = kalmanFilterV3.update(
            inputs = canonicalGlucose.mapIndexed { index, point ->
                KalmanHistoryInput(point.ts, point.valueMmol, historicalKnownInputs.rocPerMin[index])
            },
            volNorm = volNorm,
            sourceHistory = rawGlucose,
            revalidateKnownInputs = { previous ->
                buildHistoricalKnownInputSeries(
                    glucose = previous.map { GlucosePoint(it.ts, it.glucose, "kalman_history", DataQuality.OK) },
                    events = causalTherapyEvents,
                    profiledCarbEvents = profiledCarbEvents,
                    factors = factors
                ).rocPerMin
            }
        )
        if (historyUpdate.causalHistoryRevised) {
            // Residuals learned from superseded glucose/therapy must not survive a replay.
            residualArModel = ResidualArModel()
        }
        val kfSnapshot = historyUpdate.snapshot

        val warmedUp = (kfSnapshot?.updatesCount ?: 0) >= KF_MIN_UPDATES
        val rocFallback = (0.65 * trend.shortSlopePer5m + 0.35 * trend.longSlopePer5m).coerceIn(-1.2, 1.2)
        val gNowUsed = if (warmedUp) {
            (kfSnapshot?.gMmol ?: gNowRaw).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        } else {
            gNowRaw
        }
        val rocPer5Used = if (warmedUp) {
            (kfSnapshot?.rocPer5Mmol ?: rocFallback).coerceIn(-1.2, 1.2)
        } else {
            rocFallback
        }

        var residualRoc0 = if (warmedUp) {
            rocPer5Used
        } else {
            rocPer5Used - (baselineFirstTherapyStep ?: resolvedTherapySteps[1])
        }
        if (unifiedUam?.activeForForecast == true) {
            residualRoc0 = minOf(0.0, residualRoc0)
        }
        residualRoc0 = residualRoc0.coerceIn(-1.2, 1.2)

        residualArModel.appendOrUpdate(nowTs = nowTs, residualRocPer5 = residualRoc0)
        val arParams = residualArModel.fit(
            uamActive = unifiedUam?.activeForForecast == true,
            trendRocHalfLifeMin = TREND_ROC_HALF_LIFE_MIN
        )

        val trendStepRaw = residualArModel.forecastSteps(
            residualRoc0 = residualRoc0,
            params = arParams,
            steps = predictionSteps
        )

        val trendCumRaw = DoubleArray(predictionSteps + 1)
        for (j in 1..predictionSteps) {
            trendCumRaw[j] = trendCumRaw[j - 1] + trendStepRaw[j]
        }
        val trend60Raw = trendCumRaw[STEPS_MAX]
        val trend60Clamped = trend60Raw.coerceIn(-maxTrendAbs(STEPS_MAX), maxTrendAbs(STEPS_MAX))
        val trendScale = if (abs(trend60Raw) < 1e-6) 1.0 else trend60Clamped / trend60Raw

        // Keep the accepted first-hour scaling independent of requested tail length.
        val trendStep = DoubleArray(predictionSteps + 1)
        for (j in 1..predictionSteps) {
            trendStep[j] = trendStepRaw[j] * trendScale
        }

        val glucosePath = DoubleArray(predictionSteps + 1)
        glucosePath[0] = gNowUsed
        for (j in 1..predictionSteps) {
            val delta = trendStep[j] + resolvedTherapySteps[j]
            glucosePath[j] = (glucosePath[j - 1] + delta).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        }

        val predByHorizon = mapOf(
            5 to glucosePath[1],
            30 to glucosePath[6],
            60 to glucosePath[12]
        )

        val forecasts = HORIZONS_MINUTES.map { horizon ->
            val predicted = predByHorizon.getValue(horizon.toInt())
            val n = horizon / 5.0
            val ciBase = ciHalfWidth(
                horizonMinutes = horizon,
                volatility = volatility,
                intervalPenalty = intervalPenalty
            )
            val uamImpact = unifiedUam?.impactMmolPer5m ?: 0.0
            val ciAddUam = (CI_UAM_ALPHA * sqrt(n) * uamImpact).coerceIn(0.0, CI_UAM_ADD_MAX)
            val ciAddRoc = (CI_ROC_ALPHA * sqrt(n) * abs(residualRoc0)).coerceIn(0.0, CI_ROC_ADD_MAX)
            val ciAddKf = if (warmedUp) {
                (CI_KF_ALPHA * sqrt(n) * (kfSnapshot?.sigmaG ?: 0.0)).coerceIn(0.0, CI_KF_ADD_MAX)
            } else {
                0.0
            }
            val ciAddAr = (CI_AR_ALPHA * sqrt(n) * arParams.sigmaE).coerceIn(0.0, CI_AR_ADD_MAX)
            val ciHalfWidth = (ciBase + ciAddUam + ciAddRoc + ciAddKf + ciAddAr).coerceIn(0.30, 3.2)

            Forecast(
                ts = nowTs + horizon * MINUTE_MS,
                horizonMinutes = horizon.toInt(),
                valueMmol = roundToStep(predicted, 0.01),
                ciLow = roundToStep((predicted - ciHalfWidth).coerceAtLeast(MIN_GLUCOSE_MMOL), 0.01),
                ciHigh = roundToStep((predicted + ciHalfWidth).coerceAtMost(MAX_GLUCOSE_MMOL), 0.01),
                modelVersion = ENHANCED_MODEL_VERSION
            )
        }

        val unifiedImpact = unifiedUam?.impactMmolPer5m ?: 0.0
        val uamTailGuardMultiplier = if (forecastUamCandidates.getOrElse(6) { 0.0 } > 0.0) {
            (forecastUamCandidates.getOrElse(12) { 0.0 } / forecastUamCandidates[6]).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        val overlappingMealPressureIndices = resolvedMealSteps.indices.filter { index ->
            therapySeries.announcedCarbSteps[index] > 0.0 && forecastUamCandidates[index] > 0.0
        }
        val doubleCountPrevented = overlappingMealPressureIndices.isNotEmpty() &&
            overlappingMealPressureIndices.all { index ->
                resolvedMealSteps[index] <=
                    maxOf(therapySeries.announcedCarbSteps[index], forecastUamCandidates[index]) + 1e-9
            }

        lastDiagnostics = V3Diagnostics(
            gNowRaw = gNowRaw,
            gNowUsed = gNowUsed,
            rocPer5Used = rocPer5Used,
            knownInputTherapyStep1 = historicalKnownInputs.therapyDelta.getOrElse(canonicalGlucose.lastIndex) { 0.0 },
            knownInputUamStep1 = historicalKnownInputs.uamDelta.getOrElse(canonicalGlucose.lastIndex) { 0.0 },
            knownInputInsulinStep1 = historicalKnownInputs.insulinDelta
                .getOrElse(canonicalGlucose.lastIndex) { 0.0 },
            knownInputAnnouncedCarbStep1 = historicalKnownInputs.announcedCarbDelta
                .getOrElse(canonicalGlucose.lastIndex) { 0.0 },
            kfSigmaG = kfSnapshot?.sigmaG ?: 0.0,
            kfEwmaNis = kfSnapshot?.ewmaNis ?: 1.0,
            kfSigmaZ = kfSnapshot?.sigmaZ ?: 0.18,
            kfSigmaA = kfSnapshot?.sigmaA ?: 0.02,
            kfWarmedUp = warmedUp,
            kfHistoryRebuilt = historyUpdate.rebuilt,
            kfHistoryRevised = historyUpdate.causalHistoryRevised,
            kfAppliedUpdates = historyUpdate.appliedUpdates,
            insulinProfileId = insulinProfileId.name,
            insulinDurationHours = currentInsulinDurationHours(),
            insulinAgeScale = insulinAgeScale,
            residualRoc0 = residualRoc0,
            uci0 = unifiedImpact,
            uciMax = maxOf(unifiedImpact, rawUnifiedUamSteps.maxOrNull() ?: 0.0),
            k = 0.0,
            uamActive = unifiedUam?.activeForForecast == true,
            virtualMealCarbs = null,
            virtualMealConfidence = null,
            usingVirtualMeal = false,
            uamTailGuardMultiplier = uamTailGuardMultiplier,
            runtimeHintCarbs = uamRuntimeHint?.carbsGrams,
            runtimeHintConfidence = uamRuntimeHint?.confidence,
            runtimeHintSource = uamRuntimeHint?.source,
            unifiedUamTimestamp = unifiedUam?.timestamp ?: nowTs,
            unifiedUamState = unifiedUam?.state?.name ?: UAM_DISABLED_STATE,
            unifiedUamActiveForForecast = unifiedUam?.activeForForecast == true,
            unifiedUamConfidence = unifiedUam?.confidence ?: 0.0,
            unifiedUamImpactMmol5 = unifiedUam?.impactMmolPer5m ?: 0.0,
            unifiedUamSignedResidualMmol5 = unifiedUam?.signedResidualMmolPer5m ?: 0.0,
            unifiedUamShortAverageDeltaMmol5 = unifiedUam?.shortAverageDeltaMmol5 ?: 0.0,
            unifiedUamEquivalentCarbsGrams = unifiedUam?.equivalentCarbsGrams,
            unifiedUamLowerBoundCarbsGrams = unifiedUam?.supportedLowerBoundCarbsGrams,
            unifiedUamOnsetTs = unifiedUam?.onsetTs,
            unifiedUamFirstDetectionTs = unifiedUam?.firstDetectionTs,
            unifiedUamActiveSinceTs = unifiedUam?.activeSinceTs,
            unifiedUamSupportStableBuckets = unifiedUam?.supportStableBuckets ?: 0,
            unifiedUamLowerBoundStableBuckets = unifiedUam?.lowerBoundStableBuckets ?: 0,
            unifiedUamSource = unifiedUam?.source ?: UAM_DISABLED_SOURCE,
            unifiedUamReasons = unifiedUam?.reasons.orEmpty(),
            unifiedUamAlgorithmVersion = UNIFIED_UAM_ALGORITHM_VERSION,
            unifiedUamSensorTrust = unifiedUam?.sensorTrust ?: sensorTrust,
            unifiedUamTherapyCoverage = unifiedUam?.therapyCoverage ?: therapyCoverage,
            unifiedUamSensitivityCycleId = uamSensitivityRuntime?.snapshot?.forecastCycleId.orEmpty(),
            unifiedUamSensitivitySettingsRevision = uamSensitivityRuntime?.snapshot?.settingsRevision ?: -1L,
            unifiedUamSensitivityIsfMmolPerUnit = uamSensitivityRuntime?.snapshot?.isf?.effective,
            unifiedUamSensitivityCrGramPerUnit = uamSensitivityRuntime?.snapshot?.cr?.effective,
            unifiedUamActiveForControl = unifiedUam?.activeForControl == true,
            unifiedUamQualityProvisional = runtimeQuality == null,
            announcedCarbCoverage = announcedCarbCoverage,
            carbAbsorptionProvenance = profiledCarbEvents.mapNotNull { profiled ->
                profiled.mealAbsorptionCurve?.let { profiled.reason }
            },
            resolvedMealPressureSource = resolvedMealPressure.source,
            resolvedMealPressureStep = resolvedMealSteps.toList(),
            resolvedTherapyStep = resolvedTherapySteps.toList(),
            insulinStep = therapySeries.insulinSteps.toList(),
            announcedCarbStep = therapySeries.announcedCarbSteps.toList(),
            foodDisplayProjection = therapySeries.foodDisplayProjection,
            announcedMealPressureWeight = resolvedMealPressure.announcedWeight,
            uamMealPressureWeight = resolvedMealPressure.uamWeight,
            legacyVirtualMealUsed = false,
            doubleCountPrevented = doubleCountPrevented,
            arMu = arParams.mu,
            arPhi = arParams.phi,
            arSigmaE = arParams.sigmaE,
            arUsedFallback = arParams.usedFallback,
            therapyStep = legacyTherapySteps.toList(),
            therapyCumClamped = therapySeries.cumClamped.toList(),
            carbFastActiveGrams = therapySeries.carbFastActiveGrams,
            carbMediumActiveGrams = therapySeries.carbMediumActiveGrams,
            carbProteinSlowActiveGrams = therapySeries.carbProteinSlowActiveGrams,
            residualCarbsNowGrams = therapySeries.residualCarbsNowGrams,
            residualCarbs30mGrams = therapySeries.residualCarbs30mGrams,
            residualCarbs60mGrams = therapySeries.residualCarbs60mGrams,
            residualCarbs120mGrams = therapySeries.residualCarbs120mGrams,
            uamStep = resolvedUamAttributionSteps.toList(),
            rawUnifiedUamStep = rawUnifiedUamSteps.toList(),
            trendStep = trendStep.toList(),
            glucosePath = glucosePath.toList(),
            trendCum60Raw = trend60Raw,
            trendCum60Clamped = trend60Clamped,
            predByHorizon = predByHorizon
        )

        logV3Debug(nowTs = nowTs, diagnostics = lastDiagnostics!!)

        return forecasts
    }

    private fun logV3Debug(nowTs: Long, diagnostics: V3Diagnostics) {
        if (!enableDebugLogs) return
        val msg = buildString {
            append("enhanced_v3_cycle")
            append(" ts=").append(nowTs)
            append(" gRaw=").append(roundToStep(diagnostics.gNowRaw, 0.001))
            append(" gUsed=").append(roundToStep(diagnostics.gNowUsed, 0.001))
            append(" kfSigmaG=").append(roundToStep(diagnostics.kfSigmaG, 0.001))
            append(" ewmaNIS=").append(roundToStep(diagnostics.kfEwmaNis, 0.001))
            append(" sigmaZ=").append(roundToStep(diagnostics.kfSigmaZ, 0.001))
            append(" sigmaA=").append(roundToStep(diagnostics.kfSigmaA, 0.001))
            append(" insulinProfile=").append(diagnostics.insulinProfileId)
            append(" insulinDurationH=")
            append(diagnostics.insulinDurationHours?.let { roundToStep(it, 0.01) } ?: "default")
            append(" insulinAgeScale=").append(roundToStep(diagnostics.insulinAgeScale, 0.001))
            append(" rocPer5=").append(roundToStep(diagnostics.rocPer5Used, 0.001))
            append(" kfKnownTherapy=").append(roundToStep(diagnostics.knownInputTherapyStep1, 0.001))
            append(" kfKnownUam=").append(roundToStep(diagnostics.knownInputUamStep1, 0.001))
            append(" therapyStep1=").append(roundToStep(diagnostics.therapyStep.getOrElse(1) { 0.0 }, 0.001))
            append(" carbFast=").append(roundToStep(diagnostics.carbFastActiveGrams, 0.1))
            append(" carbMedium=").append(roundToStep(diagnostics.carbMediumActiveGrams, 0.1))
            append(" carbProtein=").append(roundToStep(diagnostics.carbProteinSlowActiveGrams, 0.1))
            append(" residualCarbs60=").append(roundToStep(diagnostics.residualCarbs60mGrams, 0.1))
            append(" uamStep1=").append(roundToStep(diagnostics.uamStep.getOrElse(1) { 0.0 }, 0.001))
            append(" uamTailGuard=").append(roundToStep(diagnostics.uamTailGuardMultiplier, 0.01))
            append(" residualRoc0=").append(roundToStep(diagnostics.residualRoc0, 0.001))
            append(" mu=").append(roundToStep(diagnostics.arMu, 0.001))
            append(" phi=").append(roundToStep(diagnostics.arPhi, 0.001))
            append(" sigmaE=").append(roundToStep(diagnostics.arSigmaE, 0.001))
            append(" trendStep1=").append(roundToStep(diagnostics.trendStep.getOrElse(1) { 0.0 }, 0.001))
            append(" pred5=").append(roundToStep(diagnostics.predByHorizon.getValue(5), 0.01))
            append(" pred30=").append(roundToStep(diagnostics.predByHorizon.getValue(30), 0.01))
            append(" pred60=").append(roundToStep(diagnostics.predByHorizon.getValue(60), 0.01))
            append(" uci0=").append(roundToStep(diagnostics.uci0, 0.001))
            append(" k=").append(roundToStep(diagnostics.k, 0.001))
            if (diagnostics.runtimeHintCarbs != null || diagnostics.runtimeHintConfidence != null) {
                append(" runtimeHintC=")
                append(diagnostics.runtimeHintCarbs?.let { roundToStep(it, 0.01) } ?: "n/a")
                append(" runtimeHintConf=")
                append(diagnostics.runtimeHintConfidence?.let { roundToStep(it, 0.001) } ?: "n/a")
                append(" runtimeHintSrc=")
                append(diagnostics.runtimeHintSource ?: "n/a")
            }
            if (diagnostics.virtualMealCarbs != null || diagnostics.virtualMealConfidence != null) {
                append(" mealC=")
                append(diagnostics.virtualMealCarbs?.let { roundToStep(it, 0.01) } ?: "n/a")
                append(" mealConf=")
                append(diagnostics.virtualMealConfidence?.let { roundToStep(it, 0.001) } ?: "n/a")
            }
        }
        (debugLogger ?: ::println).invoke(msg)
    }

    private fun deduplicateAndSort(glucose: List<GlucosePoint>): List<GlucosePoint> {
        val sorted = glucose.sortedBy { it.ts }
        if (sorted.size <= 1) return sorted
        val byTs = LinkedHashMap<Long, GlucosePoint>(sorted.size)
        sorted.forEach { point -> byTs[point.ts] = point }
        return byTs.values.toList()
    }

    private fun resolveMealPressure(input: MealPressureInput): ResolvedMealPressureSnapshot {
        val resolved = MealPressureResolver.resolve(input)
        val resolvedSteps = resolved.steps
        val announcedValid = input.announcedCarbSteps.isNotEmpty() &&
            input.announcedCarbSteps.all { it.isFinite() && it >= 0.0 }
        val safeSteps = if (
            resolved.source == INVALID_MEAL_PRESSURE_SOURCE &&
            announcedValid &&
            resolvedSteps.size == input.announcedCarbSteps.size
        ) {
            input.announcedCarbSteps.copyOf()
        } else {
            resolvedSteps
        }
        return ResolvedMealPressureSnapshot(
            steps = safeSteps,
            announcedWeight = resolved.announcedWeight,
            uamWeight = if (resolved.source == INVALID_MEAL_PRESSURE_SOURCE) 0.0 else resolved.uamWeight,
            source = resolved.source
        )
    }

    private fun buildTherapyStepSeries(
        events: List<TherapyEvent>,
        profiledCarbEvents: List<ProfiledCarbEvent>,
        nowTs: Long,
        factors: SensitivityFactors,
        predictionSteps: Int = STEPS_MAX
    ): TherapyStepSeries {
        val insulinSteps = DoubleArray(predictionSteps + 1)
        val announcedCarbSteps = DoubleArray(predictionSteps + 1)
        val profiledByKey = profiledCarbEvents.associateBy { it.eventKey }
        val displayGiContext = mealGlycemicIndexContext
        val displayCutoffMinutes = carbAbsorptionMaxAgeMinutes
        val relevantEvents = events.asSequence()
            .filter { nowTs - it.ts <= EVENT_LOOKBACK_MS }
            .filter { it.ts <= nowTs }
            .mapNotNull { event ->
                val profiled = profiledByKey[eventKey(event)]
                val grams = profiled?.grams ?: extractCarbsGramsForPrediction(event)
                val modeledInsulin = modeledInsulinEvent(event)
                val insulinUnits = modeledInsulin?.units
                val carryInsulin = modeledInsulin != null
                if ((grams == null || grams <= 0.0) && !carryInsulin) {
                    return@mapNotNull null
                }
                RuntimeTherapyEvent(
                    ts = event.ts,
                    grams = grams?.takeIf { it > 0.0 },
                    carbType = profiled?.type,
                    mealAbsorptionCurve = profiled?.mealAbsorptionCurve,
                    displayGlycemicIndex = if (event.componentTrust.legacyValidityConflict) null
                        else displayGiContext.indexFor(event.toMealTherapyReference()),
                    insulinUnits = insulinUnits?.takeIf { it > 0.0 },
                    carryInsulin = carryInsulin,
                    insulinImpactScale = modeledInsulin?.impactScale ?: 1.0
                )
            }
            .toList()

        for (j in 1..predictionSteps) {
            val stepEndTs = nowTs + j * FIVE_MINUTES_MS
            var insulinStep = 0.0
            var announcedCarbStep = 0.0
            relevantEvents.forEach { event ->
                if (event.ts > stepEndTs) return@forEach
                val age0 = maxOf(0.0, (nowTs - event.ts) / 60_000.0)
                val ageA = age0 + (j - 1) * 5.0
                val ageB = age0 + j * 5.0

                val carbsEffective = event.grams
                if (carbsEffective != null && carbsEffective > 0.0) {
                    val carbType = event.carbType ?: CarbAbsorptionType.MEDIUM
                    val absorbed = maxOf(
                        0.0,
                        carbCumulativeWithCutoff(carbType, ageB, event.mealAbsorptionCurve) -
                            carbCumulativeWithCutoff(carbType, ageA, event.mealAbsorptionCurve)
                    )
                    announcedCarbStep += carbsEffective * factors.carbSensitivityMmolPerGram * absorbed
                }

                val insulin = event.insulinUnits
                if (insulin != null && event.carryInsulin) {
                    insulinStep += -insulin * factors.isfMmolPerUnit * event.insulinImpactScale *
                        maxOf(0.0, insulinCumulative(ageB) - insulinCumulative(ageA))
                }
            }
            insulinSteps[j] = insulinStep
            announcedCarbSteps[j] = announcedCarbStep
        }

        // This separate projection never feeds clinical pressure, UAM or forecasts.
        val foodDisplayProjection = if (relevantEvents.size > 5000) null else
            MealFoodDisplayProjection.build(nowTs, factors.carbSensitivityMmolPerGram,
                relevantEvents.mapNotNull { event ->
                    val grams = event.grams ?: return@mapNotNull null
                    val ageNow = maxOf(0.0, (nowTs - event.ts) / 60_000.0)
                    MealFoodDisplayInput(grams, { offset ->
                        carbCumulativeWithCutoff(event.carbType ?: CarbAbsorptionType.MEDIUM,
                            ageNow + offset, event.mealAbsorptionCurve, displayCutoffMinutes)
                    }, event.displayGlycemicIndex)
                })

        var fastActive = 0.0
        var mediumActive = 0.0
        var proteinActive = 0.0
        var residualNow = 0.0
        var residual30 = 0.0
        var residual60 = 0.0
        var residual120 = 0.0

        profiledCarbEvents.forEach { profiled ->
            val ageNow = maxOf(0.0, (nowTs - profiled.event.ts) / 60_000.0)
            val nowFraction = carbCumulativeWithCutoff(profiled.type, ageNow, profiled.mealAbsorptionCurve)
                .coerceIn(0.0, 1.0)
            val residualNowEvent = (profiled.grams * (1.0 - nowFraction)).coerceAtLeast(0.0)
            val residual30Event = (profiled.grams * (1.0 - carbCumulativeWithCutoff(
                profiled.type,
                ageNow + 30.0,
                profiled.mealAbsorptionCurve
            )))
                .coerceAtLeast(0.0)
            val residual60Event = (profiled.grams * (1.0 - carbCumulativeWithCutoff(
                profiled.type,
                ageNow + 60.0,
                profiled.mealAbsorptionCurve
            )))
                .coerceAtLeast(0.0)
            val residual120Event = (profiled.grams * (1.0 - carbCumulativeWithCutoff(
                profiled.type,
                ageNow + 120.0,
                profiled.mealAbsorptionCurve
            )))
                .coerceAtLeast(0.0)
            residualNow += residualNowEvent
            residual30 += residual30Event
            residual60 += residual60Event
            residual120 += residual120Event
            when (profiled.type) {
                CarbAbsorptionType.ULTRA_FAST -> fastActive += residualNowEvent
                CarbAbsorptionType.FAST -> fastActive += residualNowEvent
                CarbAbsorptionType.MEDIUM -> mediumActive += residualNowEvent
                CarbAbsorptionType.PROTEIN_SLOW -> proteinActive += residualNowEvent
            }
        }

        val (legacySteps, legacyCumClamped) = combinedClampedSteps(
            insulinSteps = insulinSteps,
            mealSteps = announcedCarbSteps
        )

        return TherapyStepSeries(
            insulinSteps = insulinSteps,
            announcedCarbSteps = announcedCarbSteps,
            legacySteps = legacySteps,
            resolvedSteps = DoubleArray(predictionSteps + 1),
            cumClamped = legacyCumClamped,
            carbFastActiveGrams = fastActive,
            carbMediumActiveGrams = mediumActive,
            carbProteinSlowActiveGrams = proteinActive,
            residualCarbsNowGrams = residualNow,
            residualCarbs30mGrams = residual30,
            residualCarbs60mGrams = residual60,
            residualCarbs120mGrams = residual120,
            foodDisplayProjection = foodDisplayProjection
        )
    }

    private fun TherapyStepSeries.withResolvedMealPressure(resolvedMealSteps: DoubleArray): TherapyStepSeries {
        val (resolvedSteps) = combinedClampedSteps(
            insulinSteps = insulinSteps,
            mealSteps = resolvedMealSteps
        )
        return copy(resolvedSteps = resolvedSteps)
    }

    private fun combinedClampedSteps(
        insulinSteps: DoubleArray,
        mealSteps: DoubleArray
    ): Pair<DoubleArray, DoubleArray> {
        require(insulinSteps.size == mealSteps.size)
        val steps = DoubleArray(insulinSteps.size)
        val cumClamped = DoubleArray(insulinSteps.size)
        var cumulativeRaw = 0.0
        var previousClamped = 0.0
        for (j in 1 until insulinSteps.size) {
            cumulativeRaw += insulinSteps.getOrElse(j) { 0.0 } + mealSteps.getOrElse(j) { 0.0 }
            val clamped = cumulativeRaw.coerceIn(-THERAPY_CUM_CLAMP_ABS, THERAPY_CUM_CLAMP_ABS)
            cumClamped[j] = clamped
            steps[j] = clamped - previousClamped
            previousClamped = clamped
        }
        return steps to cumClamped
    }

    private fun buildHistoricalKnownInputSeries(
        glucose: List<GlucosePoint>,
        events: List<TherapyEvent>,
        profiledCarbEvents: List<ProfiledCarbEvent>,
        factors: SensitivityFactors
    ): HistoricalKnownInputSeries {
        val therapyDelta = DoubleArray(glucose.size)
        val uamDelta = DoubleArray(glucose.size)
        val rocPerMin = DoubleArray(glucose.size)
        val insulinDelta = DoubleArray(glucose.size)
        val announcedCarbDelta = DoubleArray(glucose.size)
        if (glucose.size < 2) {
            return HistoricalKnownInputSeries(
                therapyDelta = therapyDelta,
                uamDelta = uamDelta,
                rocPerMin = rocPerMin,
                insulinDelta = insulinDelta,
                announcedCarbDelta = announcedCarbDelta
            )
        }

        for (index in 1 until glucose.size) {
            val prev = glucose[index - 1]
            val current = glucose[index]
            val dtMinutes = (current.ts - prev.ts) / 60_000.0
            if (dtMinutes <= 0.0) continue

            val components = intervalTherapyComponents(
                events = events,
                profiledCarbEvents = profiledCarbEvents,
                startTs = prev.ts,
                endTs = current.ts,
                factors = factors
            )
            insulinDelta[index] = components.insulinDelta
            announcedCarbDelta[index] = components.announcedCarbDelta
            therapyDelta[index] = (components.insulinDelta + components.announcedCarbDelta)
                .coerceIn(-THERAPY_CUM_CLAMP_ABS, THERAPY_CUM_CLAMP_ABS)
            rocPerMin[index] = therapyDelta[index] / dtMinutes
        }

        return HistoricalKnownInputSeries(
            therapyDelta = therapyDelta,
            uamDelta = uamDelta,
            rocPerMin = rocPerMin,
            insulinDelta = insulinDelta,
            announcedCarbDelta = announcedCarbDelta
        )
    }

    private fun predictLegacy(glucose: List<GlucosePoint>, therapyEvents: List<TherapyEvent>): List<Forecast> {
        if (glucose.isEmpty()) return emptyList()
        val sortedGlucose = glucose.sortedBy { it.ts }
        val nowPoint = sortedGlucose.last()
        val nowTs = nowPoint.ts
        val nowGlucose = nowPoint.valueMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
        val causalTherapyEvents = therapyEvents.filter { it.ts <= nowTs }

        val trend = estimateTrend(sortedGlucose)
        val factors = estimateSensitivityFactors(sortedGlucose, causalTherapyEvents)
        val volatility = estimateVolatility(sortedGlucose)
        val intervalPenalty = estimateIntervalPenalty(sortedGlucose)
        val profiledCarbEvents = profileCarbEvents(causalTherapyEvents, sortedGlucose, nowTs)

        return HORIZONS_MINUTES.map { horizon ->
            val trendDelta = trendDeltaAtHorizon(trend, horizon)
            val therapyDelta = therapyDeltaAtHorizon(
                events = causalTherapyEvents,
                profiledCarbEvents = profiledCarbEvents,
                nowTs = nowTs,
                horizonMinutes = horizon,
                factors = factors
            )
            val predicted = (nowGlucose + trendDelta + therapyDelta).coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)
            val ciHalfWidth = ciHalfWidth(
                horizonMinutes = horizon,
                volatility = volatility,
                intervalPenalty = intervalPenalty
            )

            Forecast(
                ts = nowTs + horizon * MINUTE_MS,
                horizonMinutes = horizon.toInt(),
                valueMmol = roundToStep(predicted, 0.01),
                ciLow = roundToStep((predicted - ciHalfWidth).coerceAtLeast(MIN_GLUCOSE_MMOL), 0.01),
                ciHigh = roundToStep((predicted + ciHalfWidth).coerceAtMost(MAX_GLUCOSE_MMOL), 0.01),
                modelVersion = LEGACY_MODEL_VERSION
            )
        }
    }

    private fun estimateTrend(points: List<GlucosePoint>): TrendEstimate {
        val shortWindow = points.takeLast(10)
        val longWindow = points.takeLast(24)
        val shortSlope = weightedSlopePer5m(shortWindow, halfLifeMinutes = 14.0)
        val longSlope = weightedSlopePer5m(longWindow, halfLifeMinutes = 40.0)
        val acceleration = (shortSlope - longSlope).coerceIn(-0.35, 0.35)
        return TrendEstimate(shortSlope, longSlope, acceleration)
    }

    private fun weightedSlopePer5m(points: List<GlucosePoint>, halfLifeMinutes: Double): Double {
        if (points.size < 2) return 0.0
        val lastTs = points.last().ts
        var weightedSum = 0.0
        var weightTotal = 0.0

        points.zipWithNext().forEach { (a, b) ->
            val dtMinutes = (b.ts - a.ts) / 60_000.0
            if (dtMinutes !in 2.0..15.0) return@forEach
            val slopePer5 = (b.valueMmol - a.valueMmol) / (dtMinutes / 5.0)
            val ageMinutes = ((lastTs - b.ts).coerceAtLeast(0L)) / 60_000.0
            val weight = kotlin.math.exp(-ln(2.0) * ageMinutes / halfLifeMinutes)
            weightedSum += slopePer5 * weight
            weightTotal += weight
        }

        if (weightTotal <= 1e-6) return 0.0
        return (weightedSum / weightTotal).coerceIn(-1.2, 1.2)
    }

    private fun trendDeltaAtHorizon(trend: TrendEstimate, horizonMinutes: Long): Double {
        val steps = horizonMinutes / 5.0
        val blendedSlope = 0.65 * trend.shortSlopePer5m + 0.35 * trend.longSlopePer5m
        val accelerationPart = 0.5 * trend.accelerationPer5m * steps.pow(2.0) * 0.35
        val raw = blendedSlope * steps + accelerationPart
        val maxAbs = maxTrendAbs(steps)
        return raw.coerceIn(-maxAbs, maxAbs)
    }

    private fun maxTrendAbs(steps: Int): Double = 0.55 * steps + 0.7
    private fun maxTrendAbs(steps: Double): Double = 0.55 * steps + 0.7

    private fun therapyDeltaAtHorizon(
        events: List<TherapyEvent>,
        profiledCarbEvents: List<ProfiledCarbEvent>,
        nowTs: Long,
        horizonMinutes: Long,
        factors: SensitivityFactors
    ): Double {
        if (events.isEmpty()) return 0.0
        val horizon = horizonMinutes.toDouble()
        var delta = 0.0
        val profiledByKey = profiledCarbEvents.associateBy { it.eventKey }
        val eventKeys = HashMap<TherapyEvent, String>(events.size)

        events.asSequence()
            .filter { it.ts <= nowTs }
            .filter { nowTs - it.ts <= EVENT_LOOKBACK_MS }
            .forEach { event ->
                val ageStart = ((nowTs - event.ts).coerceAtLeast(0L)) / 60_000.0
                val ageEnd = ageStart + horizon

                val carbsEffective = extractCarbsGramsForPrediction(event)
                if (carbsEffective != null && carbsEffective > 0.0) {
                    val profiled = profiledByKey[eventKeys.getOrPut(event) { eventKey(event) }]
                    val carbType = profiled?.type ?: CarbAbsorptionType.MEDIUM
                    val absorbed = (
                        carbCumulativeWithCutoff(carbType, ageEnd, profiled?.mealAbsorptionCurve) -
                            carbCumulativeWithCutoff(carbType, ageStart, profiled?.mealAbsorptionCurve)
                        ).coerceAtLeast(0.0)
                    delta += carbsEffective * factors.carbSensitivityMmolPerGram * absorbed
                }

                val modeledInsulin = modeledInsulinEvent(event)
                if (modeledInsulin != null) {
                    val active = (insulinCumulative(ageEnd) - insulinCumulative(ageStart)).coerceAtLeast(0.0)
                    delta -= modeledInsulin.units * factors.isfMmolPerUnit * modeledInsulin.impactScale * active
                }
            }

        return delta.coerceIn(-THERAPY_CUM_CLAMP_ABS, THERAPY_CUM_CLAMP_ABS)
    }

    private fun intervalTherapyComponents(
        events: List<TherapyEvent>,
        profiledCarbEvents: List<ProfiledCarbEvent>,
        startTs: Long,
        endTs: Long,
        factors: SensitivityFactors
    ): TherapyIntervalComponents {
        if (events.isEmpty() || endTs <= startTs) return TherapyIntervalComponents()
        var insulinDelta = 0.0
        var announcedCarbDelta = 0.0
        val profiledByKey = profiledCarbEvents.associateBy { it.eventKey }
        val eventKeys = HashMap<TherapyEvent, String>(events.size)

        events.asSequence()
            .filter { it.ts <= endTs }
            .filter { endTs - it.ts <= EVENT_LOOKBACK_MS }
            .forEach { event ->
                val ageStart = ((startTs - event.ts).coerceAtLeast(0L)) / 60_000.0
                val ageEnd = ((endTs - event.ts).coerceAtLeast(0L)) / 60_000.0

                val carbsEffective = extractCarbsGramsForPrediction(event)
                if (carbsEffective != null && carbsEffective > 0.0) {
                    val profiled = profiledByKey[eventKeys.getOrPut(event) { eventKey(event) }]
                    val carbType = profiled?.type ?: CarbAbsorptionType.MEDIUM
                    val absorbed = (
                        carbCumulativeWithCutoff(carbType, ageEnd, profiled?.mealAbsorptionCurve) -
                            carbCumulativeWithCutoff(carbType, ageStart, profiled?.mealAbsorptionCurve)
                        ).coerceAtLeast(0.0)
                    announcedCarbDelta += carbsEffective * factors.carbSensitivityMmolPerGram * absorbed
                }

                val modeledInsulin = modeledInsulinEvent(event)
                if (modeledInsulin != null) {
                    val active = (insulinCumulative(ageEnd) - insulinCumulative(ageStart)).coerceAtLeast(0.0)
                    insulinDelta -= modeledInsulin.units * factors.isfMmolPerUnit * modeledInsulin.impactScale * active
                }
            }

        return TherapyIntervalComponents(
            insulinDelta = insulinDelta.takeIf { it.isFinite() } ?: 0.0,
            announcedCarbDelta = announcedCarbDelta.takeIf { it.isFinite() } ?: 0.0
        )
    }

    private fun estimateSensitivityFactors(
        glucose: List<GlucosePoint>,
        events: List<TherapyEvent>
    ): SensitivityFactors {
        val sortedGlucose = glucose.sortedBy { it.ts }
        val sortedEvents = events.sortedBy { it.ts }
        val estimatorLocal = sensitivityProfileEstimator
            .estimate(
                glucoseHistory = sortedGlucose,
                therapyEvents = sortedEvents,
                telemetrySignals = emptyList()
            )
            ?.let { it.isfMmolPerUnit to it.crGramPerUnit }
        val localIsfCr = estimatorLocal ?: (DEFAULT_ISF_MMOL_PER_UNIT to DEFAULT_CR_GRAM_PER_UNIT)
        val external = externalSensitivityOverrides
        val blendedIsf = blendSensitivityMetric(
            localValue = localIsfCr.first,
            override = external.isf,
            min = EXTERNAL_ISF_MIN,
            max = EXTERNAL_ISF_MAX
        )
        val blendedCr = blendSensitivityMetric(
            localValue = localIsfCr.second,
            override = external.cr,
            min = EXTERNAL_CR_MIN,
            max = EXTERNAL_CR_MAX
        )
        val csf = (blendedIsf / blendedCr).coerceIn(0.05, 0.40)
        return SensitivityFactors(isfMmolPerUnit = blendedIsf, carbSensitivityMmolPerGram = csf)
    }

    private fun blendSensitivityMetric(
        localValue: Double,
        override: SensitivityMetricOverride?,
        min: Double,
        max: Double
    ): Double {
        if (override == null || override.confidence < override.minConfidenceRequired) return localValue
        if (override.authoritative) return override.value.coerceIn(min, max)
        val confidenceScale = if (override.minConfidenceRequired >= 0.999) {
            1.0
        } else {
            ((override.confidence - override.minConfidenceRequired) / (1.0 - override.minConfidenceRequired))
                .coerceIn(0.0, 1.0)
        }
        val effectiveBlend = (
            override.blendWeight * (EXTERNAL_BLEND_BASE + EXTERNAL_BLEND_CONF_GAIN * confidenceScale)
            ).coerceIn(0.0, 1.0)
        return (localValue * (1.0 - effectiveBlend) + override.value * effectiveBlend).coerceIn(min, max)
    }

    private fun SensitivityMetricOverride.bounded(min: Double, max: Double): SensitivityMetricOverride? {
        if (!value.isFinite() || !confidence.isFinite() || !minConfidenceRequired.isFinite() || !blendWeight.isFinite()) {
            return null
        }
        return copy(
            value = value.coerceIn(min, max),
            confidence = confidence.coerceIn(0.0, 1.0),
            minConfidenceRequired = minConfidenceRequired.coerceIn(0.0, 1.0),
            blendWeight = blendWeight.coerceIn(0.0, 1.0),
            source = source.ifBlank { "external" }
        )
    }

    private fun eventCanCarryInsulin(event: TherapyEvent): Boolean {
        val type = normalize(event.type)
        return type.contains("bolus") || type.contains("correction") || type == "insulin"
    }

    private fun modeledInsulinEvent(event: TherapyEvent): ModeledInsulinEvent? {
        if (!eventCanCarryInsulin(event)) return null
        val units = extractInsulinUnits(event)?.takeIf { it > 0.0 } ?: return null
        val inferred = isInferredInsulinEvent(event)
        return ModeledInsulinEvent(
            units = units,
            impactScale = if (inferred) INFERRED_INSULIN_IMPACT_SCALE else 1.0,
            inferred = inferred
        )
    }

    private fun isInferredInsulinEvent(event: TherapyEvent): Boolean {
        val inferredFlag = event.payload["inferred"]
            ?.trim()
            ?.lowercase(Locale.US)
        val method = event.payload["method"]
            ?.trim()
            ?.lowercase(Locale.US)
        return inferredFlag == "true" || method == "iob_jump"
    }

    private fun extractCarbsGrams(event: TherapyEvent): Double? {
        return payloadDouble(event, "grams", "carbs", "enteredCarbs", "mealCarbs")
            ?.takeIf { it in 0.5..400.0 }
    }

    private fun extractCarbsGramsForPrediction(event: TherapyEvent): Double? {
        if (isSyntheticUamCarbEvent(event)) return null
        if (enableEnhancedPredictionV3) {
            return io.aaps.copilot.domain.nutrition.MealCarbLimits.announcedGrams(event, carbComputationMaxGrams)
        }
        return extractCarbsGrams(event)?.coerceAtMost(carbComputationMaxGrams)
    }

    private fun extractInsulinUnits(event: TherapyEvent): Double? {
        return payloadDouble(event, "units", "bolusUnits", "insulin", "enteredInsulin")
            ?.takeIf { it in 0.02..30.0 }
    }

    private fun payloadDouble(event: TherapyEvent, vararg keys: String): Double? {
        if (event.payload.isEmpty()) return null
        val normalizedKeys = keys.map(::normalize)
        for (candidate in normalizedKeys) {
            for ((rawKey, rawValue) in event.payload) {
                if (normalize(rawKey) == candidate) {
                    return rawValue.replace(",", ".").toDoubleOrNull()
                }
            }
        }
        return null
    }

    private fun carbCumulative(type: CarbAbsorptionType, ageMinutes: Double): Double {
        return CarbAbsorptionProfiles.cumulative(type, ageMinutes)
    }

    private fun carbCumulativeWithCutoff(
        type: CarbAbsorptionType,
        ageMinutes: Double,
        mealAbsorptionCurve: MealAbsorptionCurve? = null,
        maxAgeMinutes: Double = carbAbsorptionMaxAgeMinutes
    ): Double {
        val boundedAge = ageMinutes.coerceAtLeast(0.0)
        mealAbsorptionCurve?.let { return it.absorbedFraction(boundedAge) }
        if (boundedAge >= maxAgeMinutes) return 1.0
        return carbCumulative(type = type, ageMinutes = boundedAge)
    }

    private fun profileCarbEvents(
        events: List<TherapyEvent>,
        glucose: List<GlucosePoint>,
        nowTs: Long
    ): List<ProfiledCarbEvent> {
        val context = mealAbsorptionContext.takeIf { enableEnhancedPredictionV3 && it.enabled }
        return events.asSequence()
            .mapNotNull { event ->
                val ageMinutes = ((nowTs - event.ts).coerceAtLeast(0L)) / 60_000.0
                if (ageMinutes > EVENT_LOOKBACK_MS / 60_000.0) return@mapNotNull null
                val grams = extractCarbsGramsForPrediction(event)
                    ?: return@mapNotNull null
                if (grams <= 0.0) return@mapNotNull null
                val classified = CarbAbsorptionProfiles.classifyCarbEvent(event, glucose, nowTs)
                val mealAbsorption = resolveMealAbsorption(
                    event = event,
                    classifiedType = classified.type,
                    context = context
                )
                if (mealAbsorption == null && ageMinutes > carbAbsorptionMaxAgeMinutes) {
                    return@mapNotNull null
                }
                ProfiledCarbEvent(
                    event = event,
                    eventKey = eventKey(event),
                    grams = grams,
                    type = mealAbsorption?.resolved?.profile?.toLegacyCarbType() ?: classified.type,
                    reason = mealAbsorption?.let {
                        absorptionProvenance(it.resolved, it.reference)
                    } ?: classified.reason,
                    mealAbsorptionCurve = mealAbsorption?.let {
                        MealAbsorptionCurve(it.resolved.profile, it.resolved.durationMinutes)
                    }
                )
            }
            .toList()
    }

    private fun resolveMealAbsorption(
        event: TherapyEvent,
        classifiedType: CarbAbsorptionType,
        context: MealAbsorptionContext?
    ): ResolvedMealAbsorptionForEvent? = if (context == null || classifiedType == CarbAbsorptionType.ULTRA_FAST) {
        null
    } else {
        val reference = event.toMealTherapyReference()
        ResolvedMealAbsorptionForEvent(
            resolved = mealAbsorptionProfileResolver.resolve(
                perMeal = context.perMealOverride(reference),
                manual = context.manual,
                auto = context.auto
            ),
            reference = reference
        )
    }

    private fun absorptionProvenance(
        resolved: ResolvedMealAbsorption,
        reference: MealTherapyReference
    ): String =
        "identity=${reference.identity};revision=${reference.revision ?: "absent"};" +
            "reference=${reference.trust.name};" +
            "profile=${resolved.profile.name};duration=${resolved.durationMinutes};source=${resolved.source.name}"

    private fun MealAbsorptionProfile.toLegacyCarbType(): CarbAbsorptionType = when (this) {
        MealAbsorptionProfile.FAST -> CarbAbsorptionType.FAST
        MealAbsorptionProfile.MIXED -> CarbAbsorptionType.MEDIUM
        MealAbsorptionProfile.FAT_PROTEIN -> CarbAbsorptionType.PROTEIN_SLOW
    }

    private fun eventKey(event: TherapyEvent): String {
        val payloadHash = event.payload.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
            .hashCode()
        return "${event.ts}|${event.type}|$payloadHash"
    }

    private fun insulinCumulative(ageMinutes: Double): Double {
        val onsetAdjustedAge = (ageMinutes - insulinOnsetShiftMinutes).coerceAtLeast(0.0)
        val scaledAge = (onsetAdjustedAge * insulinAgeScale).coerceAtLeast(0.0)
        return insulinProfile.cumulativeAt(scaledAge)
    }

    private fun estimateVolatility(points: List<GlucosePoint>): Double {
        val deltasPer5m = points
            .takeLast(16)
            .zipWithNext()
            .mapNotNull { (a, b) ->
                val dtMinutes = (b.ts - a.ts) / 60_000.0
                if (dtMinutes !in 2.0..15.0) return@mapNotNull null
                (b.valueMmol - a.valueMmol) / (dtMinutes / 5.0)
            }
        if (deltasPer5m.size < 2) return 0.0
        return stddev(deltasPer5m).coerceIn(0.0, 1.5)
    }

    private fun estimateIntervalPenalty(points: List<GlucosePoint>): Double {
        val intervals = points.takeLast(16).zipWithNext().map { (a, b) ->
            ((b.ts - a.ts).coerceAtLeast(0L)) / 60_000.0
        }.filter { it > 0.0 }
        if (intervals.isEmpty()) return 0.2
        val median = median(intervals)
        return when {
            median > 9.0 -> 0.30
            median > 7.0 -> 0.16
            median > 6.0 -> 0.08
            else -> 0.0
        }
    }

    private fun ciHalfWidth(horizonMinutes: Long, volatility: Double, intervalPenalty: Double): Double {
        val base = when (horizonMinutes) {
            5L -> 0.35
            30L -> 0.90
            60L -> 1.25
            else -> 1.0
        }
        val volatilityGain = when (horizonMinutes) {
            5L -> 0.45
            30L -> 0.80
            60L -> 1.15
            else -> 0.75
        }
        return (base + volatility * volatilityGain + intervalPenalty).coerceIn(0.30, 3.2)
    }

    private fun stddev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean).pow(2.0) } / values.size
        return sqrt(variance)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun List<GlucosePoint>.closestTo(targetTs: Long, maxDistanceMs: Long): GlucosePoint? {
        return this.minByOrNull { point ->
            abs(point.ts - targetTs)
        }?.takeIf { abs(it.ts - targetTs) <= maxDistanceMs }
    }

    private fun roundToStep(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        val scaled = value / step
        return floor(scaled + 0.5) * step
    }

    private fun normalize(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""

        val out = StringBuilder(trimmed.length + 4)
        var pendingSeparator = false
        var previousWasLowerOrDigit = false
        trimmed.forEach { char ->
            if (char.isLetterOrDigit()) {
                if (char.isUpperCase() && previousWasLowerOrDigit && out.isNotEmpty() && out.last() != '_') {
                    out.append('_')
                } else if (pendingSeparator && out.isNotEmpty() && out.last() != '_') {
                    out.append('_')
                }
                out.append(char.lowercaseChar())
                pendingSeparator = false
                previousWasLowerOrDigit = char.isLowerCase() || char.isDigit()
            } else {
                pendingSeparator = out.isNotEmpty()
                previousWasLowerOrDigit = false
            }
        }
        return out.toString()
    }

    private fun currentInsulinDurationHours(): Double {
        return (insulinProfile.defaultDurationMinutes / insulinAgeScale / 60.0)
            .coerceIn(MIN_INSULIN_DURATION_HOURS, MAX_INSULIN_DURATION_HOURS)
    }

    internal data class V3Diagnostics(
        val gNowRaw: Double,
        val gNowUsed: Double,
        val rocPer5Used: Double,
        val knownInputTherapyStep1: Double = 0.0,
        val knownInputUamStep1: Double = 0.0,
        val knownInputInsulinStep1: Double = 0.0,
        val knownInputAnnouncedCarbStep1: Double = 0.0,
        val kfSigmaG: Double,
        val kfEwmaNis: Double,
        val kfSigmaZ: Double,
        val kfSigmaA: Double,
        val kfWarmedUp: Boolean,
        val kfHistoryRebuilt: Boolean = false,
        val kfHistoryRevised: Boolean = false,
        val kfAppliedUpdates: Int = 0,
        val insulinProfileId: String,
        val insulinDurationHours: Double?,
        val insulinAgeScale: Double,
        val residualRoc0: Double,
        val uci0: Double,
        val uciMax: Double,
        val k: Double,
        val uamActive: Boolean,
        val virtualMealCarbs: Double?,
        val virtualMealConfidence: Double?,
        val usingVirtualMeal: Boolean,
        val uamTailGuardMultiplier: Double,
        val runtimeHintCarbs: Double? = null,
        val runtimeHintConfidence: Double? = null,
        val runtimeHintSource: String? = null,
        val unifiedUamTimestamp: Long = 0L,
        val unifiedUamState: String = UAM_DISABLED_STATE,
        val unifiedUamActiveForForecast: Boolean = false,
        val unifiedUamConfidence: Double = 0.0,
        val unifiedUamImpactMmol5: Double = 0.0,
        val unifiedUamSignedResidualMmol5: Double = 0.0,
        val unifiedUamShortAverageDeltaMmol5: Double = 0.0,
        val unifiedUamEquivalentCarbsGrams: Double? = null,
        val unifiedUamLowerBoundCarbsGrams: Double? = null,
        val unifiedUamOnsetTs: Long? = null,
        val unifiedUamFirstDetectionTs: Long? = null,
        val unifiedUamActiveSinceTs: Long? = null,
        val unifiedUamSupportStableBuckets: Int = 0,
        val unifiedUamLowerBoundStableBuckets: Int = 0,
        val unifiedUamSource: String = UAM_DISABLED_SOURCE,
        val unifiedUamReasons: Set<String> = emptySet(),
        val unifiedUamAlgorithmVersion: String = UNIFIED_UAM_ALGORITHM_VERSION,
        val unifiedUamSensorTrust: Double = UNKNOWN_UAM_SENSOR_TRUST,
        val unifiedUamTherapyCoverage: Double = UNKNOWN_UAM_THERAPY_COVERAGE,
        val unifiedUamActiveForControl: Boolean = false,
        val unifiedUamQualityProvisional: Boolean = true,
        val announcedCarbCoverage: Double = UNKNOWN_ANNOUNCED_CARB_COVERAGE,
        val carbAbsorptionProvenance: List<String> = emptyList(),
        val resolvedMealPressureSource: String = "NONE",
        val resolvedMealPressureStep: List<Double> = emptyList(),
        val resolvedTherapyStep: List<Double> = emptyList(),
        val insulinStep: List<Double> = emptyList(),
        val announcedCarbStep: List<Double> = emptyList(),
        val announcedMealPressureWeight: Double = 0.0,
        val uamMealPressureWeight: Double = 0.0,
        val legacyVirtualMealUsed: Boolean = false,
        val doubleCountPrevented: Boolean = false,
        val arMu: Double,
        val arPhi: Double,
        val arSigmaE: Double,
        val arUsedFallback: Boolean,
        val therapyStep: List<Double>,
        val therapyCumClamped: List<Double>,
        val carbFastActiveGrams: Double,
        val carbMediumActiveGrams: Double,
        val carbProteinSlowActiveGrams: Double,
        val residualCarbsNowGrams: Double,
        val residualCarbs30mGrams: Double,
        val residualCarbs60mGrams: Double,
        val residualCarbs120mGrams: Double,
        val uamStep: List<Double>,
        val rawUnifiedUamStep: List<Double> = emptyList(),
        val trendStep: List<Double>,
        val glucosePath: List<Double>,
        val trendCum60Raw: Double,
        val trendCum60Clamped: Double,
        val predByHorizon: Map<Int, Double>,
        val unifiedUamSensitivityCycleId: String = "",
        val unifiedUamSensitivitySettingsRevision: Long = -1L,
        val unifiedUamSensitivityIsfMmolPerUnit: Double? = null,
        val unifiedUamSensitivityCrGramPerUnit: Double? = null,
        val foodDisplayProjection: MealFoodDisplayProjection? = null
    ) {
        val resolvedUamAttributionStep: List<Double>
            get() = uamStep
    }

    private data class TrendEstimate(
        val shortSlopePer5m: Double,
        val longSlopePer5m: Double,
        val accelerationPer5m: Double
    )

    private data class UamRuntimeQualityContext(
        val sensorTrust: Double,
        val therapyCoverage: Double,
        val announcedCarbCoverage: Double,
        val sensorBlocked: Boolean
    )

    private data class SensitivityFactors(
        val isfMmolPerUnit: Double,
        val carbSensitivityMmolPerGram: Double
    )

    private data class RuntimeTherapyEvent(
        val ts: Long,
        val grams: Double?,
        val carbType: CarbAbsorptionType?,
        val mealAbsorptionCurve: MealAbsorptionCurve?,
        val displayGlycemicIndex: MealGlycemicIndex?,
        val insulinUnits: Double?,
        val carryInsulin: Boolean,
        val insulinImpactScale: Double
    )

    internal data class ModeledActiveInsulinEvidence(
        val activeUnits: Double = 0.0,
        val eventCount: Int = 0,
        val latestEvidenceTimestamp: Long? = null,
        val qualifiedEventCount: Int = 0,
        val latestQualifiedEvidenceTimestamp: Long? = null
    )

    private data class ModeledInsulinEvent(
        val units: Double,
        val impactScale: Double,
        val inferred: Boolean
    )

    private data class ExternalSensitivityOverrides(
        val isf: SensitivityMetricOverride? = null,
        val cr: SensitivityMetricOverride? = null
    )

    private data class UamRuntimeHint(
        val ingestionTs: Long,
        val carbsGrams: Double,
        val confidence: Double,
        val source: String
    )

    private data class TherapyStepSeries(
        val insulinSteps: DoubleArray,
        val announcedCarbSteps: DoubleArray,
        val legacySteps: DoubleArray,
        val resolvedSteps: DoubleArray,
        val cumClamped: DoubleArray,
        val carbFastActiveGrams: Double,
        val carbMediumActiveGrams: Double,
        val carbProteinSlowActiveGrams: Double,
        val residualCarbsNowGrams: Double,
        val residualCarbs30mGrams: Double,
        val residualCarbs60mGrams: Double,
        val residualCarbs120mGrams: Double,
        val foodDisplayProjection: MealFoodDisplayProjection? = null
    )

    private data class TherapyIntervalComponents(
        val insulinDelta: Double = 0.0,
        val announcedCarbDelta: Double = 0.0
    )

    private data class HistoricalKnownInputSeries(
        val therapyDelta: DoubleArray,
        val uamDelta: DoubleArray,
        val rocPerMin: DoubleArray,
        val insulinDelta: DoubleArray,
        val announcedCarbDelta: DoubleArray
    )

    internal data class ResolvedMealPressureSnapshot(
        val steps: DoubleArray,
        val announcedWeight: Double,
        val uamWeight: Double,
        val source: String
    )

    private data class ProfiledCarbEvent(
        val event: TherapyEvent,
        val eventKey: String,
        val grams: Double,
        val type: CarbAbsorptionType,
        val reason: String,
        val mealAbsorptionCurve: MealAbsorptionCurve?
    )

    private data class ResolvedMealAbsorptionForEvent(
        val resolved: ResolvedMealAbsorption,
        val reference: MealTherapyReference
    )

    private companion object {
        const val LEGACY_MODEL_VERSION = "local-hybrid-v2"
        const val ENHANCED_MODEL_VERSION = "local-hybrid-v3"
        const val MIN_GLUCOSE_MMOL = 2.2
        const val MAX_GLUCOSE_MMOL = 22.0
        const val DEFAULT_ISF_MMOL_PER_UNIT = 2.3
        const val DEFAULT_CR_GRAM_PER_UNIT = 10.0
        const val DEFAULT_PROFILE_DURATION_MINUTES = 300.0
        const val MIN_INSULIN_DURATION_HOURS = 1.5
        const val MAX_INSULIN_DURATION_HOURS = 12.0
        const val INSULIN_AGE_SCALE_MIN = 0.4
        const val INSULIN_AGE_SCALE_MAX = 2.0
        const val EXTERNAL_SENSITIVITY_CONFIDENCE_THRESHOLD = 0.55
        const val EXTERNAL_ISF_MIN = 0.8
        const val EXTERNAL_ISF_MAX = 18.0
        const val EXTERNAL_CR_MIN = 2.0
        const val EXTERNAL_CR_MAX = 60.0
        const val EXTERNAL_BLEND_BASE = 0.35
        const val EXTERNAL_BLEND_CONF_GAIN = 0.65
        const val DEFAULT_CARB_ABSORPTION_MAX_AGE_MINUTES = 180.0
        const val DEFAULT_CARB_COMPUTATION_MAX_GRAMS = 60.0
        const val INFERRED_INSULIN_IMPACT_SCALE = 0.45
        const val MINUTE_MS = 60_000L
        const val FIVE_MINUTES_MS = 5 * MINUTE_MS
        const val EVENT_LOOKBACK_MS = 8 * 60 * MINUTE_MS

        const val STEPS_MAX = 12
        const val THERAPY_CUM_CLAMP_ABS = 6.0
        val HORIZONS_MINUTES = listOf(5L, 30L, 60L)

        const val UNKNOWN_UAM_SENSOR_TRUST = 0.0
        const val UNKNOWN_UAM_THERAPY_COVERAGE = 0.0
        const val UNKNOWN_ANNOUNCED_CARB_COVERAGE = 0.0
        const val UAM_DISABLED_STATE = "DISABLED"
        const val UAM_DISABLED_SOURCE = "disabled"
        const val UNIFIED_UAM_ALGORITHM_VERSION = "unified-uam-v1"
        const val INVALID_MEAL_PRESSURE_SOURCE = "INVALID_INPUT"
        const val TREND_ROC_HALF_LIFE_MIN = 20.0

        const val CI_UAM_ALPHA = 1.0
        const val CI_UAM_ADD_MAX = 0.8
        const val CI_ROC_ALPHA = 0.35
        const val CI_ROC_ADD_MAX = 0.6
        const val CI_KF_ALPHA = 0.90
        const val CI_KF_ADD_MAX = 0.6
        const val CI_AR_ALPHA = 0.70
        const val CI_AR_ADD_MAX = 0.7

        const val KF_MIN_UPDATES = 3
    }
}
