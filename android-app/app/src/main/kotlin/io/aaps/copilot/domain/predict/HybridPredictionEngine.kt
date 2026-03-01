package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.Forecast
import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

class HybridPredictionEngine(
    private val enableEnhancedPredictionV3: Boolean = false,
    private val enableUam: Boolean = true,
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
    private var lastDiagnostics: V3Diagnostics? = null

    private val kalmanFilterV3 = KalmanGlucoseFilterV3()
    private val residualArModel = ResidualArModel()
    private val uamEstimatorV3 = UamEstimator()

    fun setInsulinProfile(profileId: InsulinActionProfileId) {
        insulinProfileId = profileId
        insulinProfile = InsulinActionProfiles.profile(profileId)
    }

    fun setInsulinProfile(profileIdRaw: String?) {
        setInsulinProfile(InsulinActionProfileId.fromRaw(profileIdRaw))
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

    internal fun lastDiagnosticsForTest(): V3Diagnostics? = lastDiagnostics
    internal fun currentInsulinProfileForTest(): InsulinActionProfileId = insulinProfileId

    private fun predictEnhancedV3(glucose: List<GlucosePoint>, therapyEvents: List<TherapyEvent>): List<Forecast> {
        val sortedGlucose = deduplicateAndSort(glucose)
        if (sortedGlucose.isEmpty()) return emptyList()

        val nowPoint = sortedGlucose.last()
        val nowTs = nowPoint.ts
        val gNowRaw = nowPoint.valueMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)

        val trend = estimateTrend(sortedGlucose)
        val factors = estimateSensitivityFactors(sortedGlucose, therapyEvents)
        val volatility = estimateVolatility(sortedGlucose)
        val intervalPenalty = estimateIntervalPenalty(sortedGlucose)
        val volNorm = (volatility / 1.5).coerceIn(0.0, 1.0)

        var kfSnapshot: KalmanSnapshotV3? = null
        sortedGlucose.forEach { point ->
            kfSnapshot = kalmanFilterV3.update(
                zMmol = point.valueMmol,
                ts = point.ts,
                volNorm = volNorm
            )
        }

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

        val profiledCarbEvents = profileCarbEvents(
            events = therapyEvents,
            glucose = sortedGlucose,
            nowTs = nowTs
        )
        val carbTypeByEventKey = profiledCarbEvents.associate { profiled ->
            eventKey(profiled.event) to profiled.type
        }

        val therapySeries = buildTherapyStepSeries(
            events = therapyEvents,
            profiledCarbEvents = profiledCarbEvents,
            nowTs = nowTs,
            factors = factors
        )

        val uamSeries = if (enableUam) {
            uamEstimatorV3.estimate(
                context = UamEstimator.Context(
                    glucose = sortedGlucose,
                    therapyEvents = therapyEvents,
                    nowTs = nowTs,
                    isfMmolPerUnit = factors.isfMmolPerUnit,
                    csfMmolPerGram = factors.carbSensitivityMmolPerGram,
                    enableVirtualMealFit = enableUamVirtualMealFit,
                    carbCumulativeForEvent = { event, ageMinutes ->
                        val type = carbTypeByEventKey[eventKey(event)] ?: CarbAbsorptionType.MEDIUM
                        carbCumulative(type, ageMinutes)
                    },
                    insulinCumulative = ::insulinCumulative,
                    extractCarbsGrams = ::extractCarbsGrams,
                    extractInsulinUnits = ::extractInsulinUnits,
                    eventCanCarryInsulin = ::eventCanCarryInsulin
                )
            )
        } else {
            UamEstimator.Result.zero(factors.carbSensitivityMmolPerGram)
        }

        var residualRoc0 = rocPer5Used - therapySeries.steps[1] - uamSeries.steps[1]
        if (uamSeries.uci0 >= UAM_ACTIVE_THRESHOLD) {
            residualRoc0 = minOf(0.0, residualRoc0)
        }
        residualRoc0 = residualRoc0.coerceIn(-1.2, 1.2)

        residualArModel.appendOrUpdate(nowTs = nowTs, residualRocPer5 = residualRoc0)
        val arParams = residualArModel.fit(
            uamActive = uamSeries.uci0 >= UAM_ACTIVE_THRESHOLD,
            trendRocHalfLifeMin = TREND_ROC_HALF_LIFE_MIN
        )

        val trendStepRaw = residualArModel.forecastSteps(
            residualRoc0 = residualRoc0,
            params = arParams,
            steps = STEPS_MAX
        )

        val trendCumRaw = DoubleArray(STEPS_MAX + 1)
        for (j in 1..STEPS_MAX) {
            trendCumRaw[j] = trendCumRaw[j - 1] + trendStepRaw[j]
        }
        val trend60Raw = trendCumRaw[STEPS_MAX]
        val trend60Clamped = trend60Raw.coerceIn(-maxTrendAbs(STEPS_MAX), maxTrendAbs(STEPS_MAX))
        val trendScale = if (abs(trend60Raw) < 1e-6) 1.0 else trend60Clamped / trend60Raw

        val trendStep = DoubleArray(STEPS_MAX + 1)
        for (j in 1..STEPS_MAX) {
            trendStep[j] = trendStepRaw[j] * trendScale
        }

        val glucosePath = DoubleArray(STEPS_MAX + 1)
        glucosePath[0] = gNowUsed
        for (j in 1..STEPS_MAX) {
            val delta = trendStep[j] + therapySeries.steps[j] + uamSeries.steps[j]
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
            val ciAddUam = (CI_UAM_ALPHA * sqrt(n) * uamSeries.uci0).coerceIn(0.0, CI_UAM_ADD_MAX)
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

        lastDiagnostics = V3Diagnostics(
            gNowRaw = gNowRaw,
            gNowUsed = gNowUsed,
            rocPer5Used = rocPer5Used,
            kfSigmaG = kfSnapshot?.sigmaG ?: 0.0,
            kfEwmaNis = kfSnapshot?.ewmaNis ?: 1.0,
            kfSigmaZ = kfSnapshot?.sigmaZ ?: 0.18,
            kfSigmaA = kfSnapshot?.sigmaA ?: 0.02,
            kfWarmedUp = warmedUp,
            insulinProfileId = insulinProfileId.name,
            residualRoc0 = residualRoc0,
            uci0 = uamSeries.uci0,
            uciMax = uamSeries.uciMax,
            k = uamSeries.k,
            uamActive = uamSeries.active,
            virtualMealCarbs = uamSeries.virtualMealCarbs,
            virtualMealConfidence = uamSeries.virtualMealConfidence,
            usingVirtualMeal = uamSeries.usingVirtualMeal,
            arMu = arParams.mu,
            arPhi = arParams.phi,
            arSigmaE = arParams.sigmaE,
            arUsedFallback = arParams.usedFallback,
            therapyStep = therapySeries.steps.toList(),
            therapyCumClamped = therapySeries.cumClamped.toList(),
            carbFastActiveGrams = therapySeries.carbFastActiveGrams,
            carbMediumActiveGrams = therapySeries.carbMediumActiveGrams,
            carbProteinSlowActiveGrams = therapySeries.carbProteinSlowActiveGrams,
            residualCarbsNowGrams = therapySeries.residualCarbsNowGrams,
            residualCarbs30mGrams = therapySeries.residualCarbs30mGrams,
            residualCarbs60mGrams = therapySeries.residualCarbs60mGrams,
            residualCarbs120mGrams = therapySeries.residualCarbs120mGrams,
            uamStep = uamSeries.steps.toList(),
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
            append(" rocPer5=").append(roundToStep(diagnostics.rocPer5Used, 0.001))
            append(" therapyStep1=").append(roundToStep(diagnostics.therapyStep.getOrElse(1) { 0.0 }, 0.001))
            append(" carbFast=").append(roundToStep(diagnostics.carbFastActiveGrams, 0.1))
            append(" carbMedium=").append(roundToStep(diagnostics.carbMediumActiveGrams, 0.1))
            append(" carbProtein=").append(roundToStep(diagnostics.carbProteinSlowActiveGrams, 0.1))
            append(" residualCarbs60=").append(roundToStep(diagnostics.residualCarbs60mGrams, 0.1))
            append(" uamStep1=").append(roundToStep(diagnostics.uamStep.getOrElse(1) { 0.0 }, 0.001))
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

    private fun buildTherapyStepSeries(
        events: List<TherapyEvent>,
        profiledCarbEvents: List<ProfiledCarbEvent>,
        nowTs: Long,
        factors: SensitivityFactors
    ): TherapyStepSeries {
        val stepsRaw = DoubleArray(STEPS_MAX + 1)
        val relevantEvents = events.asSequence()
            .filter { nowTs - it.ts <= EVENT_LOOKBACK_MS }
            .filter { it.ts <= nowTs + STEPS_MAX * FIVE_MINUTES_MS }
            .toList()
        val profiledByKey = profiledCarbEvents.associateBy { eventKey(it.event) }

        for (j in 1..STEPS_MAX) {
            val stepEndTs = nowTs + j * FIVE_MINUTES_MS
            var stepDelta = 0.0
            relevantEvents.forEach { event ->
                if (event.ts > stepEndTs) return@forEach
                val age0 = maxOf(0.0, (nowTs - event.ts) / 60_000.0)
                val ageA = age0 + (j - 1) * 5.0
                val ageB = age0 + j * 5.0

                val carbs = extractCarbsGrams(event)
                if (carbs != null && carbs > 0.0) {
                    val profiled = profiledByKey[eventKey(event)]
                    val carbType = profiled?.type ?: CarbAbsorptionType.MEDIUM
                    stepDelta += carbs * factors.carbSensitivityMmolPerGram *
                        maxOf(0.0, carbCumulative(carbType, ageB) - carbCumulative(carbType, ageA))
                }

                val insulin = extractInsulinUnits(event)
                if (insulin != null && insulin > 0.0 && eventCanCarryInsulin(event)) {
                    stepDelta += -insulin * factors.isfMmolPerUnit *
                        maxOf(0.0, insulinCumulative(ageB) - insulinCumulative(ageA))
                }
            }
            stepsRaw[j] = stepDelta
        }

        val steps = DoubleArray(STEPS_MAX + 1)
        val cumClamped = DoubleArray(STEPS_MAX + 1)
        var cumulativeRaw = 0.0
        var prevClamped = 0.0
        for (j in 1..STEPS_MAX) {
            cumulativeRaw += stepsRaw[j]
            val clamped = cumulativeRaw.coerceIn(-THERAPY_CUM_CLAMP_ABS, THERAPY_CUM_CLAMP_ABS)
            cumClamped[j] = clamped
            steps[j] = clamped - prevClamped
            prevClamped = clamped
        }

        var fastActive = 0.0
        var mediumActive = 0.0
        var proteinActive = 0.0
        var residualNow = 0.0
        var residual30 = 0.0
        var residual60 = 0.0
        var residual120 = 0.0

        profiledCarbEvents.forEach { profiled ->
            val ageNow = maxOf(0.0, (nowTs - profiled.event.ts) / 60_000.0)
            val nowFraction = carbCumulative(profiled.type, ageNow).coerceIn(0.0, 1.0)
            val residualNowEvent = (profiled.grams * (1.0 - nowFraction)).coerceAtLeast(0.0)
            val residual30Event = (profiled.grams * (1.0 - carbCumulative(profiled.type, ageNow + 30.0))).coerceAtLeast(0.0)
            val residual60Event = (profiled.grams * (1.0 - carbCumulative(profiled.type, ageNow + 60.0))).coerceAtLeast(0.0)
            val residual120Event = (profiled.grams * (1.0 - carbCumulative(profiled.type, ageNow + 120.0))).coerceAtLeast(0.0)
            residualNow += residualNowEvent
            residual30 += residual30Event
            residual60 += residual60Event
            residual120 += residual120Event
            when (profiled.type) {
                CarbAbsorptionType.FAST -> fastActive += residualNowEvent
                CarbAbsorptionType.MEDIUM -> mediumActive += residualNowEvent
                CarbAbsorptionType.PROTEIN_SLOW -> proteinActive += residualNowEvent
            }
        }

        return TherapyStepSeries(
            steps = steps,
            cumClamped = cumClamped,
            carbFastActiveGrams = fastActive,
            carbMediumActiveGrams = mediumActive,
            carbProteinSlowActiveGrams = proteinActive,
            residualCarbsNowGrams = residualNow,
            residualCarbs30mGrams = residual30,
            residualCarbs60mGrams = residual60,
            residualCarbs120mGrams = residual120
        )
    }

    private fun predictLegacy(glucose: List<GlucosePoint>, therapyEvents: List<TherapyEvent>): List<Forecast> {
        if (glucose.isEmpty()) return emptyList()
        val sortedGlucose = glucose.sortedBy { it.ts }
        val nowPoint = sortedGlucose.last()
        val nowTs = nowPoint.ts
        val nowGlucose = nowPoint.valueMmol.coerceIn(MIN_GLUCOSE_MMOL, MAX_GLUCOSE_MMOL)

        val trend = estimateTrend(sortedGlucose)
        val factors = estimateSensitivityFactors(sortedGlucose, therapyEvents)
        val volatility = estimateVolatility(sortedGlucose)
        val intervalPenalty = estimateIntervalPenalty(sortedGlucose)
        val profiledCarbEvents = profileCarbEvents(therapyEvents, sortedGlucose, nowTs)

        return HORIZONS_MINUTES.map { horizon ->
            val trendDelta = trendDeltaAtHorizon(trend, horizon)
            val therapyDelta = therapyDeltaAtHorizon(
                events = therapyEvents,
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
        val profiledByKey = profiledCarbEvents.associateBy { eventKey(it.event) }

        events.asSequence()
            .filter { it.ts <= nowTs + horizonMinutes * MINUTE_MS }
            .filter { nowTs - it.ts <= EVENT_LOOKBACK_MS }
            .forEach { event ->
                val ageStart = ((nowTs - event.ts).coerceAtLeast(0L)) / 60_000.0
                val ageEnd = ageStart + horizon

                val carbs = extractCarbsGrams(event)
                if (carbs != null && carbs > 0.0) {
                    val profiled = profiledByKey[eventKey(event)]
                    val carbType = profiled?.type ?: CarbAbsorptionType.MEDIUM
                    val absorbed = (carbCumulative(carbType, ageEnd) - carbCumulative(carbType, ageStart)).coerceAtLeast(0.0)
                    delta += carbs * factors.carbSensitivityMmolPerGram * absorbed
                }

                val insulinUnits = extractInsulinUnits(event)
                if (insulinUnits != null && insulinUnits > 0.0 && eventCanCarryInsulin(event)) {
                    val active = (insulinCumulative(ageEnd) - insulinCumulative(ageStart)).coerceAtLeast(0.0)
                    delta -= insulinUnits * factors.isfMmolPerUnit * active
                }
            }

        return delta.coerceIn(-THERAPY_CUM_CLAMP_ABS, THERAPY_CUM_CLAMP_ABS)
    }

    private fun estimateSensitivityFactors(
        glucose: List<GlucosePoint>,
        events: List<TherapyEvent>
    ): SensitivityFactors {
        val sortedGlucose = glucose.sortedBy { it.ts }
        val sortedEvents = events.sortedBy { it.ts }

        val crSamples = sortedEvents.mapNotNull { event ->
            val grams = extractCarbsGrams(event) ?: return@mapNotNull null
            val units = extractInsulinUnits(event) ?: return@mapNotNull null
            if (grams <= 0.0 || units <= 0.0) return@mapNotNull null
            (grams / units).takeIf { it in 2.0..60.0 }
        }

        val correctionSamples = sortedEvents.mapNotNull { event ->
            if (!eventIsCorrection(event)) return@mapNotNull null
            val units = extractInsulinUnits(event) ?: return@mapNotNull null
            if (units <= 0.0) return@mapNotNull null
            val before = sortedGlucose.closestTo(event.ts - 10 * MINUTE_MS, maxDistanceMs = 25 * MINUTE_MS)
                ?: return@mapNotNull null
            val after = sortedGlucose.closestTo(event.ts + 90 * MINUTE_MS, maxDistanceMs = 45 * MINUTE_MS)
                ?: return@mapNotNull null
            val drop = before.valueMmol - after.valueMmol
            if (drop <= 0.1) return@mapNotNull null
            (drop / units).takeIf { it in 0.2..18.0 }
        }

        val isf = median(correctionSamples).coerceIn(0.8, 8.0).takeIf { correctionSamples.isNotEmpty() }
            ?: DEFAULT_ISF_MMOL_PER_UNIT
        val cr = median(crSamples).coerceIn(4.0, 30.0).takeIf { crSamples.isNotEmpty() }
            ?: DEFAULT_CR_GRAM_PER_UNIT
        val csf = (isf / cr).coerceIn(0.05, 0.40)
        return SensitivityFactors(isfMmolPerUnit = isf, carbSensitivityMmolPerGram = csf)
    }

    private fun eventIsCorrection(event: TherapyEvent): Boolean {
        val type = normalize(event.type)
        if (type == "correction_bolus") return true
        if (type != "bolus") return false
        val reason = event.payload["reason"]?.lowercase(Locale.US).orEmpty()
        val flag = event.payload["isCorrection"]?.lowercase(Locale.US).orEmpty()
        return reason.contains("correction") || flag == "true"
    }

    private fun eventCanCarryInsulin(event: TherapyEvent): Boolean {
        val type = normalize(event.type)
        return type.contains("bolus") || type.contains("correction") || type == "insulin"
    }

    private fun extractCarbsGrams(event: TherapyEvent): Double? {
        return payloadDouble(event, "grams", "carbs", "enteredCarbs", "mealCarbs")
            ?.takeIf { it in 0.5..400.0 }
    }

    private fun extractInsulinUnits(event: TherapyEvent): Double? {
        return payloadDouble(event, "units", "bolusUnits", "insulin", "enteredInsulin")
            ?.takeIf { it in 0.02..30.0 }
    }

    private fun payloadDouble(event: TherapyEvent, vararg keys: String): Double? {
        val normalizedPayload = event.payload.entries.associate { normalize(it.key) to it.value }
        return keys.firstNotNullOfOrNull { key ->
            normalizedPayload[normalize(key)]?.replace(",", ".")?.toDoubleOrNull()
        }
    }

    private fun carbCumulative(type: CarbAbsorptionType, ageMinutes: Double): Double {
        return CarbAbsorptionProfiles.cumulative(type, ageMinutes)
    }

    private fun profileCarbEvents(
        events: List<TherapyEvent>,
        glucose: List<GlucosePoint>,
        nowTs: Long
    ): List<ProfiledCarbEvent> {
        return events.asSequence()
            .mapNotNull { event ->
                val grams = extractCarbsGrams(event) ?: return@mapNotNull null
                if (grams <= 0.0) return@mapNotNull null
                val classified = CarbAbsorptionProfiles.classifyCarbEvent(event, glucose, nowTs)
                ProfiledCarbEvent(
                    event = event,
                    grams = grams,
                    type = classified.type,
                    reason = classified.reason
                )
            }
            .toList()
    }

    private fun eventKey(event: TherapyEvent): String {
        val payloadHash = event.payload.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
            .hashCode()
        return "${event.ts}|${event.type}|$payloadHash"
    }

    private fun insulinCumulative(ageMinutes: Double): Double {
        return insulinProfile.cumulativeAt(ageMinutes)
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
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    internal data class V3Diagnostics(
        val gNowRaw: Double,
        val gNowUsed: Double,
        val rocPer5Used: Double,
        val kfSigmaG: Double,
        val kfEwmaNis: Double,
        val kfSigmaZ: Double,
        val kfSigmaA: Double,
        val kfWarmedUp: Boolean,
        val insulinProfileId: String,
        val residualRoc0: Double,
        val uci0: Double,
        val uciMax: Double,
        val k: Double,
        val uamActive: Boolean,
        val virtualMealCarbs: Double?,
        val virtualMealConfidence: Double?,
        val usingVirtualMeal: Boolean,
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
        val trendStep: List<Double>,
        val glucosePath: List<Double>,
        val trendCum60Raw: Double,
        val trendCum60Clamped: Double,
        val predByHorizon: Map<Int, Double>
    )

    private data class TrendEstimate(
        val shortSlopePer5m: Double,
        val longSlopePer5m: Double,
        val accelerationPer5m: Double
    )

    private data class SensitivityFactors(
        val isfMmolPerUnit: Double,
        val carbSensitivityMmolPerGram: Double
    )

    private data class TherapyStepSeries(
        val steps: DoubleArray,
        val cumClamped: DoubleArray,
        val carbFastActiveGrams: Double,
        val carbMediumActiveGrams: Double,
        val carbProteinSlowActiveGrams: Double,
        val residualCarbsNowGrams: Double,
        val residualCarbs30mGrams: Double,
        val residualCarbs60mGrams: Double,
        val residualCarbs120mGrams: Double
    )

    private data class ProfiledCarbEvent(
        val event: TherapyEvent,
        val grams: Double,
        val type: CarbAbsorptionType,
        val reason: String
    )

    private companion object {
        const val LEGACY_MODEL_VERSION = "local-hybrid-v2"
        const val ENHANCED_MODEL_VERSION = "local-hybrid-v3"
        const val MIN_GLUCOSE_MMOL = 2.2
        const val MAX_GLUCOSE_MMOL = 22.0
        const val DEFAULT_ISF_MMOL_PER_UNIT = 2.3
        const val DEFAULT_CR_GRAM_PER_UNIT = 10.0

        const val MINUTE_MS = 60_000L
        const val FIVE_MINUTES_MS = 5 * MINUTE_MS
        const val EVENT_LOOKBACK_MS = 8 * 60 * MINUTE_MS

        const val STEPS_MAX = 12
        const val THERAPY_CUM_CLAMP_ABS = 6.0
        val HORIZONS_MINUTES = listOf(5L, 30L, 60L)

        const val UAM_ACTIVE_THRESHOLD = 0.10
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
