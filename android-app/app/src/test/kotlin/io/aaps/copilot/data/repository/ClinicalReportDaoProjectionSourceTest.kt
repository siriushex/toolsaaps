package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import io.aaps.copilot.data.local.dao.ClinicalTherapyProjection
import java.io.File
import org.junit.Test

class ClinicalReportDaoProjectionSourceTest {

    @Test
    fun clinicalProjectionQueriesStayBoundedAndCarryOnlyInternalSelectionIdentifiers() {
        val daoDir = sourceFile("src/main/kotlin/io/aaps/copilot/data/local/dao")
        val glucose = File(daoDir, "GlucoseDao.kt").readText()
        val therapy = File(daoDir, "TherapyDao.kt").readText()
        val forecast = File(daoDir, "ForecastDao.kt").readText()
        val telemetry = File(daoDir, "TelemetryDao.kt").readText()

        assertThat(clinicalQuery(glucose)).contains(
            "SELECT timestamp AS ts, mmol, source, quality, id AS rowId FROM glucose_samples"
        )
        assertThat(clinicalQuery(therapy))
            .contains("SELECT timestamp AS ts, type, payloadJson, id AS rowId, ")
        assertThat(clinicalQuery(therapy))
            .contains("CASE WHEN id LIKE 'br-local_broadcast-%'")
        assertThat(clinicalQuery(forecast))
            .contains("SELECT timestamp AS ts, horizonMinutes AS horizonMin, valueMmol AS mmol, ")
        assertThat(clinicalQuery(forecast))
            .contains("ciLow AS lower, ciHigh AS upper, id AS rowId, modelVersion FROM forecasts")
        assertThat(clinicalQuery(telemetry))
            .contains("SELECT timestamp AS ts, key, valueDouble AS value, quality, ")
        assertThat(clinicalQuery(telemetry))
            .contains("id AS rowId, source, unit FROM telemetry_samples")
        listOf(glucose, therapy, forecast, telemetry).forEach { source ->
            assertThat(clinicalQuery(source)).doesNotContain("SELECT *")
        }
        listOf(glucose, therapy, forecast, telemetry).forEach { source ->
            assertThat(clinicalQuery(source)).contains("BETWEEN :fromTs AND :toTs")
        }
        assertThat(clinicalQuery(therapy)).contains("type IN (:types)")
        assertThat(clinicalQuery(telemetry)).contains("key IN (:keys)")
        assertThat(
            ClinicalTherapyProjection::class.java.declaredFields
                .filterNot { it.isSynthetic || it.name.startsWith("$") }
                .map { it.name }
        )
            .containsExactly("ts", "type", "payloadJson", "isBroadcastArtifact", "rowId")

        val builder = sourceFile("src/main/kotlin/io/aaps/copilot/data/repository")
            .resolve("ClinicalReportDatasetBuilder.kt")
            .readText()
        assertThat(builder).contains("db.withTransaction")
        assertThat(builder).contains("source.readSnapshot(request)")
        assertThat(builder).contains("\"uam_calculated_carbs_grams\"")
        assertThat(builder).contains("\"isf_runtime_selected_value\"")
        assertThat(builder).contains("\"cr_runtime_selected_value\"")
        assertThat(builder).doesNotContain("\"uam_calculated_grams\"")
        assertThat(builder).doesNotContain("\"isf_effective\"")
        assertThat(builder).doesNotContain("\"cr_effective\"")

        val producer = sourceFile("src/main/kotlin/io/aaps/copilot/data/repository")
            .resolve("AutomationRepository.kt")
            .readText()
        assertThat(producer).contains(
            "addNumeric(\"uam_calculated_carbs_grams\", snapshot.estimatedCarbsGrams ?: 0.0, \"g\")"
        )
        assertThat(producer).contains(
            "addNumeric(\"isf_runtime_selected_value\", latestTelemetry[\"isf_runtime_selected_value\"], \"mmol/L/U\")"
        )
        assertThat(producer).contains(
            "addNumeric(\"cr_runtime_selected_value\", latestTelemetry[\"cr_runtime_selected_value\"], \"g/U\")"
        )
    }

    private fun clinicalQuery(source: String): String {
        val method = "suspend fun betweenForClinicalReport"
        val methodIndex = source.indexOf(method)
        check(methodIndex >= 0) { "Missing $method" }
        val queryIndex = source.lastIndexOf("@Query(", methodIndex)
        check(queryIndex >= 0) { "Missing @Query for $method" }
        return source.substring(queryIndex, methodIndex)
    }

    private fun sourceFile(relativePath: String): File {
        val moduleDir = File(requireNotNull(System.getProperty("user.dir")))
        return File(moduleDir, relativePath).takeIf(File::exists)
            ?: File(moduleDir, "app/$relativePath").takeIf(File::exists)
            ?: error("Missing source path: $relativePath from $moduleDir")
    }
}
