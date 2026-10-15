package io.aaps.copilot.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import io.aaps.copilot.data.local.entity.MealProfileOverrideEntity
import io.aaps.copilot.data.local.entity.TherapyEventEntity
import io.aaps.copilot.data.repository.PersonalAbsorptionRepository
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionEstimator
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionInput
import io.aaps.copilot.domain.nutrition.PersonalAbsorptionRequest
import java.io.File
import java.sql.DriverManager
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional private-copy tool, not device instrumentation or production wiring. */
class PersonalAbsorptionReplayToolTest {
    private data class Bundle(val request: PersonalAbsorptionRequest, val inputs: List<PersonalAbsorptionInput>)

    @Test fun evaluatePrivateEvidenceOrAuditCopy() {
        val input = System.getenv("COPILOT_ABSORPTION_EVIDENCE_JSON").orEmpty()
        val db = System.getenv("COPILOT_ABSORPTION_DB").orEmpty()
        assumeTrue("No optional private absorption input configured", input.isNotBlank() || db.isNotBlank())
        require(input.isBlank() || db.isBlank()) { "Choose evidence JSON or a database-copy audit, not both" }
        val output = File(requireNotNull(System.getenv("COPILOT_ABSORPTION_OUTPUT_JSON")) {
            "An explicit private output path is required"
        }).canonicalFile
        val sourceRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "android-app").isDirectory }
            ?.toPath() ?: error("Run the diagnostic tool from the source project")
        require(!output.toPath().startsWith(sourceRoot)) { "Diagnostic output must stay outside source" }
        val gson = GsonBuilder().setPrettyPrinting().serializeNulls().registerTypeAdapter(Instant::class.java,
            JsonDeserializer<Instant> { element, _, _ -> Instant.parse(element.asString) }).create()
        val report = if (input.isNotBlank()) {
            val file = File(input).canonicalFile
            require(file.isFile && file.length() <= 8 * 1024 * 1024) { "Invalid or oversized evidence bundle" }
            require(file != output) { "Output cannot replace input" }
            val bundle = runCatching { file.reader().use { gson.fromJson(it, Bundle::class.java) } }
                .getOrElse { throw IllegalArgumentException("Invalid diagnostic evidence JSON") }
            val result = PersonalAbsorptionEstimator().evaluate(bundle.request, bundle.inputs)
            linkedMapOf<String, Any?>(
                "mode" to "retrospective_declared_mass_conditional",
                "modelVersion" to result.modelVersion, "status" to result.status.name,
                "trainingMeals" to result.trainingMeals, "trainingDays" to result.trainingDays,
                "validationMeals" to result.validationMeals, "validationDays" to result.validationDays,
                "candidate" to result.candidate, "parameterEnvelope" to result.envelope,
                "baselineDeltaMaeMmol5" to result.baselineMaeMmol5,
                "candidateDeltaMaeMmol5" to result.candidateMaeMmol5,
                "baselineDeltaBiasMmol5" to result.baselineBiasMmol5,
                "candidateDeltaBiasMmol5" to result.candidateBiasMmol5,
                "dayScores" to result.days.map { mapOf("date" to it.date.toString(), "meals" to it.meals,
                    "baselineDeltaMaeMmol5" to it.baselineMaeMmol5, "candidateDeltaMaeMmol5" to it.candidateMaeMmol5) },
                "rejections" to result.rejections.mapKeys { it.key.name },
                "evidenceDigest" to result.evidenceDigest, "evidenceCutoffMs" to result.evidenceCutoffMs,
                "actualMassAccuracyValidated" to false, "clinicalInfluenceAllowed" to false,
                "prospectiveForecastMae" to null, "laboratoryGi" to null, "targetPercentage" to null)
        } else auditCopy(File(db).canonicalFile, output, gson)
        requireNotNull(output.parentFile) { "Output needs a parent directory" }.mkdirs()
        Files.write(output.toPath(), gson.toJson(report).toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun auditCopy(file: File, output: File, gson: Gson): Map<String, Any?> {
        require(file.isFile && file != output) { "A saved database copy is required" }
        val cutoff = requireNotNull(System.getenv("COPILOT_ABSORPTION_CUTOFF_MS")) {
            "Explicit audit cutoff is required"
        }.toLong()
        val zone = ZoneId.of(requireNotNull(System.getenv("COPILOT_ABSORPTION_ZONE")))
        val end = Instant.ofEpochMilli(cutoff).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val start = end.atZone(zone).minusDays(21).toInstant()
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${file.toURI()}?mode=ro").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA query_only=ON") }
            connection.autoCommit = false
            val rows = connection.prepareStatement("SELECT id,timestamp,type,payloadJson FROM therapy_events " +
                "WHERE timestamp >= ? AND timestamp < ? AND type IN ('carbs','meal_bolus') " +
                "ORDER BY timestamp,type,id LIMIT 5001").use { statement ->
                statement.setLong(1, start.toEpochMilli()); statement.setLong(2, end.toEpochMilli())
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) add(TherapyEventEntity(result.getString("id"), result.getLong("timestamp"),
                        result.getString("type"), result.getString("payloadJson")))
                } }
            }
            val overrides = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT canonicalTherapyIdentity,therapyRevisionHash,profile,durationMinutes," +
                    "source,revision,updatedAtMs,portion,portionProvenance,confirmedCarbsGrams " +
                    "FROM meal_profile_overrides ORDER BY canonicalTherapyIdentity LIMIT 5001").use { result -> buildList {
                    while (result.next()) add(MealProfileOverrideEntity(result.getString(1), result.getString(2),
                        result.getString(3), result.getInt(4), result.getString(5), result.getLong(6), result.getLong(7),
                        result.getString(8), result.getString(9), (result.getObject(10) as? Number)?.toDouble()))
                } }
            }
            val audit = PersonalAbsorptionRepository.auditRows(rows, overrides, gson, start, end, Instant.ofEpochMilli(cutoff))
            mapOf("mode" to "read_only_history_audit", "completedDays" to 21,
                "windowZone" to zone.id, "windowStartMs" to start.toEpochMilli(), "windowEndMs" to end.toEpochMilli(),
                "rawCarbRows" to audit.rawCarbRows, "syntheticCarbRows" to audit.syntheticCarbRows,
                "independentDeclaredMeals" to audit.independentDeclaredMeals,
                "eligibleTimingMeals" to 0, "missingInputs" to audit.missingInputs.sorted(),
                "inputOverflow" to (audit.inputOverflow || overrides.size > 5000),
                "actualMassAccuracyValidated" to false, "clinicalInfluenceAllowed" to false)
        }
    }
}
