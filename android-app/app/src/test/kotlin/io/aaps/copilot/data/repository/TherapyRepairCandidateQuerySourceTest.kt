package io.aaps.copilot.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class TherapyRepairCandidateQuerySourceTest {

    @Test
    fun uamRepairCandidateMatchesOnlyMarkersUsedByNormalizer() {
        val source = File(
            "src/main/kotlin/io/aaps/copilot/data/local/dao/TherapyDao.kt"
        ).readText()
        val query = source
            .substringBefore("suspend fun countNightscoutRepairCandidatesSince")
            .substringAfterLast("@Query(")

        assertThat(query).contains("UAM_ENGINE|")
        assertThat(query).contains("\\\"reason\\\":\\\"%uam_engine")
        assertThat(query).doesNotContain("lower(payloadJson) LIKE '%uam_engine%'")
    }
}
