package io.aaps.copilot.domain.target

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BaseTargetScheduleCodecTest {

    private val codec = BaseTargetScheduleCodec()

    @Test
    fun missingPayloadMigratesLegacyTargetWithAutoOff() {
        val result = codec.decode(raw = null, legacyTarget = 6.2)

        assertThat(result).isEqualTo(
            BaseTargetScheduleDecodeResult.Fallback(
                schedule = BaseTargetSchedule.legacy(6.2),
                reason = "legacy_schedule_migrated"
            )
        )
        assertThat(result.schedule().autoEnabled).isFalse()
        assertThat(result.schedule().intervals).isEmpty()
    }

    @Test
    fun malformedPayloadKeepsLegacyTargetAndReportsRecovery() {
        val raw = """{"schemaVersion":1,"defaultTargetMmol":6.0"""

        val result = codec.decode(raw = raw, legacyTarget = 6.3)

        assertThat(raw).isEqualTo("""{"schemaVersion":1,"defaultTargetMmol":6.0""")
        assertThat(result).isEqualTo(
            BaseTargetScheduleDecodeResult.Fallback(
                schedule = BaseTargetSchedule.legacy(6.3),
                reason = "malformed_schedule_json"
            )
        )
    }

    @Test
    fun unsupportedSchemaKeepsRawPayloadAndReportsRecovery() {
        val raw = """{"schemaVersion":2,"revision":17,"defaultTargetMmol":6.4,"autoEnabled":true,"intervals":[]}"""

        val result = codec.decode(raw = raw, legacyTarget = 5.9)

        assertThat(raw).isEqualTo(
            """{"schemaVersion":2,"revision":17,"defaultTargetMmol":6.4,"autoEnabled":true,"intervals":[]}"""
        )
        assertThat(result).isEqualTo(
            BaseTargetScheduleDecodeResult.Fallback(
                schedule = BaseTargetSchedule.legacy(5.9),
                reason = "unsupported_schedule_schema"
            )
        )
    }

    @Test
    fun futureSchemaWithUnknownFieldsReportsUnsupportedSchema() {
        val raw = """{"schemaVersion":2,"futureMode":"adaptive","futureSchedule":{"target":6.4}}"""

        val result = codec.decode(raw = raw, legacyTarget = 6.0)

        assertThat(result).isEqualTo(
            BaseTargetScheduleDecodeResult.Fallback(
                schedule = BaseTargetSchedule.legacy(6.0),
                reason = "unsupported_schedule_schema"
            )
        )
    }

    @Test
    fun currentSchemaWithUnknownFieldReportsMalformedPayload() {
        val raw = """{"schemaVersion":1,"revision":4,"defaultTargetMmol":6.1,"autoEnabled":false,"intervals":[],"futureMode":"adaptive"}"""

        val result = codec.decode(raw = raw, legacyTarget = 5.8)

        assertThat(result).isEqualTo(
            BaseTargetScheduleDecodeResult.Fallback(
                schedule = BaseTargetSchedule.legacy(5.8),
                reason = "malformed_schedule_json"
            )
        )
    }

    @Test
    fun nonObjectOrNonnumericSchemaReportsMalformedPayload() {
        val malformedPayloads = listOf(
            "[]",
            "42",
            "null",
            "{}",
            """{"schemaVersion":"2"}""",
            """{"schemaVersion":true}"""
        )

        malformedPayloads.forEach { raw ->
            assertThat(codec.decode(raw = raw, legacyTarget = 6.2)).isEqualTo(
                BaseTargetScheduleDecodeResult.Fallback(
                    schedule = BaseTargetSchedule.legacy(6.2),
                    reason = "malformed_schedule_json"
                )
            )
        }
    }

    @Test
    fun overLimitPayloadReportsMalformedBeforeSchemaClassification() {
        val raw = """{"schemaVersion":2,"futurePayload":"${"x".repeat(64 * 1024)}"}"""

        val result = codec.decode(raw = raw, legacyTarget = 6.2)

        assertThat(result).isEqualTo(
            BaseTargetScheduleDecodeResult.Fallback(
                schedule = BaseTargetSchedule.legacy(6.2),
                reason = "malformed_schedule_json"
            )
        )
    }

    @Test
    fun overLimitEncodedPayloadIsRejected() {
        val schedule = BaseTargetSchedule(
            revision = 1L,
            defaultTargetMmol = 6.0,
            autoEnabled = false,
            intervals = listOf(
                BaseTargetInterval(
                    id = "x".repeat(64 * 1024),
                    startMinuteOfDay = 60,
                    endMinuteOfDay = 300,
                    targetMmol = 6.0
                )
            )
        )

        val error = runCatching { codec.encode(schedule) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun invalidSchedulePayloadFallsBackWithoutReturningPartialIntervals() {
        val raw = codec.encode(
            BaseTargetSchedule(
                revision = 8L,
                defaultTargetMmol = 6.0,
                autoEnabled = true,
                intervals = listOf(
                    BaseTargetInterval("first", 60, 300, 6.1),
                    BaseTargetInterval("overlap", 240, 420, 6.2)
                )
            )
        )

        val result = codec.decode(raw = raw, legacyTarget = 5.8)

        assertThat(result).isEqualTo(
            BaseTargetScheduleDecodeResult.Fallback(
                schedule = BaseTargetSchedule.legacy(5.8),
                reason = "invalid_schedule_payload"
            )
        )
        assertThat(result.schedule().autoEnabled).isFalse()
        assertThat(result.schedule().intervals).isEmpty()
    }

    @Test
    fun validPayloadRoundTripsWithoutChangingRevision() {
        val schedule = BaseTargetSchedule(
            revision = 23L,
            defaultTargetMmol = 6.1,
            autoEnabled = true,
            intervals = listOf(
                BaseTargetInterval("night", 1320, 360, 6.5)
            )
        )

        val raw = codec.encode(schedule)
        val result = codec.decode(raw = raw, legacyTarget = 5.5)

        assertThat(raw).isEqualTo(
            """{"schemaVersion":1,"revision":23,"defaultTargetMmol":6.1,"autoEnabled":true,"intervals":[{"id":"night","startMinuteOfDay":1320,"endMinuteOfDay":360,"targetMmol":6.5}]}"""
        )
        assertThat(result).isEqualTo(BaseTargetScheduleDecodeResult.Valid(schedule))
        assertThat((result as BaseTargetScheduleDecodeResult.Valid).schedule.revision).isEqualTo(23L)
    }

    private fun BaseTargetScheduleDecodeResult.schedule(): BaseTargetSchedule {
        return when (this) {
            is BaseTargetScheduleDecodeResult.Valid -> schedule
            is BaseTargetScheduleDecodeResult.Fallback -> schedule
        }
    }
}
