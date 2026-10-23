package io.aaps.copilot.service

import io.aaps.copilot.data.repository.LocalNightscoutV3Store
import java.util.Collections

enum class AapsV3ReportedBasalStatus {
    UNCHECKED, OBSERVED_WINDOW, OBSERVED_WITH_GAPS, NOT_OBSERVED, PROFILE_UNAVAILABLE,
    INPUT_UNAVAILABLE, REVISION_MISMATCH, INVALID, LATE_EVIDENCE, CONFLICTING, UNSUPPORTED, LIMIT_REACHED
}

internal sealed class AapsV3BasalRate {
    data class Absolute(val unitsPerHour: Double) : AapsV3BasalRate()
    data class PercentAdjustment(val percent: Double) : AapsV3BasalRate()
    data class ExtendedAddition(val unitsPerHour: Double) : AapsV3BasalRate()
    final override fun toString() = "AapsV3BasalRate(redacted)"
}

internal data class AapsV3ReportedBasalSegment(
    val startMs: Long, val endMs: Long, val rate: AapsV3BasalRate,
    val source: AapsV3InsulinObservation, val profile: AapsV3EffectiveProfileObservation
) {
    /** A reported component, never independently confirmed delivery or total emulated basal. */
    val reportedComponentUnits: Double? get() = when (rate) {
        is AapsV3BasalRate.Absolute -> rate.unitsPerHour * ((endMs - startMs) / 3_600_000.0)
        is AapsV3BasalRate.ExtendedAddition -> rate.unitsPerHour * ((endMs - startMs) / 3_600_000.0)
        is AapsV3BasalRate.PercentAdjustment -> null
    }
    override fun toString() = "AapsV3ReportedBasalSegment(redacted)"
}

internal data class AapsV3BasalGap(val startMs: Long, val endMs: Long) {
    override fun toString() = "AapsV3BasalGap(redacted)"
}

internal class AapsV3ReportedBasalRead(
    val status: AapsV3ReportedBasalStatus,
    val retainedRevision: Long?,
    segments: List<AapsV3ReportedBasalSegment> = emptyList(),
    gaps: List<AapsV3BasalGap> = emptyList()
) {
    val segments: List<AapsV3ReportedBasalSegment> = Collections.unmodifiableList(segments.toList())
    val gaps: List<AapsV3BasalGap> = Collections.unmodifiableList(gaps.toList())
    val upstreamCompletenessVerified: Boolean get() = false
    override fun toString() = "AapsV3ReportedBasalRead(status=$status, redacted)"
}

/** Reported intervals only. Missing records are not normal basal and profile timezone is not a clock receipt. */
internal object AapsV3ReportedBasalProjector {
    private data class Span(val startMs: Long, val endMs: Long, val rate: AapsV3BasalRate, val source: AapsV3InsulinObservation)

    fun read(fromMs: Long, untilMs: Long, knownAtMs: Long, profiles: AapsV3ProfileHistoryRead,
        insulin: AapsV3InsulinRead): AapsV3ReportedBasalRead {
        val revision = profiles.retainedRevision
        fun unavailable(status: AapsV3ReportedBasalStatus) = AapsV3ReportedBasalRead(status, revision)
        if (fromMs !in 1 until untilMs || untilMs > knownAtMs || knownAtMs > LocalNightscoutV3Store.MAX_TIME)
            return unavailable(AapsV3ReportedBasalStatus.INVALID)
        if (untilMs - fromMs > 172_800_000L) return unavailable(AapsV3ReportedBasalStatus.LIMIT_REACHED)
        if (profiles.status != AapsV3ObservedProfileStatus.OBSERVED)
            return unavailable(AapsV3ReportedBasalStatus.PROFILE_UNAVAILABLE)
        if (insulin.status !in setOf(AapsV3ObservedInsulinStatus.OBSERVED,
                AapsV3ObservedInsulinStatus.PARTIAL_CONTEXT, AapsV3ObservedInsulinStatus.NOT_OBSERVED))
            return unavailable(AapsV3ReportedBasalStatus.INPUT_UNAVAILABLE)
        if (revision == null || revision != insulin.retainedRevision)
            return unavailable(AapsV3ReportedBasalStatus.REVISION_MISMATCH)
        if (revision > knownAtMs || insulin.records.any { it.availableAtMs > knownAtMs })
            return unavailable(AapsV3ReportedBasalStatus.LATE_EVIDENCE)
        if (profiles.intervals.size > 2880 || insulin.records.size > 10_000)
            return unavailable(AapsV3ReportedBasalStatus.LIMIT_REACHED)
        val history = profiles.intervals
        if (history.isEmpty() || history.first().startMs != fromMs || history.last().endMs != untilMs ||
            history.zipWithNext().any { (a, b) -> a.endMs != b.startMs } || history.any {
                it.startMs >= it.endMs || it.observation.eventAtMs > it.startMs ||
                    it.observation.createdRevision !in 1..it.observation.modifiedRevision ||
                    it.observation.modifiedRevision > revision
            } || insulin.records.any { it.identifier.isBlank() || it.createdRevision !in 1..it.modifiedRevision ||
                it.modifiedRevision > revision || it.eventAtMs !in 1..LocalNightscoutV3Store.MAX_TIME })
            return unavailable(AapsV3ReportedBasalStatus.INVALID)
        if (insulin.records.map { it.identifier }.distinct().size != insulin.records.size)
            return unavailable(AapsV3ReportedBasalStatus.CONFLICTING)
        val pumpKeys = insulin.records.mapNotNull { it.pumpRecordIdentity }
        if (pumpKeys.distinct().size != pumpKeys.size) return unavailable(AapsV3ReportedBasalStatus.CONFLICTING)
        val spans = mutableListOf<Span>()
        for (record in insulin.records) {
            if (record.invalidated) continue
            val duration: Long
            val rate: AapsV3BasalRate
            when (val dose = record.dose) {
                is AapsV3InsulinDose.Bolus -> {
                    if (dose.basalInsulin) return unavailable(AapsV3ReportedBasalStatus.UNSUPPORTED)
                    continue
                }
                is AapsV3InsulinDose.TempBasal -> {
                    if (dose.type == AapsV3TempBasalType.FAKE_EXTENDED) return unavailable(AapsV3ReportedBasalStatus.UNSUPPORTED)
                    duration = dose.durationMs
                    val absolute = dose.absoluteUnitsPerHour
                    val percent = dose.percentAdjustment
                    if ((absolute != null) == (percent != null) || absolute?.let { !it.isFinite() || it < 0 } == true ||
                        percent?.let { !it.isFinite() || it < -100 } == true)
                        return unavailable(AapsV3ReportedBasalStatus.INVALID)
                    rate = absolute?.let(AapsV3BasalRate::Absolute) ?: AapsV3BasalRate.PercentAdjustment(checkNotNull(percent))
                }
                is AapsV3InsulinDose.Extended -> {
                    if (!dose.emulatingTempBasal) continue
                    if (dose.durationMs == 0L && dose.units != 0.0) return unavailable(AapsV3ReportedBasalStatus.UNSUPPORTED)
                    if (!dose.units.isFinite() || dose.units < 0) return unavailable(AapsV3ReportedBasalStatus.INVALID)
                    duration = dose.durationMs
                    if (duration == 0L) continue
                    val extra = dose.units / (duration / 3_600_000.0)
                    if (!extra.isFinite()) return unavailable(AapsV3ReportedBasalStatus.INVALID)
                    rate = AapsV3BasalRate.ExtendedAddition(extra)
                }
            }
            if (duration < 0 || record.eventAtMs > LocalNightscoutV3Store.MAX_TIME - duration)
                return unavailable(AapsV3ReportedBasalStatus.INVALID)
            val start = maxOf(fromMs, record.eventAtMs)
            val end = minOf(untilMs, record.eventAtMs + duration)
            if (start < end) spans += Span(start, end, rate, record)
        }
        spans.sortBy { it.startMs }
        if (spans.zipWithNext().any { (a, b) -> a.endMs > b.startMs })
            return unavailable(AapsV3ReportedBasalStatus.CONFLICTING)
        val segments = mutableListOf<AapsV3ReportedBasalSegment>()
        var profileIndex = 0
        for (span in spans) {
            var start = span.startMs
            while (start < span.endMs) {
                while (history[profileIndex].endMs <= start) profileIndex++
                val profile = history[profileIndex]
                val end = minOf(profile.endMs, span.endMs)
                val segment = AapsV3ReportedBasalSegment(start, end, span.rate, span.source, profile.observation)
                if (segment.reportedComponentUnits?.isFinite() == false)
                    return unavailable(AapsV3ReportedBasalStatus.INVALID)
                segments += segment
                if (segments.size > 2880) return unavailable(AapsV3ReportedBasalStatus.LIMIT_REACHED)
                start = end
            }
        }
        val gaps = mutableListOf<AapsV3BasalGap>()
        var end = fromMs
        for (segment in segments) {
            if (end < segment.startMs) gaps += AapsV3BasalGap(end, segment.startMs)
            end = segment.endMs
        }
        if (end < untilMs) gaps += AapsV3BasalGap(end, untilMs)
        val status = when {
            segments.isEmpty() -> AapsV3ReportedBasalStatus.NOT_OBSERVED
            gaps.isEmpty() -> AapsV3ReportedBasalStatus.OBSERVED_WINDOW
            else -> AapsV3ReportedBasalStatus.OBSERVED_WITH_GAPS
        }
        return AapsV3ReportedBasalRead(status, revision, segments, gaps)
    }
}
