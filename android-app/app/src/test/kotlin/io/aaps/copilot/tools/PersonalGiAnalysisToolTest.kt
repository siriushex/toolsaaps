package io.aaps.copilot.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import io.aaps.copilot.domain.nutrition.MealPortionObservation
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionEpisodeEvidence
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionEpisodeProducer
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionRequest
import io.aaps.copilot.domain.nutrition.PersonalGiAnalyzer
import io.aaps.copilot.domain.nutrition.PersonalGiEvidence
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit private research bundle only. Never reads or changes a phone. */
class PersonalGiAnalysisToolTest {
    private data class Bundle(val request: PersonalAbsorptionRequest,
        val canonicalMeals: List<MealPortionObservation>,
        val evidence: List<PersonalAbsorptionEpisodeEvidence>, val gi: List<PersonalGiEvidence>,
        val comparisonCohort: String? = null)

    @Test fun analyzePrivateQualifiedBundle() {
        val inputPath = System.getenv("COPILOT_GI_ANALYSIS_JSON").orEmpty()
        assumeTrue("No optional private GI evidence bundle configured", inputPath.isNotBlank())
        val input = File(inputPath).canonicalFile
        val output = File(requireNotNull(System.getenv("COPILOT_GI_ANALYSIS_OUTPUT_JSON")) {
            "Explicit private output path required"
        }).canonicalFile
        val project = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "android-app").isDirectory }
            ?: error("Run from the source project")
        require(!input.toPath().startsWith(project.toPath()) && !output.toPath().startsWith(project.toPath())) {
            "Private research input/output must stay outside source"
        }
        require(input.isFile && input.length() <= 8 * 1024 * 1024 && input != output)
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().registerTypeAdapter(Instant::class.java,
            JsonDeserializer<Instant> { element, _, _ -> Instant.parse(element.asString) }).create()
        val bundle = runCatching { input.reader().use { gson.fromJson(it, Bundle::class.java) } }
            .getOrElse { throw IllegalArgumentException("Invalid private GI evidence bundle") }
        val source = PersonalAbsorptionEpisodeProducer().produce(bundle.request.cutoffMs,
            bundle.request.recordedZoneId, bundle.canonicalMeals, bundle.evidence)
        val result = PersonalGiAnalyzer().analyze(bundle.request, source, bundle.gi, bundle.comparisonCohort)
        val report = mapOf(
            "mode" to "retrospective_insulin_corrected_response_not_forecast",
            "modelVersion" to result.modelVersion, "status" to result.status.name,
            "trainingMeals" to result.trainingMeals, "trainingDays" to result.trainingDays,
            "validationMeals" to result.validationMeals, "validationDays" to result.validationDays,
            "model" to result.model, "personalFitStatus" to result.fitStatus?.name,
            "coefficientAtSearchBoundary" to result.coefficientAtSearchBoundary,
            "horizonResponseErrors" to result.horizons, "declaredMassStress" to result.massStress,
            "giImprovesEveryHorizon" to result.giImprovesEveryHorizon,
            "rejections" to result.rejections, "availableCohorts" to result.availableCohorts,
            "evidenceDigest" to result.evidenceDigest, "cutoffMs" to result.cutoffMs,
            "clinicalInfluenceAllowed" to result.clinicalInfluenceAllowed,
            "prospectiveForecastValidated" to result.prospectiveForecastValidated,
            "targetManagerBlockers" to result.targetManagerBlockers,
            "laboratoryGi" to result.laboratoryGi, "targetPercentage" to result.targetPercentage)
        requireNotNull(output.parentFile).mkdirs()
        Files.write(output.toPath(), gson.toJson(report).toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}
