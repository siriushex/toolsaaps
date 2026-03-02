package io.aaps.copilot.domain.predict

import io.aaps.copilot.domain.model.GlucosePoint
import io.aaps.copilot.domain.model.TherapyEvent
import kotlin.math.abs
import kotlin.math.ln

class UamInferenceEngine {

    data class Input(
        val nowTs: Long,
        val glucose: List<GlucosePoint>,
        val therapyEvents: List<TherapyEvent>,
        val existingEvents: List<UamInferenceEvent>,
        val isfMmolPerUnit: Double?,
        val crGramPerUnit: Double?,
        val insulinProfileId: String,
        val enableUamInference: Boolean,
        val enableUamBoost: Boolean,
        val learnedMultiplier: Double,
        val userSettings: UamUserSettings
    )

    data class Output(
        val events: List<UamInferenceEvent>,
        val activeEvent: UamInferenceEvent?,
        val inferredCarbsGrams: Double?,
        val ingestionTs: Long?,
        val confidence: Double?,
        val mode: UamMode,
        val gAbsRecent: List<Double>,
        val manualCobNow: Double,
        val createdNewEvent: Boolean,
        val learnedMultiplierUpdate: Double?
    )

    private data class Interval(
        val startTs: Long,
        val endTs: Long,
        val residualPos: Double,
        val gAbs: Double,
        val weight: Double
    )

    private data class FitCandidate(
        val ingestionTs: Long,
        val carbsGrams: Double,
        val confidence: Double,
        val sse: Double
    )

    fun infer(input: Input): Output {
        val mode = if (input.enableUamBoost) UamMode.BOOST else UamMode.NORMAL
        val profile = InsulinActionProfiles.profile(InsulinActionProfileId.fromRaw(input.insulinProfileId))
        val isfMmolPerUnit = input.isfMmolPerUnit?.coerceIn(0.8, 8.0) ?: DEFAULT_ISF
        val csf = resolveCsf(input.isfMmolPerUnit, input.crGramPerUnit)
        val multiplier = resolveMultiplier(input.learnedMultiplier, mode)
        val csfUam = (csf / multiplier).coerceIn(0.02, 0.40)
        val maxAbsPer5 = resolveMaxAbsPer5(input.userSettings, mode)
        val threshold = if (mode == UamMode.BOOST) input.userSettings.gAbsThreshold_Boost else input.userSettings.gAbsThreshold_Normal
        val mOfN = if (mode == UamMode.BOOST) input.userSettings.mOfN_Boost else input.userSettings.mOfN_Normal
        val confirmConf = if (mode == UamMode.BOOST) input.userSettings.confirmConf_Boost else input.userSettings.confirmConf_Normal

        if (!input.enableUamInference) {
            return Output(
                events = input.existingEvents,
                activeEvent = input.existingEvents.firstOrNull(),
                inferredCarbsGrams = null,
                ingestionTs = null,
                confidence = null,
                mode = mode,
                gAbsRecent = emptyList(),
                manualCobNow = 0.0,
                createdNewEvent = false,
                learnedMultiplierUpdate = null
            )
        }

        val smoothed = smoothGlucose(input.glucose)
        val grid = buildGrid(smoothed = smoothed, nowTs = input.nowTs, historyMinutes = 120)
        if (grid.size < 6) {
            return Output(
                events = input.existingEvents,
                activeEvent = input.existingEvents.firstOrNull(),
                inferredCarbsGrams = input.existingEvents.maxByOrNull { it.updatedAt }?.carbsDisplayG,
                ingestionTs = input.existingEvents.maxByOrNull { it.updatedAt }?.ingestionTs,
                confidence = input.existingEvents.maxByOrNull { it.updatedAt }?.confidence,
                mode = mode,
                gAbsRecent = emptyList(),
                manualCobNow = 0.0,
                createdNewEvent = false,
                learnedMultiplierUpdate = null
            )
        }

        val activeEvents = input.existingEvents
            .filter { it.state == UamInferenceState.SUSPECTED || it.state == UamInferenceState.CONFIRMED }
            .sortedBy { it.createdAt }
            .toMutableList()

        val taggedCarbEvents = input.therapyEvents
            .mapNotNull { event ->
                extractCarbs(event)?.takeIf { it > 0.0 }?.let { carbs ->
                    CarbEvent(
                        event = event,
                        carbs = carbs,
                        tag = parseUamTag(extractNote(event))
                    )
                }
            }
        val manualCarbEvents = taggedCarbEvents.filter { it.tag == null }
        val manualCobNow = manualCarbEvents.sumOf { event ->
            val ageNow = ((input.nowTs - event.event.ts).coerceAtLeast(0L)) / 60_000.0
            val carbType = CarbAbsorptionProfiles.classifyCarbEvent(event.event, input.glucose, input.nowTs).type
            val remaining = (1.0 - CarbAbsorptionProfiles.cumulative(carbType, ageNow)).coerceIn(0.0, 1.0)
            event.carbs * remaining
        }

        val intervals = buildIntervals(
            grid = grid,
            therapyEvents = input.therapyEvents,
            activeEvents = activeEvents,
            csf = csf,
            isfMmolPerUnit = isfMmolPerUnit,
            csfUam = csfUam,
            insulinCumulative = profile::cumulativeAt,
            maxAbsPer5 = maxAbsPer5
        )
        val gAbsRecent = intervals.takeLast(6).map { it.gAbs }
        val detectionSatisfied = mOfN(gAbsRecent, threshold, mOfN.first, mOfN.second)

        var created = false
        val mayCreateNew = !(input.userSettings.disableUamWhenManualCobActive &&
            manualCobNow > input.userSettings.manualCobThresholdG)

        if (detectionSatisfied &&
            mayCreateNew &&
            activeEvents.size < input.userSettings.maxActiveUamEvents
        ) {
            val ingestionCandidate = input.nowTs - input.userSettings.backdateMinutesDefault * 60_000L
            val hasManualNearby = input.userSettings.disableUamIfManualCarbsNearby &&
                manualCarbEvents.any { abs(it.event.ts - ingestionCandidate) <= input.userSettings.manualMergeWindowMinutes * 60_000L }
            val hasTaggedNearby = taggedCarbEvents.any {
                it.tag != null && abs(it.event.ts - ingestionCandidate) <= input.userSettings.manualMergeWindowMinutes * 60_000L
            }
            if (!hasManualNearby && !hasTaggedNearby) {
                val startEstimate = quantizeSnack(
                    value = gAbsRecent.sum().coerceAtLeast(input.userSettings.minSnackG.toDouble()),
                    settings = input.userSettings
                )
                activeEvents += UamInferenceEvent(
                    state = UamInferenceState.SUSPECTED,
                    mode = mode,
                    createdAt = input.nowTs,
                    updatedAt = input.nowTs,
                    ingestionTs = ingestionCandidate,
                    carbsModelG = startEstimate.coerceIn(0.0, input.userSettings.maxUamTotalG),
                    carbsDisplayG = startEstimate,
                    confidence = 0.0
                )
                created = true
            }
        }

        val updated = activeEvents.map { event ->
            val fit = fitDiscrete(
                intervals = intervals,
                nowTs = input.nowTs,
                csfUam = csfUam,
                settings = input.userSettings
            ) ?: return@map event
            val ageMinutes = ((input.nowTs - event.createdAt).coerceAtLeast(0L) / 60_000L).toInt()
            val allowDecrease = event.state == UamInferenceState.SUSPECTED && fit.confidence < 0.20
            val nextDisplay = if (allowDecrease) {
                fit.carbsGrams
            } else {
                maxOf(event.carbsDisplayG, fit.carbsGrams)
            }
            val nextState = when {
                event.state == UamInferenceState.CONFIRMED -> UamInferenceState.CONFIRMED
                fit.confidence >= confirmConf && ageMinutes >= input.userSettings.minConfirmAgeMin -> UamInferenceState.CONFIRMED
                else -> UamInferenceState.SUSPECTED
            }
            val weakTail = intervals.takeLast(4).all { it.gAbs < threshold * 0.25 }
            val finalState = if (nextState == UamInferenceState.CONFIRMED && weakTail && ageMinutes >= 90) {
                UamInferenceState.FINAL
            } else {
                nextState
            }
            event.copy(
                state = finalState,
                updatedAt = input.nowTs,
                ingestionTs = fit.ingestionTs.coerceAtLeast(input.nowTs - input.userSettings.exportMaxBackdateMin * 60_000L),
                carbsModelG = fit.carbsGrams.coerceIn(0.0, input.userSettings.maxUamTotalG),
                carbsDisplayG = quantizeSnack(nextDisplay, input.userSettings),
                confidence = fit.confidence,
                learnedEligible = finalState == UamInferenceState.FINAL && fit.confidence >= 0.60
            )
        }

        val merged = (input.existingEvents.filterNot { old -> updated.any { it.id == old.id } } + updated)
            .sortedBy { it.createdAt }
        val active = merged
            .filter { it.state == UamInferenceState.SUSPECTED || it.state == UamInferenceState.CONFIRMED }
            .maxByOrNull { it.updatedAt }

        val learnedUpdate = buildLearnedMultiplierUpdate(
            nowTs = input.nowTs,
            learnedMultiplier = input.learnedMultiplier,
            events = merged,
            intervals = intervals,
            manualCarbEvents = manualCarbEvents,
            settings = input.userSettings
        )

        return Output(
            events = merged,
            activeEvent = active,
            inferredCarbsGrams = active?.carbsDisplayG,
            ingestionTs = active?.ingestionTs,
            confidence = active?.confidence,
            mode = mode,
            gAbsRecent = gAbsRecent,
            manualCobNow = manualCobNow,
            createdNewEvent = created,
            learnedMultiplierUpdate = learnedUpdate
        )
    }

    private data class SmoothedPoint(val ts: Long, val g: Double)
    private data class CarbEvent(val event: TherapyEvent, val carbs: Double, val tag: UamTag?)

    private fun smoothGlucose(glucose: List<GlucosePoint>): List<SmoothedPoint> {
        val filter = KalmanGlucoseFilter()
        return glucose
            .sortedBy { it.ts }
            .distinctBy { it.ts }
            .map { point ->
                val snapshot = filter.update(point.valueMmol, point.ts)
                SmoothedPoint(ts = point.ts, g = snapshot.gMmol)
            }
    }

    private fun buildGrid(smoothed: List<SmoothedPoint>, nowTs: Long, historyMinutes: Int): List<SmoothedPoint> {
        val out = mutableListOf<SmoothedPoint>()
        if (smoothed.isEmpty()) return out
        val nowBucket = (nowTs / FIVE_MIN_MS) * FIVE_MIN_MS
        val start = nowBucket - historyMinutes * 60_000L
        var ts = start
        while (ts <= nowBucket) {
            val value = interpolate(smoothed, ts) ?: run {
                ts += FIVE_MIN_MS
                continue
            }
            out += SmoothedPoint(ts = ts, g = value)
            ts += FIVE_MIN_MS
        }
        return out
    }

    private fun interpolate(points: List<SmoothedPoint>, targetTs: Long): Double? {
        val exact = points.firstOrNull { it.ts == targetTs }?.g
        if (exact != null) return exact
        val left = points.lastOrNull { it.ts < targetTs }
        val right = points.firstOrNull { it.ts > targetTs }
        if (left == null && right == null) return null
        if (left == null) {
            if (abs((right!!.ts - targetTs).toDouble()) > 10 * 60_000.0) return null
            return right.g
        }
        if (right == null) {
            if (abs((targetTs - left.ts).toDouble()) > 10 * 60_000.0) return null
            return left.g
        }
        val span = (right.ts - left.ts).toDouble().coerceAtLeast(1.0)
        val alpha = ((targetTs - left.ts) / span).coerceIn(0.0, 1.0)
        return (left.g + (right.g - left.g) * alpha).coerceIn(2.2, 22.0)
    }

    private fun buildIntervals(
        grid: List<SmoothedPoint>,
        therapyEvents: List<TherapyEvent>,
        activeEvents: List<UamInferenceEvent>,
        csf: Double,
        isfMmolPerUnit: Double,
        csfUam: Double,
        insulinCumulative: (Double) -> Double,
        maxAbsPer5: Double
    ): List<Interval> {
        if (grid.size < 2) return emptyList()
        val intervals = mutableListOf<Interval>()
        grid.zipWithNext().forEach { (a, b) ->
            val dObs = b.g - a.g
            val dTherapy = therapyDelta(
                therapyEvents = therapyEvents,
                startTs = a.ts,
                endTs = b.ts,
                csf = csf,
                isfMmolPerUnit = isfMmolPerUnit,
                insulinCumulative = insulinCumulative
            )
            val dVirtual = virtualUamDelta(
                events = activeEvents,
                startTs = a.ts,
                endTs = b.ts,
                csfUam = csfUam
            )
            val residualPos = maxOf(0.0, dObs - dTherapy - dVirtual)
            val gAbs = (residualPos / csfUam).coerceIn(0.0, maxAbsPer5)
            val ageMid = ((grid.last().ts - b.ts).coerceAtLeast(0L)) / 60_000.0
            val weight = kotlin.math.exp(-ln(2.0) * ageMid / 30.0)
            intervals += Interval(
                startTs = a.ts,
                endTs = b.ts,
                residualPos = residualPos,
                gAbs = gAbs,
                weight = weight
            )
        }
        return intervals
    }

    private fun therapyDelta(
        therapyEvents: List<TherapyEvent>,
        startTs: Long,
        endTs: Long,
        csf: Double,
        isfMmolPerUnit: Double,
        insulinCumulative: (Double) -> Double
    ): Double {
        var out = 0.0
        therapyEvents.forEach { event ->
            if (event.ts > endTs || endTs - event.ts > EVENT_LOOKBACK_MS) return@forEach
            val age0 = maxOf(0.0, (startTs - event.ts) / 60_000.0)
            val age1 = maxOf(0.0, (endTs - event.ts) / 60_000.0)
            val carbs = extractCarbs(event)
            if (carbs != null && carbs > 0.0) {
                val type = CarbAbsorptionProfiles.classifyCarbEvent(event, emptyList(), endTs).type
                out += carbs * csf * maxOf(0.0, CarbAbsorptionProfiles.cumulative(type, age1) - CarbAbsorptionProfiles.cumulative(type, age0))
            }
            val insulin = extractInsulin(event)
            if (insulin != null && insulin > 0.0 && eventCanCarryInsulin(event)) {
                out -= insulin * isfMmolPerUnit * maxOf(0.0, insulinCumulative(age1) - insulinCumulative(age0))
            }
        }
        return out
    }

    private fun virtualUamDelta(
        events: List<UamInferenceEvent>,
        startTs: Long,
        endTs: Long,
        csfUam: Double
    ): Double {
        var out = 0.0
        events.forEach { event ->
            if (event.state == UamInferenceState.FINAL || event.state == UamInferenceState.MERGED) return@forEach
            val virtualGrams = (event.carbsDisplayG - event.exportedGrams).coerceAtLeast(0.0)
            if (virtualGrams <= 0.0) return@forEach
            val age0 = maxOf(0.0, (startTs - event.ingestionTs) / 60_000.0)
            val age1 = maxOf(0.0, (endTs - event.ingestionTs) / 60_000.0)
            out += virtualGrams * csfUam * maxOf(
                0.0,
                CarbAbsorptionProfiles.cumulative(CarbAbsorptionType.MEDIUM, age1) -
                    CarbAbsorptionProfiles.cumulative(CarbAbsorptionType.MEDIUM, age0)
            )
        }
        return out
    }

    private fun fitDiscrete(
        intervals: List<Interval>,
        nowTs: Long,
        csfUam: Double,
        settings: UamUserSettings
    ): FitCandidate? {
        if (intervals.size < 4) return null
        val window = intervals.takeLast(18)
        val carbCandidates = buildSnackCandidates(settings)
        var best: FitCandidate? = null
        val energy = window.sumOf { it.weight * it.residualPos * it.residualPos }
        if (energy <= 1e-6) return null

        val nowBucket = (nowTs / FIVE_MIN_MS) * FIVE_MIN_MS
        val startBucket = nowBucket - 12 * FIVE_MIN_MS
        var tStar = startBucket
        while (tStar <= nowBucket - FIVE_MIN_MS) {
            carbCandidates.forEach { carbs ->
                var sse = 0.0
                window.forEach { interval ->
                    val age0 = maxOf(0.0, (interval.startTs - tStar) / 60_000.0)
                    val age1 = maxOf(0.0, (interval.endTs - tStar) / 60_000.0)
                    val rHat = carbs * csfUam * maxOf(
                        0.0,
                        CarbAbsorptionProfiles.cumulative(CarbAbsorptionType.MEDIUM, age1) -
                            CarbAbsorptionProfiles.cumulative(CarbAbsorptionType.MEDIUM, age0)
                    )
                    val err = interval.residualPos - rHat
                    sse += interval.weight * err * err
                }
                if (best == null || sse < best!!.sse) {
                    val conf = (1.0 - sse / (energy + 1e-6)).coerceIn(0.0, 1.0)
                    best = FitCandidate(
                        ingestionTs = tStar,
                        carbsGrams = carbs,
                        confidence = conf,
                        sse = sse
                    )
                }
            }
            tStar += FIVE_MIN_MS
        }
        return best
    }

    private fun mOfN(values: List<Double>, threshold: Double, m: Int, n: Int): Boolean {
        if (values.isEmpty()) return false
        val tail = values.takeLast(n.coerceAtLeast(1))
        return tail.count { it >= threshold } >= m.coerceAtLeast(1)
    }

    private fun quantizeSnack(value: Double, settings: UamUserSettings): Double {
        val minG = settings.minSnackG.toDouble()
        val maxG = settings.maxSnackG.toDouble()
        val step = settings.snackStepG.coerceAtLeast(1).toDouble()
        val clamped = value.coerceIn(minG, maxG)
        val scaled = (clamped / step)
        return (kotlin.math.floor(scaled + 0.5) * step).coerceIn(minG, maxG)
    }

    private fun buildSnackCandidates(settings: UamUserSettings): List<Double> {
        val out = mutableListOf<Double>()
        val min = settings.minSnackG.coerceAtLeast(1)
        val max = settings.maxSnackG.coerceAtLeast(min)
        val step = settings.snackStepG.coerceAtLeast(1)
        var value = min
        while (value <= max) {
            out += value.toDouble()
            value += step
        }
        if (out.isEmpty()) out += min.toDouble()
        return out
    }

    private fun extractCarbs(event: TherapyEvent): Double? {
        val keys = listOf("grams", "carbs", "enteredCarbs", "mealCarbs")
        return keys.firstNotNullOfOrNull { key ->
            event.payload[key]?.replace(",", ".")?.toDoubleOrNull()
        }?.takeIf { it in 0.5..400.0 }
    }

    private fun extractInsulin(event: TherapyEvent): Double? {
        val keys = listOf("units", "bolusUnits", "insulin", "enteredInsulin")
        return keys.firstNotNullOfOrNull { key ->
            event.payload[key]?.replace(",", ".")?.toDoubleOrNull()
        }?.takeIf { it in 0.02..30.0 }
    }

    private fun eventCanCarryInsulin(event: TherapyEvent): Boolean {
        val type = normalize(event.type)
        return type.contains("bolus") || type.contains("correction") || type == "insulin"
    }

    private fun extractNote(event: TherapyEvent): String? {
        return event.payload["note"] ?: event.payload["notes"] ?: event.payload["reason"]
    }

    private fun normalize(value: String): String {
        return value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun resolveCsf(isfMmolPerUnit: Double?, crGramPerUnit: Double?): Double {
        if (isfMmolPerUnit != null && crGramPerUnit != null && isfMmolPerUnit > 0.0 && crGramPerUnit > 0.0) {
            return (isfMmolPerUnit / crGramPerUnit).coerceIn(0.05, 0.40)
        }
        return 0.18
    }

    private fun resolveMultiplier(learned: Double, mode: UamMode): Double {
        return if (mode == UamMode.BOOST) {
            (2.0 * learned.coerceIn(0.8, 1.6)).coerceIn(1.5, 3.0)
        } else {
            learned.coerceIn(0.8, 1.6)
        }
    }

    private fun resolveMaxAbsPer5(settings: UamUserSettings, mode: UamMode): Double {
        val gph = if (mode == UamMode.BOOST) settings.maxUamAbsorbRateGph_Boost else settings.maxUamAbsorbRateGph_Normal
        return gph.coerceAtLeast(1.0) * (5.0 / 60.0)
    }

    private fun buildLearnedMultiplierUpdate(
        nowTs: Long,
        learnedMultiplier: Double,
        events: List<UamInferenceEvent>,
        intervals: List<Interval>,
        manualCarbEvents: List<CarbEvent>,
        settings: UamUserSettings
    ): Double? {
        val lastFinal = events
            .filter { it.state == UamInferenceState.FINAL && it.learnedEligible }
            .maxByOrNull { it.updatedAt }
            ?: return null
        val hasManualNearby = manualCarbEvents.any {
            abs(it.event.ts - lastFinal.ingestionTs) <= settings.manualMergeWindowMinutes * 60_000L
        }
        if (hasManualNearby) return null
        if (nowTs - lastFinal.updatedAt > 45 * 60_000L) return null
        val resRemain = intervals.takeLast(6).sumOf { it.residualPos }
        val delta = (resRemain / 1.0).coerceIn(-0.05, 0.05)
        val next = (learnedMultiplier * (1.0 + delta * 0.02)).coerceIn(0.8, 1.6)
        return if (abs(next - learnedMultiplier) < 1e-6) null else next
    }

    private companion object {
        const val FIVE_MIN_MS = 5 * 60_000L
        const val EVENT_LOOKBACK_MS = 8 * 60 * 60_000L
        const val DEFAULT_ISF = 2.3
    }
}
