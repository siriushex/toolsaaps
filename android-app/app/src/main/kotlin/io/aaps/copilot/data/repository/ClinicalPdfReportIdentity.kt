package io.aaps.copilot.data.repository

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ClinicalPdfReportPhase {
    IDLE,
    BUILDING,
    LOCAL_READY,
    UPLOADING,
    COMPLETE,
    FAILED,
    CANCELLED
}

class ClinicalPdfReportIdentity internal constructor(
    val phase: ClinicalPdfReportPhase,
    val digest: String
) {
    init {
        require(digest.length == SHA_256_HEX_LENGTH && digest.all(::isLowerHex))
    }

    val isExportable: Boolean
        get() = phase == ClinicalPdfReportPhase.LOCAL_READY ||
            phase == ClinicalPdfReportPhase.COMPLETE

    companion object {
        fun from(state: ClinicalReportState): ClinicalPdfReportIdentity {
            val phase = when (state) {
                ClinicalReportState.Idle -> ClinicalPdfReportPhase.IDLE
                is ClinicalReportState.Building -> ClinicalPdfReportPhase.BUILDING
                is ClinicalReportState.LocalReady -> ClinicalPdfReportPhase.LOCAL_READY
                is ClinicalReportState.Uploading -> ClinicalPdfReportPhase.UPLOADING
                is ClinicalReportState.Complete -> ClinicalPdfReportPhase.COMPLETE
                is ClinicalReportState.Failed -> ClinicalPdfReportPhase.FAILED
                is ClinicalReportState.Cancelled -> ClinicalPdfReportPhase.CANCELLED
            }
            return ClinicalPdfReportIdentity(phase, digestState(state, phase))
        }

        private fun digestState(
            state: ClinicalReportState,
            phase: ClinicalPdfReportPhase
        ): String = IdentityDigest().apply {
            field("phase", phase.name)
            when (state) {
                ClinicalReportState.Idle -> Unit
                is ClinicalReportState.Building -> field("requestId", state.requestId)
                is ClinicalReportState.LocalReady -> {
                    field("requestId", state.requestId)
                    local(state.local)
                }
                is ClinicalReportState.Uploading -> {
                    field("requestId", state.requestId)
                    local(state.local)
                    field("completed", state.completed)
                    field("total", state.total)
                    field("stage", state.stage.name)
                    field("level", state.level)
                }
                is ClinicalReportState.Complete -> {
                    field("requestId", state.requestId)
                    local(state.local)
                    report(state.report)
                    metadata(state.metadata)
                }
                is ClinicalReportState.Failed -> {
                    field("requestId", state.requestId)
                    nullableLocal(state.local)
                    field("reason", state.reason.name)
                }
                is ClinicalReportState.Cancelled -> {
                    field("requestId", state.requestId)
                    nullableLocal(state.local)
                }
            }
        }.finish()

        private const val SHA_256_HEX_LENGTH = 64

        private fun isLowerHex(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f'
    }

    override fun equals(other: Any?): Boolean =
        other is ClinicalPdfReportIdentity && phase == other.phase && digest == other.digest

    override fun hashCode(): Int = 31 * phase.hashCode() + digest.hashCode()

    override fun toString(): String = "ClinicalPdfReportIdentity(phase=$phase)"
}

private class IdentityDigest {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun field(name: String, value: String?) {
        bytes(name.toByteArray(StandardCharsets.UTF_8))
        if (value == null) {
            bytes(byteArrayOf(0))
        } else {
            bytes(byteArrayOf(1))
            bytes(value.toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun field(name: String, value: Int) = field(name, value.toString())

    fun field(name: String, value: Long) = field(name, value.toString())

    fun field(name: String, value: Boolean) = field(name, if (value) "1" else "0")

    fun field(name: String, value: Double) = field(name, java.lang.Double.toHexString(value))

    fun local(local: ClinicalLocalReport) {
        field("local.requestHash", local.requestHash)
        field("local.generatedAt", local.generatedAt)
        field("local.zoneId", local.zoneId)
    }

    fun nullableLocal(local: ClinicalLocalReport?) {
        field("local.present", local != null)
        if (local != null) local(local)
    }

    fun report(report: ClinicalAdvisoryReport) {
        field("report.summary7dStatus", report.summary7dStatus.name)
        field("report.summary30dStatus", report.summary30dStatus.name)
        enumList("report.dataQuality", report.dataQuality)
        field("report.patterns.count", report.patterns.size)
        report.patterns.forEachIndexed { index, finding ->
            field("report.patterns.$index.topic", finding.topic.name)
            field("report.patterns.$index.period", finding.period.name)
            field("report.patterns.$index.direction", finding.direction.name)
            field("report.patterns.$index.confidence", finding.confidence.name)
            field("report.patterns.$index.timeBand", finding.timeBand.name)
            field("report.patterns.$index.evidenceMetric", finding.evidenceMetric.name)
            field("report.patterns.$index.evidenceValue", finding.evidenceValue)
        }
        enumList("report.safetyObservations", report.safetyObservations)
        field("report.recommendations.count", report.recommendations.size)
        report.recommendations.forEachIndexed { index, recommendation ->
            field(
                "report.recommendations.$index.careTeamDiscussionTopic",
                recommendation.careTeamDiscussionTopic.name
            )
            field("report.recommendations.$index.priority", recommendation.priority.name)
            field(
                "report.recommendations.$index.evidenceFindingIndices.count",
                recommendation.evidenceFindingIndices.size
            )
            recommendation.evidenceFindingIndices.forEachIndexed { evidenceIndex, value ->
                field(
                    "report.recommendations.$index.evidenceFindingIndices.$evidenceIndex",
                    value
                )
            }
            field("report.recommendations.$index.period", recommendation.period.name)
        }
        enumList("report.careTeamQuestions", report.careTeamQuestions)
    }

    fun metadata(metadata: ClinicalOpenAiMetadata) {
        field("metadata.model", metadata.model)
        field("metadata.requestedModel", metadata.requestedModel)
        field("metadata.systemFingerprint", metadata.systemFingerprint)
        field("metadata.schemaName", metadata.schemaName)
        field("metadata.schemaVersion", metadata.schemaVersion)
        field("metadata.datasetSchemaVersion", metadata.datasetSchemaVersion)
        field("metadata.requestHash", metadata.requestHash)
        field("metadata.chunkCount", metadata.chunkCount)
        field("metadata.usedSynthesis", metadata.usedSynthesis)
        field("metadata.reductionLevels", metadata.reductionLevels)
        field("metadata.coverageLedgerHash", metadata.coverageLedgerHash)
        field("metadata.maxRequestBytes", metadata.maxRequestBytes)
        field("metadata.maxResponseBytes", metadata.maxResponseBytes)
        field("metadata.totalRequestBytes", metadata.totalRequestBytes)
        field("metadata.totalResponseBytes", metadata.totalResponseBytes)
        field("metadata.durationMs", metadata.durationMs)
        field("metadata.sourceRows.glucose", metadata.sourceRows.glucose)
        field("metadata.sourceRows.insulin", metadata.sourceRows.insulin)
        field("metadata.sourceRows.carbs", metadata.sourceRows.carbs)
        field("metadata.sourceRows.targets", metadata.sourceRows.targets)
        field("metadata.providerId", metadata.providerId.name)
        field("metadata.requestedProviderId", metadata.requestedProviderId.name)
    }

    fun finish(): String = digest.digest().joinToString("") { byte -> "%02x".format(byte) }

    private fun enumList(name: String, values: List<Enum<*>>) {
        field("$name.count", values.size)
        values.forEachIndexed { index, value -> field("$name.$index", value.name) }
    }

    private fun bytes(value: ByteArray) {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        digest.update(value)
    }
}
