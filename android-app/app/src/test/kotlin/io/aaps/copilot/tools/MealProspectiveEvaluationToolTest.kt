package io.aaps.copilot.tools

import com.google.gson.GsonBuilder
import io.aaps.copilot.domain.meal.MealForecastIdentity
import io.aaps.copilot.domain.meal.MealProspectiveCandidate
import io.aaps.copilot.domain.meal.MealProspectiveEvaluator
import io.aaps.copilot.domain.meal.MealProspectiveFrame
import io.aaps.copilot.domain.meal.MealProspectiveModel
import io.aaps.copilot.domain.meal.MealProspectiveOutcome
import io.aaps.copilot.domain.meal.MealProspectiveRegistrationStatus
import io.aaps.copilot.domain.meal.MealProspectiveValue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Revalidates explicit private receipts; a file cannot authenticate historical capture. */
class MealProspectiveEvaluationToolTest {
    private data class CandidateInput(val identity: MealForecastIdentity, val model: MealProspectiveModel,
        val inputThroughMs: Long, val inputsAvailableAtMs: Long, val startedAtMs: Long,
        val completedAtMs: Long, val values: List<MealProspectiveValue>)
    private data class ReceiptInput(val identity: MealForecastIdentity, val baselineCompletedAtMs: Long,
        val baseline: List<MealProspectiveValue>, val candidate: CandidateInput?, val registeredAtMs: Long)
    private data class Bundle(val receipts: List<ReceiptInput>, val outcomes: List<MealProspectiveOutcome>,
        val evaluatedAtMs: Long, val lowThresholdMmol: Double)

    @Test fun evaluatePrivateReceiptBundle() {
        val inputPath = System.getenv("COPILOT_PROSPECTIVE_EVALUATION_JSON").orEmpty()
        assumeTrue("No optional private prospective bundle configured", inputPath.isNotBlank())
        val input = File(inputPath).canonicalFile
        val output = File(requireNotNull(System.getenv("COPILOT_PROSPECTIVE_EVALUATION_OUTPUT_JSON")) {
            "Explicit private output path required"
        }).canonicalFile
        val project = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "android-app").isDirectory }
            ?: error("Run from the source project")
        require(!input.toPath().startsWith(project.toPath()) && !output.toPath().startsWith(project.toPath()))
        require(input.isFile && input.length() <= 8 * 1024 * 1024 && input != output)
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        val bundle = runCatching { input.reader().use { gson.fromJson(it, Bundle::class.java) } }
            .getOrElse { throw IllegalArgumentException("Invalid private prospective bundle") }
        require(bundle.receipts.size <= 7_000 && bundle.outcomes.size <= 24_000)
        val rejected = mutableMapOf<MealProspectiveRegistrationStatus, Int>()
        val frames = bundle.receipts.mapNotNull { row ->
            val registration = runCatching {
                val candidate = row.candidate?.let {
                    MealProspectiveCandidate(it.identity, it.model, it.inputThroughMs,
                        it.inputsAvailableAtMs, it.startedAtMs, it.completedAtMs, it.values)
                }
                MealProspectiveFrame.register(row.identity, row.baselineCompletedAtMs, row.baseline,
                    candidate, row.registeredAtMs)
            }.getOrElse { throw IllegalArgumentException("Malformed prospective receipt") }
            if (registration.frame == null) rejected.merge(registration.status, 1, Int::plus)
            registration.frame
        }
        val result = MealProspectiveEvaluator().evaluate(frames, bundle.outcomes, bundle.evaluatedAtMs, bundle.lowThresholdMmol)
        val report = mapOf(
            "mode" to "paired_prospective_observational_scores",
            "receiptCaptureAuthenticatedByTool" to false,
            "status" to result.status.name, "submittedReceipts" to bundle.receipts.size,
            "registrationRejections" to rejected, "registeredFrames" to result.registeredFrames,
            "baselineOnlyFrames" to result.baselineOnlyFrames, "duplicateFrames" to result.duplicateFrames,
            "conflictingFrames" to result.conflictingFrames, "unregisteredFutureFrames" to result.unregisteredFutureFrames,
            "evaluatedAtMs" to result.evaluatedAtMs, "lowThresholdMmol" to result.lowThresholdMmol,
            "horizons" to result.horizons, "clinicalInfluenceAllowed" to result.clinicalInfluenceAllowed,
            "controlSafetyValidated" to result.controlSafetyValidated)
        requireNotNull(output.parentFile).mkdirs()
        Files.write(output.toPath(), gson.toJson(report).toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}
